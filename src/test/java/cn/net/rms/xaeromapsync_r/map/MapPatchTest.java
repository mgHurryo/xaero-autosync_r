package cn.net.rms.xaeromapsync_r.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapPatchTest {
	@Test
	void acceptsCompleteAlignedPatch() {
		MapPatchKey key = MapPatchKey.fromChunk("minecraft:overworld", -1, 5);
		List<MapPatchManifest.TileReference> references = references(key);
		MapPatchManifest manifest = new MapPatchManifest(key, 7L, 19L, references);
		MapPatch patch = new MapPatch(manifest, tiles(key));

		assertEquals(16, patch.tiles().size());
		assertEquals(-1, key.patchX());
		assertEquals(1, key.patchZ());
	}

	@Test
	void acceptsAdaptiveSquareAndRejectsMismatchedBody() {
		MapPatchKey key = new MapPatchKey("minecraft:overworld", 0, 0);
		MapPatchKey adaptive = MapPatchKey.square("minecraft:overworld", 0, 0, 3);
		MapPatchManifest adaptiveManifest = new MapPatchManifest(adaptive, 1L, 1L, references(adaptive));
		MapPatch adaptivePatch = new MapPatch(adaptiveManifest, tiles(adaptive));
		assertEquals(9, adaptivePatch.tiles().size());

		MapPatchManifest manifest = new MapPatchManifest(key, 1L, 1L, references(key));
		List<MapTile> mismatched = new ArrayList<>(tiles(key));
		mismatched.set(0, tile(0, 0, 999L));
		assertThrows(IllegalArgumentException.class, () -> new MapPatch(manifest, mismatched));
	}

	@Test
	void acceptsFullRegionAndRejectsInvalidSquareBounds() {
		MapPatchKey fullRegion = MapPatchKey.square("minecraft:overworld", -32, 64, 32);
		assertEquals(1_024, fullRegion.tileCount());
		assertEquals(-1, fullRegion.xaeroRegionX());
		assertEquals(2, fullRegion.xaeroRegionZ());
		assertThrows(IllegalArgumentException.class,
				() -> MapPatchKey.square("minecraft:overworld", 0, 0, 33));
		assertThrows(IllegalArgumentException.class,
				() -> MapPatchKey.square("minecraft:overworld", 31, 0, 2));
	}

	@Test
	void rejectsEmptyDuplicateAndOutOfRangeSquarePatch() {
		MapPatchKey key = new MapPatchKey("minecraft:overworld", 0, 0);
		assertThrows(IllegalArgumentException.class, () -> new MapPatchManifest(key, 1L, 1L, List.of()));
		MapPatchManifest.TileReference first = references(key).get(0);
		assertThrows(IllegalArgumentException.class,
				() -> new MapPatchManifest(key, 1L, 1L, List.of(first, first)));
		assertThrows(IllegalArgumentException.class, () -> new MapPatchManifest(key, 1L, 1L,
				List.of(new MapPatchManifest.TileReference(4, 0, 1L, 1L))));
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
		for (int dx = 0; dx < key.sideLength(); dx++) for (int dz = 0; dz < key.sideLength(); dz++) {
			long hash = dx * (long) key.sideLength() + dz + 1L;
			result.add(new MapPatchManifest.TileReference(key.minChunkX() + dx, key.minChunkZ() + dz, hash, hash));
		}
		return result;
	}

	private static List<MapTile> tiles(MapPatchKey key) {
		List<MapTile> result = new ArrayList<>();
		for (int dx = 0; dx < key.sideLength(); dx++) for (int dz = 0; dz < key.sideLength(); dz++) {
			long hash = dx * (long) key.sideLength() + dz + 1L;
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
