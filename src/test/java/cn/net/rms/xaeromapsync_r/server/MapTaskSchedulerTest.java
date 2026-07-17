package cn.net.rms.xaeromapsync_r.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStore;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStoreTest;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexStore;
import org.junit.jupiter.api.Test;

final class MapTaskSchedulerTest {
	private static final long MILLIS = 1_000_000L;

	@Test
	void persistenceFailureDoesNotPublishIndexEntry() {
		MapTile tile = MapTileDataStoreTest.tile("minecraft:overworld", 3, -8, 10);
		MapTileDataStore tileData = new MapTileDataStore();
		MapTileIndexStore index = new MapTileIndexStore();

		assertFalse(MapTaskScheduler.persistThenIndex(tile, tileData, index));
		assertEquals(0, index.totalCount());
	}

	@Test
	void configuredRenderCountIsCappedByMaximum() {
		assertEquals(4, MapTaskScheduler.renderLimit(4, 16));
		assertEquals(16, MapTaskScheduler.renderLimit(32, 16));
		assertEquals(0, MapTaskScheduler.renderLimit(-1, 16));
	}

	@Test
	void automaticRenderingRequiresExplicitOptIn() {
		assertFalse(MapTaskScheduler.shouldRunAutomaticRendering(false, false, false, 0.0D, 45));
		assertFalse(MapTaskScheduler.shouldRunAutomaticRendering(true, true, false, 0.0D, 45));
		assertFalse(MapTaskScheduler.shouldRunAutomaticRendering(true, false, true, 45.0D, 45));
		org.junit.jupiter.api.Assertions.assertTrue(
				MapTaskScheduler.shouldRunAutomaticRendering(true, false, true, 44.9D, 45));
	}

	@Test
	void adaptiveBudgetUsesCurrentAndPreviousTickHeadroom() {
		assertEquals(15 * MILLIS, MapTaskScheduler.adaptiveMapWorkBudgetNanos(
				30 * MILLIS, 45 * MILLIS, 15 * MILLIS, 45 * MILLIS, 25 * MILLIS));
		assertEquals(1 * MILLIS, MapTaskScheduler.adaptiveMapWorkBudgetNanos(
				44 * MILLIS, 35 * MILLIS, 5 * MILLIS, 45 * MILLIS, 25 * MILLIS));
		assertEquals(0L, MapTaskScheduler.adaptiveMapWorkBudgetNanos(
				20 * MILLIS, 60 * MILLIS, 10 * MILLIS, 45 * MILLIS, 25 * MILLIS));
	}

	@Test
	void firstTickBudgetUsesAOneMillisecondSoftStart() {
		assertEquals(1 * MILLIS, MapTaskScheduler.adaptiveMapWorkBudgetNanos(
				20 * MILLIS, 0L, 0L, 45 * MILLIS, 10 * MILLIS));
		assertEquals(1 * MILLIS, MapTaskScheduler.adaptiveMapWorkBudgetNanos(
				40 * MILLIS, 0L, 0L, 45 * MILLIS, 25 * MILLIS));
		assertEquals(500_000L, MapTaskScheduler.adaptiveMapWorkBudgetNanos(
				20 * MILLIS, 0L, 0L, 45 * MILLIS, 500_000L));
	}

}
