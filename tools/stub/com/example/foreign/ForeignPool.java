// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.foreign;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only executor that belongs to no Baritone package. The scanner must leave it and its
 * non-daemon worker alone.
 */
public final class ForeignPool {
	private final AtomicReference<Thread> worker = new AtomicReference<>();

	public final ThreadPoolExecutor pool = new ThreadPoolExecutor(
			1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new ThreadFactory() {
				@Override
				public Thread newThread(Runnable runnable) {
					Thread thread = new Thread(runnable, "foreign-worker");
					thread.setDaemon(false);
					worker.set(thread);
					return thread;
				}
			});

	/** Submits a task that blocks until interrupted, so the worker thread exists and is visible. */
	public Thread startWorker() {
		pool.execute(() -> {
			try {
				Thread.sleep(600_000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		return worker.get();
	}
}
