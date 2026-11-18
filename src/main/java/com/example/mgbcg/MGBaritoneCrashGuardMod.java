// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mgbcg;

import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Client entrypoint: arms the exit guard.
 *
 * <p>Baritone is a hard dependency declared in {@code fabric.mod.json}, so there is no
 * "is Baritone installed" check here and nothing to do at startup besides installing the watcher.
 * The mod owns no thread of its own except the daemon watcher, and no mixins, so it cannot affect
 * the running game.</p>
 */
public class MGBaritoneCrashGuardMod implements ClientModInitializer {
	private static final Logger LOG = LogManager.getLogger("mgbaritonecrashguard");

	@Override
	public void onInitializeClient() {
		ClientExitGuard.arm();
		LOG.info("MGBaritoneCrashGuard armed: Baritone's executors will be stopped when the client exits");
	}
}
