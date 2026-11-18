// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mgbcg;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Waits for the Minecraft client thread to end and then stops Baritone's executors.
 *
 * <p>Why the client thread is the trigger: when the player quits, the render thread returns from
 * {@code Minecraft#run()}, {@code Minecraft} saves and closes the world, and {@code Main} starts the
 * post-main shutdown watchdog. The watchdog waits 15 seconds for the JVM to end; if it has not -
 * which is exactly what Baritone's non-daemon worker threads cause - it writes a
 * {@code Client shutdown from post-main} crash report and calls {@code System.exit(-8)}.</p>
 *
 * <p>The watcher is a daemon thread, so it can never be the reason the JVM stays alive. It does not
 * call {@code System.exit} and does not halt the JVM: after {@link BaritonePools#shutdownAll()} the
 * JVM ends on its own, with exit code 0, as soon as the remaining non-daemon threads are gone. If
 * another mod still keeps the process alive, the watchdog's report about <em>that</em> thread is a
 * true positive and is left alone.</p>
 */
public final class ClientExitGuard {
	private static final Logger LOG = LogManager.getLogger("mgbaritonecrashguard");

	/** How often to look for the running client while the game is still constructing itself. */
	private static final long POLL_MS = 100L;

	private ClientExitGuard() {
	}

	/**
	 * Arms the daemon watcher. Called once from the client entrypoint, which runs inside
	 * {@code Minecraft}'s constructor, so the client thread is resolved lazily.
	 */
	public static void arm() {
		Thread watcher = new Thread(() -> {
			try {
				Thread client = awaitClientThread();
				LOG.debug("watching client thread " + client.getName() + " for exit");
				client.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}

			LOG.info("client exited, stopping Baritone's executors");
			try {
				BaritonePools.shutdownAll();
			} catch (Throwable t) {
				LOG.error("failed to stop Baritone's executors; its threads may keep the JVM alive", t);
			}
		}, "mgbaritonecrashguard-exit");

		// A daemon thread: it must never be the reason the JVM stays alive itself.
		watcher.setDaemon(true);
		watcher.start();
	}

	/**
	 * @return the render thread, i.e. the thread running {@code Minecraft#run()}. Resolved lazily
	 *         because this runs before/while {@code Minecraft} is constructed.
	 */
	private static Thread awaitClientThread() throws InterruptedException {
		while (true) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft != null) {
				Thread client = minecraft.getRunningThread();
				if (client != null) {
					return client;
				}
			}
			Thread.sleep(POLL_MS);
		}
	}
}
