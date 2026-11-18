// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package baritone.stub;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Test-only: a Baritone-shaped object with its own single-thread executor. */
public final class StubNested {
	public final ExecutorService pool = Executors.newSingleThreadExecutor();
}
