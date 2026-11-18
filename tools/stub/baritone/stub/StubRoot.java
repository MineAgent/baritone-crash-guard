// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package baritone.stub;

import com.example.foreign.ForeignPool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Test-only shape that imitates Baritone's implementation classes: a static executor (the shared
 * worker pool in {@code baritone.a}), an instance field pointing at another executor, and a plain
 * list carrying a process object. It is compiled only by {@code tools/VerifyScan.java}, never
 * shipped in the jar.
 */
public final class StubRoot {
	/** Same placement as Baritone's shared {@code ThreadPoolExecutor}. */
	public static final ThreadPoolExecutor SHARED_POOL = new ThreadPoolExecutor(
			2, 4, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

	public final StubNested nested = new StubNested();
	public final List<Object> processes = new ArrayList<>();

	/** A non-Baritone object: the scanner must not follow it or collect its executor. */
	public final ForeignPool foreign = new ForeignPool();

	public StubRoot() {
		processes.add(new StubProcess());
		processes.add("not a baritone object");
	}
}
