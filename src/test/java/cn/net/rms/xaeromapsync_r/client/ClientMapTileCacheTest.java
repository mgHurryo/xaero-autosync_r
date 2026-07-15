package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileHasher;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClientMapTileCacheTest {
	@TempDir Path tempDirectory;

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

	private static MapTile tile(String dimension, int x, int z) {
		return tile(dimension, x, z, 0);
	}

	private static MapTile tile(String dimension, int x, int z, int offset) {
		int[] values = new int[256];
		java.util.Arrays.fill(values, offset);
		byte[] lights = new byte[256];
		boolean[] flags = new boolean[256];
		List<List<MapTile.Overlay>> overlays = new ArrayList<>(256);
		for (int index = 0; index < 256; index++) overlays.add(List.of());
		long hash = MapTileHasher.hashSurface(values, values, values, values, lights, flags, flags, overlays);
		return new MapTile(dimension, x, z, values, values, values, values, lights, flags, flags, overlays, hash);
	}
}
