package cn.net.rms.xaeromapsync_r.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStore;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStoreTest;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexStore;
import org.junit.jupiter.api.Test;

final class MapTaskSchedulerTest {
	@Test
	void persistenceFailureDoesNotPublishIndexEntry() {
		MapTile tile = MapTileDataStoreTest.tile("minecraft:overworld", 3, -8, 10);
		MapTileDataStore tileData = new MapTileDataStore();
		MapTileIndexStore index = new MapTileIndexStore();

		assertFalse(MapTaskScheduler.persistThenIndex(tile, tileData, index));
		assertEquals(0, index.totalCount());
	}
}
