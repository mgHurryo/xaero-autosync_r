package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClientMapTileCacheTest {
	@TempDir Path tempDir;

	@Test
	void revisionsSurviveRestartAndSubsequentSave() {
		Path file = tempDir.resolve("revisions.properties");
		ClientMapTileCache first = new ClientMapTileCache();
		first.load(file);
		first.put(tile("minecraft:overworld", -1, 2), 7L);
		first.saveIfDirty();

		ClientMapTileCache second = new ClientMapTileCache();
		second.load(file);
		assertTrue(second.hasRevision("minecraft:overworld", -1, 2, 7L));
		second.put(tile("minecraft:the_nether", 3, -4), 9L);
		second.saveIfDirty();

		ClientMapTileCache third = new ClientMapTileCache();
		third.load(file);
		assertTrue(third.hasRevision("minecraft:overworld", -1, 2, 7L));
		assertTrue(third.hasRevision("minecraft:the_nether", 3, -4, 9L));
	}

	private static MapTile tile(String dimension, int x, int z) {
		int[] values = new int[256];
		return new MapTile(dimension, x, z, values, values, values, values, 1L);
	}
}
