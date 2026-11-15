package com.rong.picturewall;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.api.specific.AVCMP4Adaptor;
import org.jcodec.api.specific.ContainerAdaptor;
import org.jcodec.api.specific.GenericAdaptor;
import org.jcodec.codecs.mpeg4.mp4.EsdsBox;
import org.jcodec.codecs.mpeg12.MPEGDecoder;
import org.jcodec.codecs.mpeg4.MPEG4Decoder;
import org.jcodec.codecs.prores.ProresDecoder;
import org.jcodec.common.Codec;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.SeekableDemuxerTrack;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.VideoDecoder;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;
import org.jcodec.scale.AWTUtil;
import org.jcodec.containers.mp4.BoxUtil;
import org.jcodec.containers.mp4.boxes.Box.LeafBox;
import org.jcodec.containers.mp4.boxes.NodeBox;
import org.jcodec.containers.mp4.boxes.SampleEntry;
import org.jcodec.containers.mp4.demuxer.AbstractMP4DemuxerTrack;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class PictureVideoPlayer {
	static final int MAX_VIDEO_BYTES = 50 * 1024 * 1024;
	private static final int MAX_VIDEO_DIMENSION = 1920;
	private static final long MAX_VIDEO_PIXELS = 2_073_600L;
	private static final double MAX_VIDEO_DURATION_SECONDS = 300.0;
	private static final long MIN_FRAME_INTERVAL_NANOS = 33_333_333L;
	private static final int FRAME_BUFFER_COUNT = 3;

	private final byte[] data;
	private final Metadata metadata;
	private final ArrayBlockingQueue<NativeImage> frames = new ArrayBlockingQueue<>(2);
	private final ArrayBlockingQueue<NativeImage> reusableFrames = new ArrayBlockingQueue<>(FRAME_BUFFER_COUNT);
	private volatile boolean running;
	private volatile NativeImageBackedTexture texture;
	private NativeImage initialFrame;
	private Decoder decoder;
	private double initialTimestamp;

	private PictureVideoPlayer(byte[] data, Metadata metadata, Decoder decoder,
			NativeImage initialFrame, double initialTimestamp) {
		this.data = data;
		this.metadata = metadata;
		this.decoder = decoder;
		this.initialFrame = initialFrame;
		this.initialTimestamp = initialTimestamp;
	}

	static boolean isMp4(byte[] data) {
		return data.length >= 12 && data[4] == 'f' && data[5] == 't'
				&& data[6] == 'y' && data[7] == 'p';
	}

	static Metadata validate(byte[] data) throws IOException {
		if (!isMp4(data)) {
			throw new IOException("Only MP4 video files are supported");
		}
		if (data.length > MAX_VIDEO_BYTES) {
			throw new IOException("Video file exceeds the 50 MiB limit");
		}
		Decoder decoder = openDecoder(data);
		try {
			return readMetadata(decoder.grab);
		} catch (RuntimeException exception) {
			throw new IOException("Could not inspect MP4 video metadata", exception);
		} finally {
			closeDecoder(decoder);
		}
	}

	static PictureVideoPlayer open(byte[] data) throws IOException {
		if (!isMp4(data) || data.length > MAX_VIDEO_BYTES) {
			throw new IOException("Invalid or oversized MP4 video");
		}
		Decoder decoder = openDecoder(data);
		NativeImage initialFrame = null;
		try {
			Metadata metadata = readMetadata(decoder.grab);
			PictureWithMetadata first = decoder.grab.getNativeFrameWithMetadata();
			if (first == null) {
				throw new IOException("MP4 video contains no decodable frames");
			}
			initialFrame = toNativeImage(first.getPicture());
			if (initialFrame.getWidth() != metadata.width || initialFrame.getHeight() != metadata.height) {
				throw new IOException("Initial video frame dimensions do not match the video metadata");
			}
			validateDimensions(initialFrame.getWidth(), initialFrame.getHeight());
			return new PictureVideoPlayer(data, metadata, decoder, initialFrame, first.getTimestamp());
		} catch (IOException exception) {
			if (initialFrame != null) {
				initialFrame.close();
			}
			closeDecoder(decoder);
			throw exception;
		} catch (RuntimeException exception) {
			if (initialFrame != null) {
				initialFrame.close();
			}
			closeDecoder(decoder);
			throw new IOException("Could not decode the first MP4 frame", exception);
		}
	}

	private static Metadata readMetadata(FrameGrab grab) throws IOException {
		DemuxerTrack videoTrack = grab.getVideoTrack();
		DemuxerTrackMeta track = videoTrack.getMeta();
		if (!isSupportedCodec(videoTrack)) {
			throw new IOException("MP4 video uses an unsupported codec");
		}
		double duration = track.getTotalDuration();
		VideoCodecMeta codec = track.getVideoCodecMeta();
		Size size = codec == null ? null : codec.getSize();
		if (size == null || !Double.isFinite(duration) || duration <= 0
				|| duration > MAX_VIDEO_DURATION_SECONDS) {
			throw new IOException("Video duration or dimensions are not supported");
		}
		validateDimensions(size.getWidth(), size.getHeight());
		return new Metadata(size.getWidth(), size.getHeight(), duration);
	}

	private static void validateDimensions(int width, int height) throws IOException {
		if (width < 1 || height < 1 || width > MAX_VIDEO_DIMENSION || height > MAX_VIDEO_DIMENSION
				|| (long) width * height > MAX_VIDEO_PIXELS) {
			throw new IOException("Video dimensions exceed the supported 1920-pixel side or pixel limits");
		}
	}

	private static Decoder openDecoder(byte[] data) throws IOException {
		SeekableByteChannel channel = ByteBufferSeekableByteChannel.readFromByteBuffer(ByteBuffer.wrap(data));
		try {
			MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(channel);
			DemuxerTrack videoTrack = demuxer.getVideoTrack();
			if (!(videoTrack instanceof SeekableDemuxerTrack seekableTrack)) {
				throw new IOException("MP4 video track is missing or cannot be read");
			}
			ContainerAdaptor codecAdaptor = createCodecAdaptor(seekableTrack);
			return new Decoder(channel, new FrameGrab(seekableTrack, codecAdaptor));
		} catch (IOException | RuntimeException exception) {
			closeChannel(channel);
			throw new IOException("Could not read the MP4 video stream", exception);
		}
	}

	private static ContainerAdaptor createCodecAdaptor(SeekableDemuxerTrack videoTrack) throws IOException {
		Codec codec = getCodec(videoTrack);
		if (codec == Codec.H264) {
			return new AVCMP4Adaptor(videoTrack.getMeta());
		}
		if (codec == Codec.MPEG4) {
			VideoDecoder decoder = new MPEG4Decoder();
			ByteBuffer codecPrivate = getMpeg4CodecPrivate(videoTrack);
			return new GenericAdaptor(decoder) {
				private boolean firstFrame = true;

				@Override
				public Picture decodeFrame(Packet packet, byte[][] buffer) {
					ByteBuffer frameData = packet.getData();
					if (firstFrame) {
						firstFrame = false;
						if (codecPrivate != null && codecPrivate.hasRemaining()) {
							frameData = NIOUtils.combineBuffers(
									java.util.List.of(codecPrivate.duplicate(), frameData));
						}
					}
					return decoder.decodeFrame(frameData, buffer);
				}
			};
		}
		if (codec == Codec.MPEG2) {
			return new GenericAdaptor(new MPEGDecoder());
		}
		if (codec == Codec.PRORES) {
			return new GenericAdaptor(new ProresDecoder());
		}
		throw new IOException("Unsupported MP4 video codec: " + codec);
	}

	private static ByteBuffer getMpeg4CodecPrivate(SeekableDemuxerTrack videoTrack) {
		if (!(videoTrack instanceof AbstractMP4DemuxerTrack mp4Track)
				|| mp4Track.getSampleEntries().length == 0) {
			return null;
		}
		SampleEntry sampleEntry = mp4Track.getSampleEntries()[0];
		LeafBox esds = NodeBox.findFirst(sampleEntry, LeafBox.class, "esds");
		if (esds == null) {
			esds = NodeBox.findFirstPath(sampleEntry, LeafBox.class, new String[] { null, "esds" });
		}
		return esds == null ? null : BoxUtil.as(EsdsBox.class, esds).getStreamInfo();
	}

	private static Codec getCodec(DemuxerTrack videoTrack) {
		Codec codec = videoTrack.getMeta().getCodec();
		if (codec != null || !(videoTrack instanceof AbstractMP4DemuxerTrack mp4Track)
				|| mp4Track.getSampleEntries().length == 0) {
			return codec;
		}
		String fourcc = mp4Track.getSampleEntries()[0].getFourcc();
		codec = Codec.codecByFourcc(fourcc);
		if (codec != null) {
			return codec;
		}
		if ("mp4v".equals(fourcc)) {
			return Codec.MPEG4;
		}
		if ("mp2v".equals(fourcc)) {
			return Codec.MPEG2;
		}
		return null;
	}

	private static boolean isSupportedCodec(DemuxerTrack videoTrack) {
		Codec codec = getCodec(videoTrack);
		return codec == Codec.H264 || codec == Codec.MPEG4 || codec == Codec.MPEG2 || codec == Codec.PRORES;
	}

	private static NativeImage toNativeImage(Picture picture) throws IOException {
		BufferedImage buffered = AWTUtil.toBufferedImage(picture);
		NativeImage nativeImage = null;
		try {
			nativeImage = new NativeImage(buffered.getWidth(), buffered.getHeight(), false);
			copyPixels(buffered, nativeImage);
			return nativeImage;
		} catch (IOException | RuntimeException exception) {
			if (nativeImage != null) {
				nativeImage.close();
			}
			throw exception;
		} finally {
			buffered.flush();
		}
	}

	private static void copyPixels(BufferedImage buffered, NativeImage nativeImage) throws IOException {
		if (buffered.getWidth() != nativeImage.getWidth() || buffered.getHeight() != nativeImage.getHeight()) {
			throw new IOException("Video frame dimensions changed during playback");
		}
		if (!(buffered.getRaster().getDataBuffer() instanceof DataBufferByte dataBuffer)) {
			throw new IOException("Unsupported decoded video frame format");
		}
		int width = buffered.getWidth();
		int height = buffered.getHeight();
		byte[] pixels = dataBuffer.getData();
		if (pixels.length < (long) width * height * 3) {
			throw new IOException("Decoded video frame is incomplete");
		}
		for (int y = 0, offset = 0; y < height; y++) {
			for (int x = 0; x < width; x++, offset += 3) {
				int blue = pixels[offset] & 0xFF;
				int green = pixels[offset + 1] & 0xFF;
				int red = pixels[offset + 2] & 0xFF;
				nativeImage.setColor(x, y, 0xFF000000 | (blue << 16) | (green << 8) | red);
			}
		}
	}

	private static void closeDecoder(Decoder decoder) {
		closeChannel(decoder.channel);
	}

	private static void closeChannel(SeekableByteChannel channel) {
		try {
			channel.close();
		} catch (IOException exception) {
			PictureWallMod.LOGGER.debug("Could not close a video decoder channel", exception);
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

	long reservedMemoryBytes() {
		return (long) metadata.width * metadata.height * 4 * (FRAME_BUFFER_COUNT + 1L);
	}

	void prepareFrameBuffers() {
		int allocated = 0;
		try {
			while (allocated < FRAME_BUFFER_COUNT) {
				reusableFrames.add(new NativeImage(metadata.width, metadata.height, false));
				allocated++;
			}
		} catch (RuntimeException exception) {
			drainReusableFrames();
			throw exception;
		}
	}

	void start(Runnable onStopped) {
		running = true;
		Thread.startVirtualThread(() -> {
			try {
				play();
			} catch (IOException | RuntimeException exception) {
				if (running) {
					PictureWallMod.LOGGER.warn("MP4 playback stopped", exception);
				}
			} finally {
				running = false;
				closeDecoder(decoder);
				drainFrames();
				onStopped.run();
			}
		});
	}

	private void play() throws IOException {
		Decoder currentDecoder = decoder;
		double loopStartTimestamp = initialTimestamp;
		long loopStartNanos = System.nanoTime();
		long lastFrameDeadline = loopStartNanos;
		PictureWithMetadata frame = currentDecoder.grab.getNativeFrameWithMetadata();

		while (running) {
			if (frame == null) {
				long loopEnd = loopStartNanos + (long) (metadata.duration * 1_000_000_000L);
				waitUntil(loopEnd);
				closeDecoder(currentDecoder);
				currentDecoder = openDecoder(data);
				decoder = currentDecoder;
				frame = currentDecoder.grab.getNativeFrameWithMetadata();
				if (frame == null) {
					throw new IOException("MP4 video contains no decodable frames");
				}
				loopStartTimestamp = frame.getTimestamp();
				loopStartNanos = System.nanoTime();
				lastFrameDeadline = loopStartNanos;
			}

			long deadline = loopStartNanos
					+ Math.max(0L, (long) ((frame.getTimestamp() - loopStartTimestamp) * 1_000_000_000L));
			if (deadline - lastFrameDeadline < MIN_FRAME_INTERVAL_NANOS) {
				frame = currentDecoder.grab.getNativeFrameWithMetadata();
				continue;
			}
			waitUntil(deadline);
			if (!running) {
				break;
			}
			NativeImage decoded = takeReusableFrame();
			if (decoded == null) {
				break;
			}
			try {
				BufferedImage buffered = AWTUtil.toBufferedImage(frame.getPicture());
				try {
					copyPixels(buffered, decoded);
				} finally {
					buffered.flush();
				}
			} catch (IOException | RuntimeException exception) {
				releaseFrame(decoded);
				throw exception;
			}
			publishFrame(decoded);
			lastFrameDeadline = deadline;
			frame = currentDecoder.grab.getNativeFrameWithMetadata();
		}
	}

	private NativeImage takeReusableFrame() {
		while (running) {
			try {
				NativeImage frame = reusableFrames.poll(100, TimeUnit.MILLISECONDS);
				if (frame != null) {
					return frame;
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				running = false;
			}
		}
		return null;
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

	private synchronized void publishFrame(NativeImage image) {
		if (!running) {
			image.close();
			return;
		}
		if (frames.offer(image)) {
			return;
		}
		NativeImage discarded = frames.poll();
		if (discarded != null) {
			recycleFrame(discarded);
		}
		if (!frames.offer(image)) {
			recycleFrame(image);
		}
	}

	synchronized NativeImage pollLatestFrame() {
		NativeImage latest = frames.poll();
		if (latest == null) {
			return null;
		}
		NativeImage next;
		while ((next = frames.poll()) != null) {
			recycleFrame(latest);
			latest = next;
		}
		return latest;
	}

	synchronized void releaseFrame(NativeImage frame) {
		recycleFrame(frame);
	}

	private void recycleFrame(NativeImage frame) {
		if (!running || !reusableFrames.offer(frame)) {
			frame.close();
		}
	}

	void close() {
		running = false;
		if (initialFrame != null) {
			initialFrame.close();
			initialFrame = null;
		}
		drainFrames();
	}

	private synchronized void drainFrames() {
		NativeImage frame;
		while ((frame = frames.poll()) != null) {
			frame.close();
		}
		drainReusableFrames();
	}

	private void drainReusableFrames() {
		NativeImage frame;
		while ((frame = reusableFrames.poll()) != null) {
			frame.close();
		}
	}

	record Metadata(int width, int height, double duration) {
	}

	private record Decoder(SeekableByteChannel channel, FrameGrab grab) {
	}
}
