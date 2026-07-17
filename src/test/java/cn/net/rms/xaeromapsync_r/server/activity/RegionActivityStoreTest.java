package cn.net.rms.xaeromapsync_r.server.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class RegionActivityStoreTest {
	@Test
	void pauseResumeAndManualControlsHaveDefinedBehavior() {
		RegionActivityStore store = store();
		RegionKey key = RegionKey.fromChunk("minecraft:overworld", 0, 0);

		store.pause();
		store.recordTick(key, 5, 1);
		assertThrows(IllegalArgumentException.class, () -> store.recordTick(key, -1, 0));
		assertTrue(store.get(key).isEmpty());
		assertTrue(store.statistics().paused());

		store.markStorm(key);
		assertEquals(RegionActivityState.STORM, store.get(key).orElseThrow().state());

		store.resume();
		store.recordTick(key, 0, 0);
		assertFalse(store.isPaused());
		assertEquals(1, store.get(key).orElseThrow().quietTicks());

		assertTrue(store.clear(key));
		assertFalse(store.clear(key));
		assertEquals(0, store.statistics().total());
	}

	@Test
	void statisticsCountEveryStateAndClearAll() {
		RegionActivityStore store = store();
		RegionKey quiet = key(0);
		RegionKey active = key(1);
		RegionKey storm = key(2);
		RegionKey cooldown = key(3);
		RegionKey stable = key(4);

		store.recordTick(quiet, 0, 0);
		store.recordTick(active, 1, 0);
		store.recordTick(storm, 10, 0);
		store.recordTick(cooldown, 10, 0);
		store.recordTick(cooldown, 0, 0);
		store.recordTick(cooldown, 0, 0);
		store.recordTick(stable, 1, 0);
		store.recordTick(stable, 0, 0);
		store.recordTick(stable, 0, 0);
		store.recordTick(stable, 0, 0);

		RegionActivityStore.Statistics statistics = store.statistics();
		assertEquals(5, statistics.total());
		assertEquals(1, statistics.quiet());
		assertEquals(1, statistics.active());
		assertEquals(1, statistics.storm());
		assertEquals(1, statistics.cooldown());
		assertEquals(1, statistics.stable());
		assertEquals(5, store.clear());
		assertEquals(0, store.clear());
	}

	@Test
	void concurrentUpdatesDoNotLoseCounts() throws Exception {
		RegionActivityStore store = new RegionActivityStore(Integer.MAX_VALUE, Integer.MAX_VALUE, 2, 3);
		RegionKey key = key(0);
		int workers = 8;
		int updatesPerWorker = 2_000;
		ExecutorService executor = Executors.newFixedThreadPool(workers);
		CountDownLatch start = new CountDownLatch(1);
		List<Throwable> failures = new ArrayList<>();

		for (int worker = 0; worker < workers; worker++) {
			executor.submit(() -> {
				try {
					start.await();
					for (int update = 0; update < updatesPerWorker; update++) {
						store.recordTick(key, 1, 1);
					}
				} catch (Throwable failure) {
					synchronized (failures) {
						failures.add(failure);
					}
				}
			});
		}
		start.countDown();
		executor.shutdown();
		assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

		assertTrue(failures.isEmpty());
		RegionActivityRecord record = store.get(key).orElseThrow();
		assertEquals(workers * updatesPerWorker, record.totalBlockChanges());
		assertEquals(workers * updatesPerWorker, record.totalDirtyChunks());
		assertEquals(1, store.statistics().total());
	}

	@Test
	void specializedSampleUsesTheSameStormStateMachine() {
		RegionActivityStore store = new RegionActivityStore(
				new RegionActivityThresholds(10, 3, 4, 2, 5, 6, 2, 3));
		RegionKey key = key(0);

		store.recordTick(key, new RegionActivitySample(0, 0, 0, 2, 0, 0));

		RegionActivityRecord record = store.get(key).orElseThrow();
		assertEquals(RegionActivityState.STORM, record.state());
		assertEquals(2, record.totalExplosions());
	}

	private static RegionActivityStore store() {
		return new RegionActivityStore(10, 3, 2, 3);
	}

	private static RegionKey key(int regionX) {
		return new RegionKey("minecraft:overworld", regionX, 0);
	}
}
