package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

	@Test
	void localTileUploadsAreAcceptedOnlyNearServerViewDistance() {
		assertTrue(SharedMapNetworking.localTileUploadDistanceLimit(10) <= 11);
		assertTrue(SharedMapNetworking.localTileUploadDistanceLimit(0) >= 2);
	}

	@Test
	void archiveMergeFillsMissingTilesButNeverOverwritesServerBodies() {
		MapTile incoming = MapTileDataStoreTest.tile("minecraft:overworld", 1, 2, 3);
		MapTile existing = MapTileDataStoreTest.tile("minecraft:overworld", 1, 2, 4);
		MapTileIndexEntry incomingEntry = new MapTileIndexEntry(incoming.dimension(), 1, 2,
				incoming.contentHash(), 7L, 1L);
		assertEquals(SharedMapNetworking.LocalTileMergeDecision.ACCEPT,
				SharedMapNetworking.localTileMergeDecision(false, null, null, incoming.contentHash()));
		assertEquals(SharedMapNetworking.LocalTileMergeDecision.DUPLICATE,
				SharedMapNetworking.localTileMergeDecision(false, incomingEntry, incoming, incoming.contentHash()));
		assertEquals(SharedMapNetworking.LocalTileMergeDecision.KEEP_SERVER,
				SharedMapNetworking.localTileMergeDecision(false, incomingEntry, existing, incoming.contentHash()));
		assertEquals(SharedMapNetworking.LocalTileMergeDecision.ACCEPT,
				SharedMapNetworking.localTileMergeDecision(true, incomingEntry, existing, incoming.contentHash()));
	}
}
