// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package baritone.stub;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Test-only: a process object only reachable through a plain {@code List}, like Baritone's. */
public final class StubProcess {
	public final ExecutorService pool = Executors.newSingleThreadExecutor();
}
