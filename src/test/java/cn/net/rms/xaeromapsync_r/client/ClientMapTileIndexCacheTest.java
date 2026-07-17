package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClientMapTileIndexCacheTest {
	@Test
	void verifiesThatReceivedLeavesRebuildTheAdvertisedRoot() {
		ClientMapTileIndexCache cache = new ClientMapTileIndexCache();
		MapTileIndexEntry first = new MapTileIndexEntry("minecraft:overworld", 0, 0, 11L, 1L, 0L);
		MapTileIndexEntry second = new MapTileIndexEntry("minecraft:overworld", 1, 0, 12L, 2L, 0L);
		cache.upsert(first);
		long partialRoot = cache.computedRootHash();
		cache.upsert(second);
		long completeRoot = cache.computedRootHash();

		assertFalse(cache.matchesRootHash(partialRoot));
		assertTrue(cache.matchesRootHash(completeRoot));
	}

	@Test
	void dimensionRootIgnoresLeavesFromOtherDimensions() {
		ClientMapTileIndexCache cache = new ClientMapTileIndexCache();
		MapTileIndexEntry overworld = new MapTileIndexEntry("minecraft:overworld", 0, 0, 11L, 1L, 0L);
		cache.upsert(overworld);
		long overworldRoot = cache.computedRootHash("minecraft:overworld");
		cache.upsert(new MapTileIndexEntry("minecraft:the_nether", 0, 0, 12L, 1L, 0L));

		assertTrue(cache.matchesRootHash("minecraft:overworld", overworldRoot));
		assertFalse(cache.matchesRootHash(overworldRoot));
	}

	@Test
	void replacingLeafContentInvalidatesCachedRoot() {
		ClientMapTileIndexCache cache = new ClientMapTileIndexCache();
		cache.upsert(entry(1L));
		long originalRoot = cache.computedRootHash();

		cache.upsert(entry(2L));

		assertFalse(cache.matchesRootHash(originalRoot));
	}

	@Test
	void delayedEntryCannotReplaceNewerRevision() {
		ClientMapTileIndexCache index = new ClientMapTileIndexCache();
		index.upsert(entry(11L));
		index.upsert(entry(10L));

		MapTileIndexEntry missing = index.missingFrom(new ClientMapTileCache(), 1).get(0);
		assertEquals(11L, missing.revision());
	}

	@Test
	void missingTilesAreGroupedByXaeroRegion() {
		ClientMapTileIndexCache index = new ClientMapTileIndexCache();
		index.replace(1L, List.of(
				entry(33, 0, 1L),
				entry(-1, 0, 2L),
				entry(31, 0, 3L),
				entry(32, 0, 4L)));

		List<MapTileIndexEntry> missing = index.missingFrom(new ClientMapTileCache(), Integer.MAX_VALUE);

		assertEquals(List.of(-1, 31, 32, 33), missing.stream().map(MapTileIndexEntry::chunkX).toList());
	}

	private static MapTileIndexEntry entry(long revision) {
		return new MapTileIndexEntry("minecraft:overworld", 4, -7, revision * 10L, revision, 0L);
	}

	private static MapTileIndexEntry entry(int chunkX, int chunkZ, long revision) {
		return new MapTileIndexEntry("minecraft:overworld", chunkX, chunkZ, revision * 10L, revision, 0L);
	}
}
