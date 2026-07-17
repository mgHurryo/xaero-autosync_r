package cn.net.rms.xaeromapsync_r.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapPatchCatalogTest {
	@TempDir Path tempDir;

	@Test
	void publishesOnlyCompleteDurablePatches() {
		MapTileIndexStore index = new MapTileIndexStore();
		MapTileDataStore bodies = new MapTileDataStore();
		bodies.start(tempDir.resolve("tiles-v6"));
		try {
			MapPatchCatalog catalog = new MapPatchCatalog(index, bodies);
			for (int value = 0; value < 15; value++) {
				MapTile tile = tile(value / 4, value % 4);
				assertTrue(bodies.putSynchronously(tile));
				index.upsert(tile);
			}
			assertTrue(catalog.manifests("minecraft:overworld").isEmpty());

			MapTile finalTile = tile(3, 3);
			assertTrue(bodies.putSynchronously(finalTile));
			index.upsert(finalTile);
			assertEquals(1, catalog.manifests("minecraft:overworld").size());
			assertTrue(catalog.load(catalog.manifests("minecraft:overworld").get(0)).isPresent());
		} finally {
			bodies.stop();
		}
	}

	@Test
	void publishesCatalogSnapshotWithSignedHashEpoch() {
		MapTileIndexStore index = new MapTileIndexStore();
		MapTileDataStore bodies = new MapTileDataStore();
		bodies.start(tempDir.resolve("signed-epoch-tiles-v6"));
		try {
			MapTile[] currentTiles = new MapTile[MapPatchKey.TILE_COUNT];
			boolean foundNegativeEpoch = false;
			for (long salt = 1L; salt <= 256L && !foundNegativeEpoch; salt++) {
				for (int value = 0; value < MapPatchKey.TILE_COUNT; value++) {
					MapTile tile = tile(value / 4, value % 4, (int) (salt * 31L + value));
					currentTiles[value] = tile;
					index.upsert(tile);
				}
				foundNegativeEpoch = index.dimensionSnapshot("minecraft:overworld").epoch() < 0L;
			}
			assertTrue(foundNegativeEpoch, "test fixture must produce a signed-negative catalog hash");
			for (MapTile tile : currentTiles) assertTrue(bodies.putSynchronously(tile));

			MapPatchCatalog.Snapshot snapshot = new MapPatchCatalog(index, bodies).snapshot("minecraft:overworld");
			assertTrue(snapshot.epoch() < 0L);
			assertEquals(1, snapshot.manifests().size());
			assertEquals(snapshot.epoch(), snapshot.manifests().get(0).epoch());
		} finally {
			bodies.stop();
		}
	}

	private static MapTile tile(int chunkX, int chunkZ) {
		int[] states = new int[256]; Arrays.fill(states, 1);
		int[] heights = new int[256]; Arrays.fill(heights, 64);
		String[] biomes = new String[256]; Arrays.fill(biomes, "minecraft:plains");
		MapTile unhashed = new MapTile("minecraft:overworld", chunkX, chunkZ, states, heights, heights, biomes,
				new byte[256], new boolean[256], new boolean[256], MapTile.emptyOverlays(256), 0L);
		return new MapTile(unhashed.dimension(), chunkX, chunkZ, states, heights, heights, biomes,
				new byte[256], new boolean[256], new boolean[256], MapTile.emptyOverlays(256), MapTileHasher.hashSurface(unhashed));
	}

	private static MapTile tile(int chunkX, int chunkZ, int stateId) {
		int[] states = new int[256]; Arrays.fill(states, stateId);
		int[] heights = new int[256]; Arrays.fill(heights, 64);
		String[] biomes = new String[256]; Arrays.fill(biomes, "minecraft:plains");
		MapTile unhashed = new MapTile("minecraft:overworld", chunkX, chunkZ, states, heights, heights, biomes,
				new byte[256], new boolean[256], new boolean[256], MapTile.emptyOverlays(256), 0L);
		return new MapTile(unhashed.dimension(), chunkX, chunkZ, states, heights, heights, biomes,
				new byte[256], new boolean[256], new boolean[256], MapTile.emptyOverlays(256),
				MapTileHasher.hashSurface(unhashed));
	}
}
