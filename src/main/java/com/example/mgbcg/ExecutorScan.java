// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mgbcg;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Finds the {@link ExecutorService}s owned by one library (Baritone) and stops them.
 *
 * <p>Baritone keeps its worker pools in implementation classes whose names are obfuscated
 * ({@code baritone.a} holds the shared static {@code ThreadPoolExecutor}; the per-world elytra
 * classes hold their own single-thread executors), and no public API exposes them. Reflection is
 * therefore the only way to reach them, and it is kept deliberately narrow:</p>
 *
 * <ul>
 *   <li>only objects whose runtime class name starts with {@code packagePrefix} are followed, so
 *       the walk never enters Minecraft objects, strings or boxed values;</li>
 *   <li>the static fields of every visited class are checked for {@link ExecutorService} - that is
 *       where Baritone's shared pool lives;</li>
 *   <li>instance fields are followed and, because Baritone registers its processes in plain
 *       {@code List}s, one level of {@link Iterable} is walked as well. Maps, arrays and the
 *       contents of the executors themselves are deliberately not entered;</li>
 *   <li>the walk is bounded ({@value #MAX_OBJECTS} objects, {@value #MAX_DEPTH} levels,
 *       {@value #MAX_ELEMENTS} elements per container, {@value #MAX_POOLS} pools);</li>
 *   <li>every reflective failure is ignored, so a future Baritone version that changes its shape
 *       degrades to "pool not found" instead of throwing.</li>
 * </ul>
 *
 * <p>Deliberately free of any Minecraft, Baritone or logging type, so {@code tools/VerifyScan.java}
 * can exercise it in a plain JVM against stub objects in a fake {@code baritone.} package.</p>
 *
 * <p>Why {@code shutdownNow()} instead of {@code shutdown()}: one of Baritone's pooled tasks
 * ({@code baritone.o.b}) sleeps for 30 seconds between cache saves. A plain {@code shutdown()}
 * does not interrupt a worker that is executing a task, so it would still be alive 15 seconds
 * later, when Minecraft's post-main watchdog fires. {@code shutdownNow()} interrupts it;
 * Baritone's tasks handle {@link InterruptedException} by returning, which is exactly what is
 * needed. It is called only after the client thread has ended, when the world has already been
 * saved and nothing of the game is left to do.</p>
 */
public final class ExecutorScan {
	/** Upper bound on visited objects; the Baritone object graph is small, this is just a stop. */
	private static final int MAX_OBJECTS = 4096;

	/** Upper bound on how deep the walk follows instance fields and containers. */
	private static final int MAX_DEPTH = 6;

	/** Upper bound on elements read from one {@link Iterable}. */
	private static final int MAX_ELEMENTS = 128;

	/** Upper bound on collected pools. */
	private static final int MAX_POOLS = 256;

	private ExecutorScan() {
	}

	/**
	 * Collects the executor services reachable from {@code roots} through objects of the given
	 * package.
	 *
	 * @param roots         starting objects (the library's own instances)
	 * @param packagePrefix package prefix of the classes to follow, e.g. {@code "baritone."}
	 * @return distinct pools in discovery order, never {@code null}
	 */
	public static List<ExecutorService> scan(Iterable<?> roots, String packagePrefix) {
		List<ExecutorService> pools = new ArrayList<>();
		Set<ExecutorService> knownPools = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<Node> agenda = new ArrayDeque<>();

		for (Object root : roots) {
			if (root != null) {
				agenda.add(new Node(root, 0));
			}
		}

		while (!agenda.isEmpty() && seen.size() < MAX_OBJECTS && pools.size() < MAX_POOLS) {
			Node node = agenda.poll();
			Object value = node.value;

			if (!seen.add(value)) {
				continue;
			}
			if (value instanceof ExecutorService pool) {
				addPool(pool, pools, knownPools);
				continue;
			}

			Class<?> type = value.getClass();
			if (!type.getName().startsWith(packagePrefix)) {
				continue;
			}

			collectStaticPools(type, packagePrefix, pools, knownPools);

			for (Class<?> current = type;
					current != null && current != Object.class && current.getName().startsWith(packagePrefix);
					current = current.getSuperclass()) {
				for (Field field : current.getDeclaredFields()) {
					if (Modifier.isStatic(field.getModifiers())) {
						continue;
					}

					Object fieldValue = read(field, value);
					if (fieldValue == null) {
						continue;
					}
					if (fieldValue instanceof ExecutorService pool) {
						addPool(pool, pools, knownPools);
						continue;
					}
					if (node.depth >= MAX_DEPTH) {
						continue;
					}
					if (fieldValue.getClass().getName().startsWith(packagePrefix)) {
						agenda.add(new Node(fieldValue, node.depth + 1));
					} else if (fieldValue instanceof Iterable<?> iterable) {
						enqueueElements(iterable, node.depth + 1, packagePrefix, agenda);
					}
				}
			}
		}

		return pools;
	}

	/**
	 * Interrupts and shuts down every pool, then waits for them to terminate.
	 *
	 * @return how many pools were still not terminated when the timeout ran out (0 on success)
	 */
	public static int stopAll(List<ExecutorService> pools, long timeoutMs) {
		for (ExecutorService pool : pools) {
			try {
				pool.shutdownNow();
			} catch (Throwable ignored) {
				// A pool that refuses to shut down is reported by the isTerminated check below.
			}
		}

		long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
		for (ExecutorService pool : pools) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0L) {
				break;
			}
			try {
				pool.awaitTermination(remaining, TimeUnit.NANOSECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Throwable ignored) {
				// Treated as "not terminated" below.
			}
		}

		int remaining = 0;
		for (ExecutorService pool : pools) {
			if (!pool.isTerminated()) {
				remaining++;
			}
		}
		return remaining;
	}

	private static void collectStaticPools(Class<?> type, String packagePrefix,
			List<ExecutorService> pools, Set<ExecutorService> knownPools) {
		for (Class<?> current = type;
				current != null && current != Object.class && current.getName().startsWith(packagePrefix);
				current = current.getSuperclass()) {
			for (Field field : current.getDeclaredFields()) {
				if (!Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				if (!ExecutorService.class.isAssignableFrom(field.getType())) {
					continue;
				}
				Object value = read(field, null);
				if (value instanceof ExecutorService pool) {
					addPool(pool, pools, knownPools);
				}
			}
		}
	}

	private static void enqueueElements(Iterable<?> iterable, int depth, String packagePrefix, Deque<Node> agenda) {
		int count = 0;
		try {
			for (Object element : iterable) {
				if (++count > MAX_ELEMENTS) {
					break;
				}
				if (element != null && element.getClass().getName().startsWith(packagePrefix)) {
					agenda.add(new Node(element, depth));
				}
			}
		} catch (Throwable ignored) {
			// A concurrently modified container only costs us the objects we did not see.
		}
	}

	private static void addPool(ExecutorService pool, List<ExecutorService> pools, Set<ExecutorService> knownPools) {
		if (pools.size() < MAX_POOLS && knownPools.add(pool)) {
			pools.add(pool);
		}
	}

	private static Object read(Field field, Object owner) {
		try {
			field.setAccessible(true);
			return field.get(owner);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private record Node(Object value, int depth) {
	}
}
