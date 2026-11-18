// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mgbcg;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Stops every executor Baritone owns, so its non-daemon worker threads cannot keep the JVM alive
 * after the player quit.
 *
 * <p>Baritone is a hard dependency ({@code depends: baritone} in {@code fabric.mod.json}), so the
 * API types are imported directly: when Baritone is missing Fabric Loader refuses to load this mod
 * instead of letting it fail silently at runtime. The executors themselves are not part of the API
 * (Baritone's implementation classes are obfuscated), so {@link ExecutorScan} finds them
 * reflectively from the live {@link IBaritone} instances.</p>
 *
 * <p>This class does not end the JVM and does not touch threads it does not own. Once Baritone's
 * pools are terminated there is nothing of Baritone left to keep the process alive; if some other
 * mod still leaks a non-daemon thread, vanilla's post-main watchdog reports it truthfully and this
 * mod stays out of the way.</p>
 */
public final class BaritonePools {
	private static final Logger LOG = LogManager.getLogger("mgbaritonecrashguard");

	/** How long to wait for Baritone's threads to disappear after the shutdown request. */
	private static final long TERMINATION_TIMEOUT_MS = 3000L;

	/** Class-name prefix that identifies Baritone's own objects and executor fields. */
	private static final String BARITONE_PACKAGE = "baritone.";

	private BaritonePools() {
	}

	/**
	 * Shuts down all Baritone executors. Idempotent enough to be called once at client exit; never
	 * throws.
	 */
	public static void shutdownAll() {
		List<IBaritone> baritones;
		try {
			IBaritoneProvider provider = BaritoneAPI.getProvider();
			baritones = provider == null ? null : provider.getAllBaritones();
		} catch (Throwable t) {
			LOG.warn("could not reach Baritone's API, leaving its threads alone", t);
			return;
		}

		if (baritones == null || baritones.isEmpty()) {
			LOG.info("Baritone has no live instance, so it owns no executor to stop");
			return;
		}

		List<ExecutorService> pools = ExecutorScan.scan(baritones, BARITONE_PACKAGE);
		if (pools.isEmpty()) {
			LOG.warn("no Baritone executor found - its threads, if any, are left alone"
					+ " (this can happen when a future Baritone version changes its internals)");
			return;
		}

		int stillRunning = ExecutorScan.stopAll(pools, TERMINATION_TIMEOUT_MS);
		if (stillRunning == 0) {
			LOG.info("stopped {} Baritone executor(s), so no Baritone thread can keep the JVM alive",
					pools.size());
		} else {
			LOG.warn("{} of {} Baritone executor(s) did not terminate within {} ms; the post-main"
							+ " watchdog may still report them",
					stillRunning, pools.size(), TERMINATION_TIMEOUT_MS);
		}
	}
}
