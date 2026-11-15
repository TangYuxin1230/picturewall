package com.rong.picturewall;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class PictureTextureCache {
	public record Texture(Identifier id, int width, int height) {
	}

	private static byte[] convertToPng(byte[] bytes) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		if (image == null) {
			throw new IOException("Image data could not be decoded");
		}
		try {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			if (!ImageIO.write(image, "png", output)) {
				throw new IOException("Could not convert image to PNG");
			}
			return output.toByteArray();
		} finally {
			image.flush();
		}
	}

	public record ImageSize(int width, int height) {
	}

	private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
	private static final long MAX_TEXTURE_MEMORY_BYTES = 128L * 1024 * 1024;
	private static final int MAX_TEXTURES = 64;
	private static final int MAX_ACTIVE_VIDEOS = 2;
	private static final int MAX_ACTIVE_GIFS = 2;
	private static final Set<String> SUPPORTED_IMAGE_FORMATS =
			Set.of("png", "jpeg", "jpg", "gif", "bmp", "wbmp", "tiff", "tif");
	private static final Map<String, Texture> TEXTURES = new ConcurrentHashMap<>();
	private static final Map<String, PictureVideoPlayer> VIDEOS = new ConcurrentHashMap<>();
	private static final Map<String, PictureGifPlayer> GIFS = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();
	private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
	private static final Set<String> APPROVED_REMOTE_SOURCES = ConcurrentHashMap.newKeySet();
	private static final AtomicInteger TEXTURE_COUNT = new AtomicInteger();
	private static final AtomicInteger ACTIVE_VIDEO_COUNT = new AtomicInteger();
	private static final AtomicInteger ACTIVE_GIF_COUNT = new AtomicInteger();
	private static final AtomicLong TEXTURE_MEMORY_BYTES = new AtomicLong();

	public enum MediaType {
		IMAGE, GIF, VIDEO
	}

	private PictureTextureCache() {
	}

	public static void retry(String source) {
		FAILED.remove(source);
		if (!source.startsWith("local:")) {
			APPROVED_REMOTE_SOURCES.add(source);
		}
	}

	public static boolean hasFailed(String source) {
		return FAILED.contains(source);
	}

	public static Texture get(String source) {
		Texture cached = TEXTURES.get(source);
		if (cached != null) {
			updateVideoTexture(source);
			updateGifTexture(source);
			return cached;
		}
		if (FAILED.contains(source)) {
			return null;
		}
		if (!source.startsWith("local:") && !APPROVED_REMOTE_SOURCES.contains(source)) {
			return null;
		}
		if (LOADING.putIfAbsent(source, true) == null) {
			if (!reserveTextureSlot()) {
				LOADING.remove(source);
				return null;
			}
			try {
				PictureWallThreads.execute(() -> load(source));
			} catch (RuntimeException exception) {
				LOADING.remove(source);
				TEXTURE_COUNT.decrementAndGet();
				FAILED.add(source);
				PictureWallMod.LOGGER.error("Could not start image loading for a picture frame", exception);
			}
		}
		return null;
	}

	private static void load(String source) {
		try {
			byte[] bytes = readBytes(source);
			if (PictureGifPlayer.isGif(bytes) && PictureGifPlayer.isAnimated(bytes)) {
				loadGif(source, bytes);
				return;
			}
			if (PictureVideoPlayer.isMp4(bytes)) {
				loadVideo(source, bytes);
				return;
			}
			ImageSize size = validateImage(bytes);
			bytes = convertToPng(bytes);
			long memoryBytes = (long) size.width() * size.height() * 4;
			if (!reserveTextureMemory(memoryBytes)) {
				throw new IllegalArgumentException("The picture texture memory limit has been reached");
			}
			NativeImage image;
			try {
				image = NativeImage.read(new ByteArrayInputStream(bytes));
			} catch (Exception exception) {
				TEXTURE_MEMORY_BYTES.addAndGet(-memoryBytes);
				throw exception;
			}
			try {
				MinecraftClient.getInstance().execute(() -> {
					try {
						Identifier id = MinecraftClient.getInstance().getTextureManager()
								.registerDynamicTexture("picturewall", new NativeImageBackedTexture(image));
						TEXTURES.put(source, new Texture(id, size.width(), size.height()));
					} catch (RuntimeException exception) {
						image.close();
						TEXTURE_MEMORY_BYTES.addAndGet(-memoryBytes);
						TEXTURE_COUNT.decrementAndGet();
						FAILED.add(source);
						PictureWallMod.LOGGER.error("Could not create texture for {}", source, exception);
					} finally {
						LOADING.remove(source);
					}
				});
			} catch (RuntimeException exception) {
				image.close();
				TEXTURE_MEMORY_BYTES.addAndGet(-memoryBytes);
				throw exception;
			}
		} catch (Exception exception) {
			LOADING.remove(source);
			TEXTURE_COUNT.decrementAndGet();
			FAILED.add(source);
			PictureWallMod.LOGGER.warn("Could not load a picture texture", exception);
		}
	}

	private static void loadGif(String source, byte[] bytes) throws IOException {
		if (!reserveGifSlot()) {
			throw new IOException("The client already has the maximum number of animated GIFs");
		}
		PictureGifPlayer player = null;
		long memoryBytes = 0;
		boolean memoryReserved = false;
		boolean handedOff = false;
		try {
			player = PictureGifPlayer.open(bytes);
			PictureGifPlayer.Metadata metadata = player.metadata();
			memoryBytes = (long) metadata.width() * metadata.height() * 4;
			if (!reserveTextureMemory(memoryBytes)) {
				throw new IOException("The picture texture memory limit has been reached");
			}
			memoryReserved = true;
			PictureGifPlayer gif = player;
			long reservedMemory = memoryBytes;
			MinecraftClient.getInstance().execute(() -> registerGif(source, gif, reservedMemory));
			handedOff = true;
		} finally {
			if (!handedOff) {
				if (player != null) {
					player.close();
				}
				if (memoryReserved) {
					TEXTURE_MEMORY_BYTES.addAndGet(-memoryBytes);
				}
				ACTIVE_GIF_COUNT.decrementAndGet();
			}
		}
	}

	private static void registerGif(String source, PictureGifPlayer player, long memoryBytes) {
		NativeImage initialFrame = player.takeInitialFrame();
		NativeImageBackedTexture dynamicTexture = null;
		Identifier id = null;
		try {
			dynamicTexture = new NativeImageBackedTexture(initialFrame);
			id = MinecraftClient.getInstance().getTextureManager()
					.registerDynamicTexture("picturewall_gif", dynamicTexture);
			player.attachTexture(dynamicTexture);
			TEXTURES.put(source, new Texture(id, player.metadata().width(), player.metadata().height()));
			GIFS.put(source, player);
			player.start(ACTIVE_GIF_COUNT::decrementAndGet);
		} catch (RuntimeException exception) {
			if (id != null) {
				MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
			} else if (dynamicTexture != null) {
				dynamicTexture.close();
			} else if (initialFrame != null) {
				initialFrame.close();
			}
			player.close();
			TEXTURES.remove(source);
			GIFS.remove(source);
			TEXTURE_MEMORY_BYTES.addAndGet(-memoryBytes);
			TEXTURE_COUNT.decrementAndGet();
			ACTIVE_GIF_COUNT.decrementAndGet();
			FAILED.add(source);
			PictureWallMod.LOGGER.error("Could not start animated GIF playback for {}", source, exception);
		} finally {
			LOADING.remove(source);
		}
	}

	private static void loadVideo(String source, byte[] bytes) throws IOException {
		if (!reserveVideoSlot()) {
			throw new IOException("The client already has the maximum number of active videos");
		}
		PictureVideoPlayer player = null;
		long memoryBytes = 0;
		boolean memoryReserved = false;
		boolean handedOff = false;
		try {
			player = PictureVideoPlayer.open(bytes);
			memoryBytes = player.reservedMemoryBytes();
			if (!reserveTextureMemory(memoryBytes)) {
				throw new IOException("The picture texture memory limit has been reached");
			}
			memoryReserved = true;
			player.prepareFrameBuffers();
			PictureVideoPlayer video = player;
			long reservedMemory = memoryBytes;
			MinecraftClient.getInstance().execute(() -> registerVideo(source, video, reservedMemory));
			handedOff = true;
		} finally {
			if (!handedOff) {
				if (player != null) {
					player.close();
				}
				if (memoryReserved) {
					TEXTURE_MEMORY_BYTES.addAndGet(-memoryBytes);
				}
				ACTIVE_VIDEO_COUNT.decrementAndGet();
			}
		}
	}

	private static void registerVideo(String source, PictureVideoPlayer player, long memoryBytes) {
		NativeImage initialFrame = player.takeInitialFrame();
		NativeImageBackedTexture dynamicTexture = null;
		Identifier id = null;
		try {
			dynamicTexture = new NativeImageBackedTexture(initialFrame);
			id = MinecraftClient.getInstance().getTextureManager()
					.registerDynamicTexture("picturewall_video", dynamicTexture);
			player.attachTexture(dynamicTexture);
			TEXTURES.put(source, new Texture(id, player.metadata().width(), player.metadata().height()));
			VIDEOS.put(source, player);
			player.start(ACTIVE_VIDEO_COUNT::decrementAndGet);
		} catch (RuntimeException exception) {
			if (id != null) {
				MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
			} else if (dynamicTexture != null) {
				dynamicTexture.close();
			} else if (initialFrame != null) {
				initialFrame.close();
			}
			player.close();
			TEXTURES.remove(source);
			VIDEOS.remove(source);
			TEXTURE_MEMORY_BYTES.addAndGet(-memoryBytes);
			TEXTURE_COUNT.decrementAndGet();
			ACTIVE_VIDEO_COUNT.decrementAndGet();
			FAILED.add(source);
			PictureWallMod.LOGGER.error("Could not start MP4 playback for {}", source, exception);
		} finally {
			LOADING.remove(source);
		}
	}

	private static boolean reserveVideoSlot() {
		int current = ACTIVE_VIDEO_COUNT.get();
		while (current < MAX_ACTIVE_VIDEOS) {
			if (ACTIVE_VIDEO_COUNT.compareAndSet(current, current + 1)) {
				return true;
			}
			current = ACTIVE_VIDEO_COUNT.get();
		}
		return false;
	}

	private static boolean reserveGifSlot() {
		int current = ACTIVE_GIF_COUNT.get();
		while (current < MAX_ACTIVE_GIFS) {
			if (ACTIVE_GIF_COUNT.compareAndSet(current, current + 1)) {
				return true;
			}
			current = ACTIVE_GIF_COUNT.get();
		}
		return false;
	}

	private static void updateVideoTexture(String source) {
		PictureVideoPlayer player = VIDEOS.get(source);
		if (player == null) {
			return;
		}
		NativeImage frame = player.pollLatestFrame();
		if (frame == null) {
			return;
		}
		try {
			NativeImageBackedTexture texture = player.texture();
			if (texture != null) {
				texture.getImage().copyFrom(frame);
				texture.upload();
			}
		} catch (RuntimeException exception) {
			PictureWallMod.LOGGER.warn("Could not update an MP4 video texture", exception);
		} finally {
			player.releaseFrame(frame);
		}
	}

	private static void updateGifTexture(String source) {
		PictureGifPlayer player = GIFS.get(source);
		if (player == null) {
			return;
		}
		NativeImage frame = player.pollLatestFrame();
		if (frame == null) {
			return;
		}
		try {
			NativeImageBackedTexture texture = player.texture();
			if (texture != null) {
				texture.getImage().copyFrom(frame);
				texture.upload();
			}
		} catch (RuntimeException exception) {
			PictureWallMod.LOGGER.warn("Could not update an animated GIF texture", exception);
		} finally {
			frame.close();
		}
	}

	public static void stopAnimations() {
		for (PictureVideoPlayer player : VIDEOS.values()) {
			player.close();
		}
		for (PictureGifPlayer player : GIFS.values()) {
			player.close();
		}
		VIDEOS.clear();
		GIFS.clear();
	}

	public static ImageSize validateImage(byte[] bytes) throws IOException {
		if (bytes.length > MAX_IMAGE_BYTES) {
			throw new IOException("Image file exceeds the 5 MiB limit");
		}
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (input == null) {
				throw new IOException("Image data could not be read");
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				throw new IOException("Unsupported image format");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				String format = reader.getFormatName();
				if (!SUPPORTED_IMAGE_FORMATS.contains(format.toLowerCase(java.util.Locale.ROOT))) {
					throw new IOException("Unsupported image format");
				}
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > 4096 || height > 4096
						|| (long) width * height > 16_777_216L) {
					throw new IOException("Image dimensions exceed the supported limit");
				}
				return new ImageSize(width, height);
			} finally {
				reader.dispose();
			}
		}
	}

	public static MediaType validateMedia(byte[] bytes) throws IOException {
		if (PictureGifPlayer.isGif(bytes)) {
			validateImage(bytes);
			if (PictureGifPlayer.isAnimated(bytes)) {
				PictureGifPlayer.validate(bytes);
			}
			return MediaType.GIF;
		}
		if (PictureVideoPlayer.isMp4(bytes)) {
			PictureVideoPlayer.validate(bytes);
			return MediaType.VIDEO;
		}
		validateImage(bytes);
		return MediaType.IMAGE;
	}

	private static boolean reserveTextureMemory(long bytes) {
		long current = TEXTURE_MEMORY_BYTES.get();
		while (current + bytes <= MAX_TEXTURE_MEMORY_BYTES) {
			if (TEXTURE_MEMORY_BYTES.compareAndSet(current, current + bytes)) {
				return true;
			}
			current = TEXTURE_MEMORY_BYTES.get();
		}
		return false;
	}

	private static boolean reserveTextureSlot() {
		int current = TEXTURE_COUNT.get();
		while (current < MAX_TEXTURES) {
			if (TEXTURE_COUNT.compareAndSet(current, current + 1)) {
				return true;
			}
			current = TEXTURE_COUNT.get();
		}
		return false;
	}

	private static byte[] readBytes(String source) throws Exception {
		if (source.startsWith("local:")) {
			String filename = source.substring("local:".length());
			Path directory = MinecraftClient.getInstance().runDirectory.toPath()
					.resolve("config/picturewall").toAbsolutePath().normalize();
			Path image = directory.resolve(filename).normalize();
			if (!image.getParent().equals(directory) || !Files.isRegularFile(image)) {
				throw new IllegalArgumentException("Local image was not found");
			}
			if (Files.size(image) > PictureVideoPlayer.MAX_VIDEO_BYTES) {
				throw new IllegalArgumentException("Media file exceeds the 50 MiB limit");
			}
			return Files.readAllBytes(image);
		}

		URI uri = URI.create(source);
		if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
			throw new IllegalArgumentException("Only HTTP and HTTPS image URLs are supported");
		}
		HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(10000);
		connection.setInstanceFollowRedirects(false);
		connection.setRequestProperty("Accept", "image/*,video/mp4");
		try {
			int status = connection.getResponseCode();
			if (status < 200 || status >= 300) {
				throw new IllegalArgumentException("Image server returned HTTP " + status);
			}
			if (connection.getContentLengthLong() > PictureVideoPlayer.MAX_VIDEO_BYTES) {
				throw new IllegalArgumentException("Media response exceeds the 50 MiB limit");
			}
			try (InputStream input = connection.getInputStream();
				 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				byte[] buffer = new byte[8192];
				int count;
				while ((count = input.read(buffer)) != -1) {
					if (output.size() + count > PictureVideoPlayer.MAX_VIDEO_BYTES) {
						throw new IllegalArgumentException("Media response exceeds the 50 MiB limit");
					}
					output.write(buffer, 0, count);
				}
				return output.toByteArray();
			}
		} finally {
			connection.disconnect();
		}
	}
}
