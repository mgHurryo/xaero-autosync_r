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

	private static MapTile tile(int chunkX, int chunkZ) {
		int[] states = new int[256]; Arrays.fill(states, 1);
		int[] heights = new int[256]; Arrays.fill(heights, 64);
		String[] biomes = new String[256]; Arrays.fill(biomes, "minecraft:plains");
		MapTile unhashed = new MapTile("minecraft:overworld", chunkX, chunkZ, states, heights, heights, biomes,
				new byte[256], new boolean[256], new boolean[256], MapTile.emptyOverlays(256), 0L);
		return new MapTile(unhashed.dimension(), chunkX, chunkZ, states, heights, heights, biomes,
				new byte[256], new boolean[256], new boolean[256], MapTile.emptyOverlays(256), MapTileHasher.hashSurface(unhashed));
	}
}
