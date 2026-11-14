package com.rong.picturewall;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;

final class PictureGifPlayer {
	private static final int MAX_GIF_BYTES = 5 * 1024 * 1024;
	private static final int MAX_FRAME_COUNT = 300;
	private static final int MAX_DIMENSION = 1280;
	private static final long MAX_PIXELS = 921_600L;
	private static final long MIN_FRAME_DELAY_MILLIS = 50L;

	private final GifDecoder decoder;
	private final Metadata metadata;
	private final ArrayBlockingQueue<NativeImage> frames = new ArrayBlockingQueue<>(2);
	private volatile boolean running;
	private volatile boolean started;
	private volatile NativeImageBackedTexture texture;
	private NativeImage initialFrame;
	private long initialDelayMillis;

	private PictureGifPlayer(GifDecoder decoder, Metadata metadata, NativeImage initialFrame,
			long initialDelayMillis) {
		this.decoder = decoder;
		this.metadata = metadata;
		this.initialFrame = initialFrame;
		this.initialDelayMillis = initialDelayMillis;
	}

	static boolean isGif(byte[] data) {
		return data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
				&& data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a';
	}

	static boolean isAnimated(byte[] data) throws IOException {
		try (GifDecoder decoder = GifDecoder.open(data)) {
			return decoder.metadata.frameCount > 1;
		}
	}

	static void validate(byte[] data) throws IOException {
		try (GifDecoder decoder = GifDecoder.open(data)) {
			if (decoder.metadata.frameCount > 1) {
				validateAnimationDimensions(decoder.metadata.width, decoder.metadata.height);
				if (decoder.metadata.frameCount > MAX_FRAME_COUNT) {
					throw new IOException("Animated GIF exceeds the 300-frame limit");
				}
			}
			BufferedImage firstFrame = decoder.reader.read(0);
			if (firstFrame == null) {
				throw new IOException("GIF contains no decodable frames");
			}
			firstFrame.flush();
		} catch (RuntimeException exception) {
			throw new IOException("Could not read the GIF animation", exception);
		}
	}

	static PictureGifPlayer open(byte[] data) throws IOException {
		GifDecoder decoder = GifDecoder.open(data);
		NativeImage initialFrame = null;
		try {
			Metadata metadata = decoder.metadata;
			if (metadata.frameCount < 2 || metadata.frameCount > MAX_FRAME_COUNT) {
				throw new IOException("Animated GIF must contain 2 to 300 frames");
			}
			validateAnimationDimensions(metadata.width, metadata.height);
			Frame first = decoder.readNextFrame();
			initialFrame = first.image;
			return new PictureGifPlayer(decoder, metadata, initialFrame, first.delayMillis);
		} catch (IOException exception) {
			if (initialFrame != null) {
				initialFrame.close();
			}
			decoder.close();
			throw exception;
		} catch (RuntimeException exception) {
			if (initialFrame != null) {
				initialFrame.close();
			}
			decoder.close();
			throw new IOException("Could not decode the first GIF frame", exception);
		}
	}

	private static void validateAnimationDimensions(int width, int height) throws IOException {
		if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
				|| (long) width * height > MAX_PIXELS) {
			throw new IOException("Animated GIF dimensions exceed the supported 1280-pixel side or pixel limits");
		}
	}

	Metadata metadata() {
		return metadata;
	}

	NativeImage takeInitialFrame() {
		NativeImage frame = initialFrame;
		initialFrame = null;
		return frame;
	}

	void attachTexture(NativeImageBackedTexture texture) {
		this.texture = texture;
	}

	NativeImageBackedTexture texture() {
		return texture;
	}

	void start(Runnable onStopped) {
		running = true;
		started = true;
		try {
			Thread.startVirtualThread(() -> {
				try {
					play();
				} catch (IOException | RuntimeException exception) {
					if (running) {
						PictureWallMod.LOGGER.warn("Animated GIF playback stopped", exception);
					}
				} finally {
					running = false;
					decoder.close();
					drainFrames();
					onStopped.run();
				}
			});
		} catch (RuntimeException exception) {
			running = false;
			started = false;
			decoder.close();
			throw exception;
		}
	}

	private void play() throws IOException {
		Frame frame = decoder.readNextFrame();
		long deadline = System.nanoTime() + initialDelayMillis * 1_000_000L;
		try {
			while (running) {
				waitUntil(deadline);
				if (!running) {
					break;
				}
				publishFrame(frame.image);
				deadline += frame.delayMillis * 1_000_000L;
				frame = null;
				frame = decoder.readNextFrame();
			}
		} finally {
			if (frame != null) {
				frame.image.close();
			}
		}
	}

	private void waitUntil(long deadline) {
		while (running) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				return;
			}
			try {
				Thread.sleep(Math.max(1, Math.min(50, remaining / 1_000_000)));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				running = false;
			}
		}
	}

	private void publishFrame(NativeImage image) {
		if (frames.offer(image)) {
			return;
		}
		NativeImage discarded = frames.poll();
		if (discarded != null) {
			discarded.close();
		}
		if (!frames.offer(image)) {
			image.close();
		}
	}

	NativeImage pollLatestFrame() {
		NativeImage latest = frames.poll();
		if (latest == null) {
			return null;
		}
		NativeImage next;
		while ((next = frames.poll()) != null) {
			latest.close();
			latest = next;
		}
		return latest;
	}

	void close() {
		running = false;
		if (!started) {
			decoder.close();
		}
		if (initialFrame != null) {
			initialFrame.close();
			initialFrame = null;
		}
		drainFrames();
	}

	private void drainFrames() {
		NativeImage frame;
		while ((frame = frames.poll()) != null) {
			frame.close();
		}
	}

	private static NativeImage toNativeImage(BufferedImage image) throws IOException {
		ByteArrayOutputStream encoded = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", encoded)) {
			throw new IOException("Could not convert a GIF frame");
		}
		return NativeImage.read(new ByteArrayInputStream(encoded.toByteArray()));
	}

	record Metadata(int width, int height, int frameCount) {
	}

	private record Frame(NativeImage image, long delayMillis) {
	}

	private record FrameInfo(int x, int y, int width, int height, String disposal, long delayMillis) {
	}

	private static final class GifDecoder implements AutoCloseable {
		private final ImageInputStream input;
		private final ImageReader reader;
		private final Metadata metadata;
		private BufferedImage canvas;
		private PreviousFrame previousFrame;
		private int frameIndex;

		private GifDecoder(ImageInputStream input, ImageReader reader, Metadata metadata) {
			this.input = input;
			this.reader = reader;
			this.metadata = metadata;
		}

		private static GifDecoder open(byte[] data) throws IOException {
			if (!isGif(data) || data.length > MAX_GIF_BYTES) {
				throw new IOException("Invalid or oversized GIF");
			}
			ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data));
			if (input == null) {
				throw new IOException("Could not open the GIF image data");
			}
			ImageReader reader = null;
			try {
				Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
				if (!readers.hasNext()) {
					throw new IOException("Unsupported GIF image data");
				}
				reader = readers.next();
				if (!"gif".equalsIgnoreCase(reader.getFormatName())) {
					throw new IOException("Unsupported GIF image data");
				}
				reader.setInput(input, false, false);
				int frameCount = reader.getNumImages(true);
				if (frameCount < 1) {
					throw new IOException("GIF contains no frames");
				}
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				IIOMetadata streamMetadata = reader.getStreamMetadata();
				if (streamMetadata != null) {
					Node screen = child(streamMetadata.getAsTree("javax_imageio_gif_stream_1.0"),
							"LogicalScreenDescriptor");
					if (screen != null) {
						width = intAttribute(screen, "logicalScreenWidth", width);
						height = intAttribute(screen, "logicalScreenHeight", height);
					}
				}
				return new GifDecoder(input, reader, new Metadata(width, height, frameCount));
			} catch (IOException | RuntimeException exception) {
				if (reader != null) {
					reader.dispose();
				}
				try {
					input.close();
				} catch (IOException closeException) {
					PictureWallMod.LOGGER.debug("Could not close a GIF image stream", closeException);
				}
				if (exception instanceof IOException ioException) {
					throw ioException;
				}
				throw new IOException("Could not read GIF metadata", exception);
			}
		}

		private Frame readNextFrame() throws IOException {
			if (canvas == null) {
				canvas = new BufferedImage(metadata.width, metadata.height, BufferedImage.TYPE_INT_ARGB);
			}
			if (frameIndex >= metadata.frameCount) {
				if (previousFrame != null && previousFrame.restoreCanvas != null) {
					previousFrame.restoreCanvas.flush();
				}
				canvas.flush();
				canvas = new BufferedImage(metadata.width, metadata.height, BufferedImage.TYPE_INT_ARGB);
				previousFrame = null;
				frameIndex = 0;
			} else {
				applyPreviousDisposal();
			}

			FrameInfo info = readFrameInfo(frameIndex);
			BufferedImage restoreCanvas = "restoreToPrevious".equals(info.disposal) ? copy(canvas) : null;
			BufferedImage frame = reader.read(frameIndex);
			if (frame == null) {
				throw new IOException("GIF frame " + frameIndex + " could not be decoded");
			}
			Graphics2D graphics = canvas.createGraphics();
			try {
				graphics.setComposite(AlphaComposite.SrcOver);
				graphics.drawImage(frame, info.x, info.y, null);
			} finally {
				graphics.dispose();
				frame.flush();
			}
			previousFrame = new PreviousFrame(info, restoreCanvas);
			frameIndex++;
			return new Frame(toNativeImage(canvas), info.delayMillis);
		}

		private FrameInfo readFrameInfo(int index) throws IOException {
			IIOMetadata imageMetadata = reader.getImageMetadata(index);
			Node root = imageMetadata.getAsTree("javax_imageio_gif_image_1.0");
			Node descriptor = child(root, "ImageDescriptor");
			Node control = child(root, "GraphicControlExtension");
			int x = descriptor == null ? 0 : intAttribute(descriptor, "imageLeftPosition", 0);
			int y = descriptor == null ? 0 : intAttribute(descriptor, "imageTopPosition", 0);
			int width = descriptor == null ? reader.getWidth(index)
					: intAttribute(descriptor, "imageWidth", reader.getWidth(index));
			int height = descriptor == null ? reader.getHeight(index)
					: intAttribute(descriptor, "imageHeight", reader.getHeight(index));
			String disposal = control == null ? "none"
					: stringAttribute(control, "disposalMethod", "none");
			long delay = control == null ? 100L
					: intAttribute(control, "delayTime", 10) * 10L;
			if (width < 1 || height < 1 || x < 0 || y < 0 || x + width > metadata.width
					|| y + height > metadata.height) {
				throw new IOException("GIF frame dimensions are invalid");
			}
			return new FrameInfo(x, y, width, height, disposal, Math.max(MIN_FRAME_DELAY_MILLIS, delay));
		}

		private void applyPreviousDisposal() {
			if (previousFrame == null) {
				return;
			}
			FrameInfo info = previousFrame.info;
			switch (info.disposal) {
				case "restoreToBackgroundColor" -> {
					Graphics2D graphics = canvas.createGraphics();
					try {
						graphics.setComposite(AlphaComposite.Clear);
						graphics.fillRect(info.x, info.y, info.width, info.height);
					} finally {
						graphics.dispose();
					}
				}
				case "restoreToPrevious" -> {
					if (previousFrame.restoreCanvas != null) {
						canvas.flush();
						canvas = previousFrame.restoreCanvas;
					}
				}
				default -> {
				}
			}
			previousFrame = null;
		}

		private static BufferedImage copy(BufferedImage source) {
			BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(),
					BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = result.createGraphics();
			try {
				graphics.drawImage(source, 0, 0, null);
			} finally {
				graphics.dispose();
			}
			return result;
		}

		private static Node child(Node parent, String name) {
			for (Node node = parent == null ? null : parent.getFirstChild(); node != null;
				 node = node.getNextSibling()) {
				if (name.equals(node.getNodeName())) {
					return node;
				}
			}
			return null;
		}

		private static int intAttribute(Node node, String name, int fallback) {
			String value = stringAttribute(node, name, null);
			if (value == null) {
				return fallback;
			}
			try {
				return Integer.parseInt(value);
			} catch (NumberFormatException exception) {
				return fallback;
			}
		}

		private static String stringAttribute(Node node, String name, String fallback) {
			NamedNodeMap attributes = node.getAttributes();
			Node attribute = attributes == null ? null : attributes.getNamedItem(name);
			return attribute == null ? fallback : attribute.getNodeValue();
		}

		@Override
		public void close() {
			reader.dispose();
			if (canvas != null) {
				canvas.flush();
			}
			if (previousFrame != null && previousFrame.restoreCanvas != null) {
				previousFrame.restoreCanvas.flush();
			}
			try {
				input.close();
			} catch (IOException exception) {
				PictureWallMod.LOGGER.debug("Could not close a GIF image stream", exception);
			}
		}

		private record PreviousFrame(FrameInfo info, BufferedImage restoreCanvas) {
		}
	}
}
