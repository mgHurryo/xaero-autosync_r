package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileHasher;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClientMapTileCacheTest {
	@TempDir Path tempDirectory;

	@Test
	void metadataCacheEvictsLeastRecentlyUsedEntryAtCapacity() {
		java.util.Map<Integer, Integer> cache = ClientMapTileCache.boundedAccessMap(2);
		cache.put(1, 1);
		cache.put(2, 2);
		assertEquals(1, cache.get(1));
		cache.put(3, 3);

		assertTrue(cache.containsKey(1));
		assertFalse(cache.containsKey(2));
		assertTrue(cache.containsKey(3));
	}

	@Test
	void sessionResetInvalidatesAppliedRevisions() {
		ClientMapTileCache cache = new ClientMapTileCache();
		cache.markApplied(tile("minecraft:overworld", -1, 2), 7L);
		assertTrue(cache.hasRevision("minecraft:overworld", -1, 2, 7L));

		cache.clearSession();

		assertFalse(cache.hasRevision("minecraft:overworld", -1, 2, 7L));
	}

	@Test
	void delayedPayloadCannotReplaceNewerRevision() {
		ClientMapTileCache cache = new ClientMapTileCache();
		cache.markApplied(tile("minecraft:overworld", 1, 2), 9L);
		cache.markApplied(tile("minecraft:overworld", 1, 2), 7L);

		assertTrue(cache.hasRevision("minecraft:overworld", 1, 2, 9L));
		assertFalse(cache.hasRevision("minecraft:overworld", 1, 2, 10L));
	}

	@Test
	void cachedTileIdentityRequiresMatchingRevisionAndHashValues() {
		assertTrue(ClientMapTileCache.sameCachedTile(7L, 7L, Long.valueOf(42L), 42L));
		assertFalse(ClientMapTileCache.sameCachedTile(8L, 7L, Long.valueOf(42L), 42L));
		assertFalse(ClientMapTileCache.sameCachedTile(7L, 7L, Long.valueOf(41L), 42L));
		assertFalse(ClientMapTileCache.sameCachedTile(7L, 7L, null, 42L));
	}

	@Test
	void tileBodiesSurviveRestartWithoutTrustingTheOldAppliedRevision() throws Exception {
		MapTile tile = tile("minecraft:overworld", 4, -7);
		MapTileIndexEntry entry = new MapTileIndexEntry(tile.dimension(), tile.chunkX(), tile.chunkZ(),
				tile.contentHash(), 12L, 1L);
		Path cachePath = tempDirectory.resolve("tiles");
		ClientMapTileCache first = new ClientMapTileCache();
		first.start(cachePath);
		first.cache(tile, entry.revision());
		first.markApplied(tile, entry.revision());
		first.stop();

		ClientMapTileCache restarted = new ClientMapTileCache();
		restarted.start(cachePath);
		MapTile restored = restarted.findCached(entry).get().orElseThrow();

		assertEquals(tile.contentHash(), restored.contentHash());
		assertFalse(restarted.hasRevision(tile.dimension(), tile.chunkX(), tile.chunkZ(), entry.revision()));
		restarted.stop();
	}

	@Test
	void delayedOlderPayloadCannotOverwriteNewerDiskBody() throws Exception {
		MapTile newer = tile("minecraft:overworld", 8, 9, 2);
		MapTile older = tile("minecraft:overworld", 8, 9, 1);
		Path cachePath = tempDirectory.resolve("ordered-tiles");
		ClientMapTileCache cache = new ClientMapTileCache();
		cache.start(cachePath);
		cache.cache(newer, 11L);
		cache.cache(older, 10L);
		cache.stop();

		ClientMapTileCache restarted = new ClientMapTileCache();
		restarted.start(cachePath);
		MapTileIndexEntry entry = new MapTileIndexEntry(newer.dimension(), newer.chunkX(), newer.chunkZ(),
				newer.contentHash(), 11L, 0L);
		assertEquals(newer.contentHash(), restarted.findCached(entry).get().orElseThrow().contentHash());
		restarted.stop();
	}

	@Test
	void unrenderablePlaceholderTilesAreNotCached() throws Exception {
		MapTile placeholder = allAirTile("minecraft:overworld", 12, 13);
		MapTileIndexEntry entry = new MapTileIndexEntry(placeholder.dimension(), placeholder.chunkX(),
				placeholder.chunkZ(), placeholder.contentHash(), 5L, 0L);
		ClientMapTileCache cache = new ClientMapTileCache();
		cache.start(tempDirectory.resolve("placeholder-tiles"));

		cache.cache(placeholder, entry.revision());

		assertTrue(cache.findCached(entry).get().isEmpty());
		cache.stop();
	}

	@Test
	void appliedTileRevisionsSurviveAdaptivePatchReshaping() {
		ClientMapTileCache cache = new ClientMapTileCache();
		MapPatchKey key = MapPatchKey.square("minecraft:overworld", 4, 8, 2);
		List<MapPatchManifest.TileReference> references = new ArrayList<>();
		for (int x = 4; x < 6; x++) for (int z = 8; z < 10; z++) {
			MapTile tile = tile(key.dimension(), x, z);
			cache.markApplied(tile, 7L);
			references.add(new MapPatchManifest.TileReference(x, z, 7L, tile.contentHash()));
		}
		MapPatchManifest reshaped = new MapPatchManifest(key, 1L, 7L, references);
		assertTrue(cache.hasApplied(reshaped));

		MapPatchManifest newer = new MapPatchManifest(key, 2L, 8L, references.stream()
				.map(reference -> reference.chunkX() == 5 && reference.chunkZ() == 9
						? new MapPatchManifest.TileReference(5, 9, 8L, reference.contentHash()) : reference)
				.toList());
		assertFalse(cache.hasApplied(newer));
	}

	@Test
	void burstWaveCacheWritesDrainWithoutDroppingBodies() throws Exception {
		Path cachePath = tempDirectory.resolve("burst-wave-tiles");
		ClientMapTileCache cache = new ClientMapTileCache();
		cache.start(cachePath);
		MapTile first = null;
		MapTile last = null;
		for (int index = 0; index < 1_100; index++) {
			MapTile current = tile("minecraft:overworld", index, -index, index & 3);
			if (first == null) first = current;
			last = current;
			cache.cache(current, index + 1L);
		}
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20L);
		while (cache.pendingWriteCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10L);
		assertEquals(0, cache.pendingWriteCount());
		cache.stop();

		ClientMapTileCache restarted = new ClientMapTileCache();
		restarted.start(cachePath);
		for (MapTile expected : List.of(first, last)) {
			MapTileIndexEntry entry = new MapTileIndexEntry(expected.dimension(), expected.chunkX(), expected.chunkZ(),
					expected.contentHash(), expected == first ? 1L : 1_100L, 0L);
			assertEquals(expected.contentHash(), restarted.findCached(entry).get().orElseThrow().contentHash());
		}
		restarted.stop();
	}

	private static MapTile tile(String dimension, int x, int z) {
		return tile(dimension, x, z, 0);
	}

	private static MapTile tile(String dimension, int x, int z, int offset) {
		int[] baseStates = new int[256];
		java.util.Arrays.fill(baseStates, offset + 1);
		int[] values = new int[256];
		java.util.Arrays.fill(values, offset);
		byte[] lights = new byte[256];
		boolean[] flags = new boolean[256];
		List<List<MapTile.Overlay>> overlays = new ArrayList<>(256);
		for (int index = 0; index < 256; index++) overlays.add(List.of());
		long hash = MapTileHasher.hashSurface(baseStates, values, values, values, lights, flags, flags, overlays);
		return new MapTile(dimension, x, z, baseStates, values, values, values, lights, flags, flags, overlays, hash);
	}

	private static MapTile allAirTile(String dimension, int x, int z) {
		int[] values = new int[256];
		byte[] lights = new byte[256];
		boolean[] flags = new boolean[256];
		List<List<MapTile.Overlay>> overlays = new ArrayList<>(256);
		for (int index = 0; index < 256; index++) overlays.add(List.of());
		long hash = MapTileHasher.hashSurface(values, values, values, values, lights, flags, flags, overlays);
		return new MapTile(dimension, x, z, values, values, values, values, lights, flags, flags, overlays, hash);
	}
}
