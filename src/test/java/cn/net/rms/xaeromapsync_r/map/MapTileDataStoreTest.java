package cn.net.rms.xaeromapsync_r.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class MapTileDataStoreTest {
	@TempDir Path tempDir;

	@Test
	void v3CacheUsesIndependentDirectory() {
		assertEquals("tiles-v3", MapTileDataStore.CACHE_DIRECTORY);
	}

	@Test
	void persistsAndReloadsVersionedSurfaceTile() {
		MapTile tile = tile("minecraft:overworld", -2, 7, 10);
		MapTileDataStore writer = new MapTileDataStore();
		writer.start(tempDir);
		assertTrue(writer.putSynchronously(tile));
		writer.stop();

		MapTileDataStore reader = new MapTileDataStore();
		reader.start(tempDir);
		MapTile loaded = reader.find("minecraft:overworld", -2, 7).orElseThrow();
		reader.stop();

		assertArrayEquals(tile.baseStateIds(), loaded.baseStateIds());
		assertArrayEquals(tile.baseHeights(), loaded.baseHeights());
		assertArrayEquals(tile.topHeights(), loaded.topHeights());
		assertArrayEquals(tile.biomeIds(), loaded.biomeIds());
		assertArrayEquals(tile.lightAbove(), loaded.lightAbove());
		assertArrayEquals(tile.glowing(), loaded.glowing());
		assertArrayEquals(tile.cave(), loaded.cave());
		assertEquals(tile.overlays(), loaded.overlays());
		assertEquals(tile.contentHash(), loaded.contentHash());
	}

	@Test
	void synchronousWriteReportsFailureWithoutPublishingMemoryTile() throws Exception {
		Path blockedRoot = tempDir.resolve("blocked-root");
		Files.writeString(blockedRoot, "not a directory");
		MapTileDataStore store = new MapTileDataStore();
		store.start(blockedRoot);

		assertFalse(store.putSynchronously(tile("minecraft:overworld", 1, 2, 20)));
		assertTrue(store.find("minecraft:overworld", 1, 2).isEmpty());
		store.stop();
	}

	@Test
	void rejectsOldCacheMagicWithoutDeletingTheFile() throws Exception {
		String dimension = "minecraft:overworld";
		Path dimensionPath = tempDir.resolve(Base64.getUrlEncoder().withoutPadding()
				.encodeToString(dimension.getBytes(StandardCharsets.UTF_8)));
		Files.createDirectories(dimensionPath);
		Path oldTile = dimensionPath.resolve("3_-4.tile");
		try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(
				new BufferedOutputStream(Files.newOutputStream(oldTile))))) {
			output.writeInt(0x584d5332);
		}
		MapTileDataStore store = new MapTileDataStore();
		store.start(tempDir);

		assertTrue(store.find(dimension, 3, -4).isEmpty());
		assertTrue(Files.isRegularFile(oldTile));
		store.stop();
	}

	@Test
	void rebuildsMissingIndexFromPersistedTiles() {
		MapTile first = tile("minecraft:overworld", -2, 7, 10);
		MapTile second = tile("minecraft:the_nether", 4, -9, 20);
		MapTileDataStore writer = new MapTileDataStore();
		writer.start(tempDir);
		assertTrue(writer.putSynchronously(first));
		assertTrue(writer.putSynchronously(second));
		writer.stop();

		MapTileDataStore reader = new MapTileDataStore();
		reader.start(tempDir);
		MapTileIndexStore index = new MapTileIndexStore();
		assertEquals(2, reader.recoverIndex(index));
		reader.stop();

		assertEquals(first.contentHash(), index.find("minecraft:overworld", -2, 7).orElseThrow().contentHash());
		assertEquals(second.contentHash(), index.find("minecraft:the_nether", 4, -9).orElseThrow().contentHash());
	}

	public static MapTile tile(String dimension, int chunkX, int chunkZ, int offset) {
		int[] baseStates = values(offset + 1000);
		int[] baseHeights = values(offset);
		int[] topHeights = values(offset + 3);
		int[] biomes = values(offset + 2000);
		byte[] lights = new byte[256];
		boolean[] glowing = new boolean[256];
		boolean[] cave = new boolean[256];
		List<List<MapTile.Overlay>> overlays = new ArrayList<>(256);
		for (int index = 0; index < 256; index++) {
			lights[index] = (byte) (index % 16);
			glowing[index] = index % 31 == 0;
			cave[index] = index % 17 == 0;
			overlays.add(index % 7 == 0
					? List.of(new MapTile.Overlay(offset + 3000 + index, 0.66F, (byte) ((index + 1) % 16), false))
					: List.of());
		}
		long hash = MapTileHasher.hashSurface(baseStates, baseHeights, topHeights, biomes, lights, glowing, cave, overlays);
		return new MapTile(dimension, chunkX, chunkZ, baseStates, baseHeights, topHeights, biomes, lights, glowing,
				cave, overlays, hash);
	}

	private static int[] values(int offset) {
		int[] values = new int[256];
		for (int index = 0; index < values.length; index++) values[index] = offset + index;
		return values;
	}
}
