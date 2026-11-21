package com.rong.picturewall;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class FfmpegHardwareDecoder implements AutoCloseable {
	private static final int MAX_ERROR_BYTES = 8192;

	private final Process process;
	private final Path inputFile;
	private final ReadableByteChannel videoOutput;
	private final ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
	private final AtomicReference<IOException> pipeError = new AtomicReference<>();
	private final Thread errorThread;
	private volatile boolean closed;

	private FfmpegHardwareDecoder(Process process, Path inputFile) {
		this.process = process;
		this.inputFile = inputFile;
		videoOutput = Channels.newChannel(process.getInputStream());
		errorThread = new Thread(this::readErrors, "picturewall-ffmpeg-errors");
		errorThread.setDaemon(true);
		errorThread.start();
	}

	static FfmpegHardwareDecoder open(byte[] data, PictureVideoPlayer.Metadata metadata) throws IOException {
		Path inputFile = Files.createTempFile("picturewall-video-", ".mp4");
		boolean started = false;
		try {
			Files.write(inputFile, data);
			FfmpegHardwareDecoder decoder = startProcess(inputFile, metadata);
			started = true;
			return decoder;
		} catch (IOException exception) {
			throw new IOException("Could not start FFmpeg hardware video decoding", exception);
		} finally {
			if (!started) {
				try {
					Files.deleteIfExists(inputFile);
				} catch (IOException exception) {
					PictureWallMod.LOGGER.debug("Could not delete the temporary MP4 file", exception);
				}
			}
		}
	}

	private static FfmpegHardwareDecoder startProcess(Path inputFile,
			PictureVideoPlayer.Metadata metadata) throws IOException {
		String executable = System.getenv("PICTUREWALL_FFMPEG");
		if (executable == null || executable.isBlank()) {
			executable = "ffmpeg";
		}
		List<String> command = new ArrayList<>();
		command.add(executable);
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("error");
		command.add("-hwaccel");
		command.add(hardwareAcceleration());
		command.add("-noautorotate");
		command.add("-stream_loop");
		command.add("-1");
		command.add("-i");
		command.add(inputFile.toString());
		command.add("-map");
		command.add("0:v:0");
		command.add("-an");
		command.add("-sn");
		command.add("-dn");
		command.add("-vf");
		command.add("fps=30,scale=" + metadata.width() + ":" + metadata.height());
		command.add("-pix_fmt");
		command.add("bgr24");
		command.add("-f");
		command.add("rawvideo");
		command.add("pipe:1");
		return new FfmpegHardwareDecoder(new ProcessBuilder(command).start(), inputFile);
	}

	private static String hardwareAcceleration() {
		String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (operatingSystem.contains("win")) {
			return "d3d11va";
		}
		if (operatingSystem.contains("mac")) {
			return "videotoolbox";
		}
		if (operatingSystem.contains("linux")) {
			return "vaapi";
		}
		return "auto";
	}

	private void readErrors() {
		try (InputStream errors = process.getErrorStream()) {
			byte[] buffer = new byte[1024];
			int count;
			while ((count = errors.read(buffer)) != -1) {
				synchronized (errorOutput) {
					int remaining = MAX_ERROR_BYTES - errorOutput.size();
					if (remaining > 0) {
						errorOutput.write(buffer, 0, Math.min(count, remaining));
					}
				}
			}
		} catch (IOException exception) {
			if (!closed) {
				pipeError.compareAndSet(null, exception);
			}
		}
	}

	boolean readFrame(ByteBuffer frame) throws IOException {
		frame.clear();
		while (frame.hasRemaining()) {
			int count = videoOutput.read(frame);
			if (count < 0) {
				if (frame.position() == 0) {
					return false;
				}
				throw new IOException("FFmpeg ended in the middle of a decoded frame");
			}
			if (count == 0) {
				Thread.yield();
			}
		}
		frame.flip();
		return true;
	}

	void verifyExit() throws IOException {
		try {
			int exitCode = process.waitFor();
			errorThread.join(TimeUnit.SECONDS.toMillis(1));
			if (errorThread.isAlive()) {
				throw new IOException("FFmpeg error output did not close");
			}
			if (exitCode != 0) {
				throw new IOException("FFmpeg hardware decoder exited with code " + exitCode + ": "
						+ errorMessage(), pipeError.get());
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for FFmpeg hardware decoding", exception);
		}
	}

	private String errorMessage() {
		synchronized (errorOutput) {
			return errorOutput.toString(StandardCharsets.UTF_8).trim();
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			videoOutput.close();
		} catch (IOException exception) {
			PictureWallMod.LOGGER.debug("Could not close the FFmpeg video output", exception);
		}
		process.destroy();
		try {
			if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
		try {
			Files.deleteIfExists(inputFile);
		} catch (IOException exception) {
			PictureWallMod.LOGGER.warn("Could not delete a temporary MP4 file", exception);
		}
	}
}
