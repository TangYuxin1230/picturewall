package com.rong.picturewall;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class PictureWallThreads {
	private static final AtomicInteger THREAD_ID = new AtomicInteger();
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
			Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())),
			task -> {
				Thread thread = new Thread(task, "picturewall-worker-" + THREAD_ID.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			}
	);

	private PictureWallThreads() {
	}

	static void execute(Runnable task) {
		EXECUTOR.execute(task);
	}
}
