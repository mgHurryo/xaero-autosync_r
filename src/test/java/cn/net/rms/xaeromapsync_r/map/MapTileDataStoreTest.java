package cn.net.rms.xaeromapsync_r.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MapTileDataStoreTest {
	@TempDir Path tempDir;

	@Test
	void persistsAndReloadsSurfaceTile() {
		int[] heights = values(10);
		int[] states = values(1000);
		int[] biomes = values(2000);
		int[] lights = values(0);
		long hash = MapTileHasher.hashSurface(heights, states, biomes, lights);
		MapTile tile = new MapTile("minecraft:overworld", -2, 7, heights, states, biomes, lights, hash);

		MapTileDataStore writer = new MapTileDataStore();
		writer.start(tempDir);
		writer.put(tile);
		writer.stop();

		MapTileDataStore reader = new MapTileDataStore();
		reader.start(tempDir);
		MapTile loaded = reader.find("minecraft:overworld", -2, 7).orElseThrow();
		reader.stop();
		assertArrayEquals(heights, loaded.heights());
		assertArrayEquals(states, loaded.blockStateIds());
		assertArrayEquals(biomes, loaded.biomeIds());
		assertArrayEquals(lights, loaded.lightLevels());
		assertTrue(loaded.contentHash() == hash);
	}

	private static int[] values(int offset) {
		int[] values = new int[256];
		for (int index = 0; index < values.length; index++) values[index] = offset + index;
		return values;
	}
}
