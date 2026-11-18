// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

import baritone.stub.StubProcess;
import baritone.stub.StubRoot;
import com.example.foreign.ForeignPool;
import com.example.mgbcg.ExecutorScan;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Standalone harness for {@link ExecutorScan}. It needs neither Minecraft nor Baritone: the stub
 * classes under {@code tools/stub/baritone/stub} imitate the shapes the scanner has to find.
 *
 * <pre>
 * javac --release 25 -encoding UTF-8 -d build/verify \
 *   src/main/java/com/example/mgbcg/ExecutorScan.java \
 *   tools/VerifyScan.java \
 *   tools/stub/baritone/stub/*.java tools/stub/com/example/foreign/*.java
 * java -cp build/verify VerifyScan        # exits 0 only when every check passes
 * </pre>
 *
 * <p>Checks: the shared static pool, an instance-field pool and a pool reached through a plain
 * {@code List} are found; a non-Baritone pool in the same object graph is not; stopping the found
 * pools really terminates their threads while the foreign non-daemon thread keeps running.</p>
 */
public final class VerifyScan {
	public static void main(String[] args) throws Exception {
		StubRoot root = new StubRoot();
		StubProcess process = (StubProcess) root.processes.get(0);
		ForeignPool foreign = root.foreign;

		Thread foreignWorker = foreign.startWorker();
		startWorker(StubRoot.SHARED_POOL);
		startWorker(root.nested.pool);
		startWorker(process.pool);
		awaitWorker(StubRoot.SHARED_POOL);
		check(foreignWorker.isAlive(), "the foreign worker thread should be running");

		List<ExecutorService> pools = ExecutorScan.scan(List.of(root), "baritone.");
		check(pools.size() == 3, "expected 3 Baritone-shaped pools, got " + pools.size());
		check(containsSame(pools, StubRoot.SHARED_POOL), "the static shared pool was not found");
		check(containsSame(pools, root.nested.pool), "the instance-field pool was not found");
		check(containsSame(pools, process.pool), "the pool behind a List was not found");
		check(!containsSame(pools, foreign.pool), "the foreign pool must not be collected");

		int stillRunning = ExecutorScan.stopAll(pools, 3000L);
		check(stillRunning == 0, "expected all pools terminated, " + stillRunning + " still running");
		for (ExecutorService pool : pools) {
			check(pool.isTerminated(), "a collected pool did not terminate");
		}
		check(!foreign.pool.isTerminated(), "the foreign pool must not be shut down");
		check(foreignWorker.isAlive(), "the foreign worker thread must still be alive");

		foreign.pool.shutdownNow();
		foreign.pool.awaitTermination(3, TimeUnit.SECONDS);
		check(!foreignWorker.isAlive(), "the foreign worker should end after its own shutdown");

		System.out.println("VerifyScan: all checks passed");
	}

	private static void startWorker(ExecutorService pool) {
		pool.execute(() -> {
			try {
				Thread.sleep(600_000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	private static void awaitWorker(ThreadPoolExecutor pool) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (pool.getPoolSize() == 0) {
			check(System.nanoTime() < deadline, "the shared pool never created a worker thread");
			Thread.sleep(10L);
		}
	}

	private static boolean containsSame(List<ExecutorService> pools, ExecutorService expected) {
		for (ExecutorService pool : pools) {
			if (pool == expected) {
				return true;
			}
		}
		return false;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new IllegalStateException(message);
		}
	}
}
