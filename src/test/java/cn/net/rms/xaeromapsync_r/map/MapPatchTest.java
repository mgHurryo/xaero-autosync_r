package cn.net.rms.xaeromapsync_r.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapPatchTest {
	@Test
	void acceptsOnlyCompleteAlignedPatch() {
		MapPatchKey key = MapPatchKey.fromChunk("minecraft:overworld", -1, 5);
		List<MapPatchManifest.TileReference> references = references(key);
		MapPatchManifest manifest = new MapPatchManifest(key, 7L, 19L, references);
		MapPatch patch = new MapPatch(manifest, tiles(key));

		assertEquals(16, patch.tiles().size());
		assertEquals(-1, key.patchX());
		assertEquals(1, key.patchZ());
	}

	@Test
	void rejectsPartialOrMismatchedPatch() {
		MapPatchKey key = new MapPatchKey("minecraft:overworld", 0, 0);
		List<MapPatchManifest.TileReference> partial = references(key).subList(0, 15);
		assertThrows(IllegalArgumentException.class, () -> new MapPatchManifest(key, 1L, 1L, partial));

		MapPatchManifest manifest = new MapPatchManifest(key, 1L, 1L, references(key));
		List<MapTile> mismatched = new ArrayList<>(tiles(key));
		mismatched.set(0, tile(0, 0, 999L));
		assertThrows(IllegalArgumentException.class, () -> new MapPatch(manifest, mismatched));
	}

	@Test
	void contentHashDoesNotChangeForUnrelatedCatalogEpoch() {
		MapPatchKey key = new MapPatchKey("minecraft:overworld", 0, 0);
		MapPatchManifest first = new MapPatchManifest(key, Long.MIN_VALUE, 19L, references(key));
		MapPatchManifest laterCatalog = new MapPatchManifest(key, 8L, 19L, references(key));

		assertEquals(Long.MIN_VALUE, first.epoch());
		assertEquals(first.contentHash(), laterCatalog.contentHash());
	}

	private static List<MapPatchManifest.TileReference> references(MapPatchKey key) {
		List<MapPatchManifest.TileReference> result = new ArrayList<>();
		for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) {
			long hash = dx * 4L + dz + 1L;
			result.add(new MapPatchManifest.TileReference(key.minChunkX() + dx, key.minChunkZ() + dz, hash, hash));
		}
		return result;
	}

	private static List<MapTile> tiles(MapPatchKey key) {
		List<MapTile> result = new ArrayList<>();
		for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) {
			long hash = dx * 4L + dz + 1L;
			result.add(tile(key.minChunkX() + dx, key.minChunkZ() + dz, hash));
		}
		return result;
	}

	private static MapTile tile(int chunkX, int chunkZ, long hash) {
		int[] states = new int[256];
		Arrays.fill(states, 1);
		int[] heights = new int[256];
		Arrays.fill(heights, 64);
		String[] biomes = new String[256];
		Arrays.fill(biomes, "minecraft:plains");
		return new MapTile("minecraft:overworld", chunkX, chunkZ, states, heights, heights, biomes,
				new byte[256], new boolean[256], new boolean[256], MapTile.emptyOverlays(256), hash);
	}
}
