package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStoreTest;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import org.junit.jupiter.api.Test;

final class SharedMapNetworkingTest {
	@Test
	void matchingIndexWithoutDurableBodyMustBeRepaired() {
		MapTile tile = MapTileDataStoreTest.tile("minecraft:overworld", 1, 2, 3);
		MapTileIndexEntry entry = new MapTileIndexEntry(tile.dimension(), tile.chunkX(), tile.chunkZ(),
				tile.contentHash(), 7L, 1L);

		assertFalse(SharedMapNetworking.hasMatchingTileBody(entry, null, tile.contentHash()));
		assertTrue(SharedMapNetworking.hasMatchingTileBody(entry, tile, tile.contentHash()));
		assertFalse(SharedMapNetworking.hasMatchingTileBody(entry, tile, tile.contentHash() + 1L));
	}
}
