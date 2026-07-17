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
import java.util.Optional;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class MapTileDataStoreTest {
	@TempDir Path tempDir;

	@Test
	void v5CacheUsesIndependentDirectory() {
		assertEquals("tiles-v6", MapTileDataStore.CACHE_DIRECTORY);
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
		assertArrayEquals(tile.biomeKeys(), loaded.biomeKeys());
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
	void asynchronousWritePublishesOnlyAfterAtomicPersistence() throws Exception {
		MapTile tile = tile("minecraft:overworld", 8, -3, 30);
		MapTileDataStore store = new MapTileDataStore();
		store.start(tempDir);
		CountDownLatch completed = new CountDownLatch(1);
		AtomicBoolean successful = new AtomicBoolean();

		assertTrue(store.putAsynchronously(tile, result -> {
			successful.set(result);
			completed.countDown();
		}));
		assertTrue(completed.await(5, TimeUnit.SECONDS));
		assertTrue(successful.get());
		assertEquals(tile.contentHash(), store.find(tile.dimension(), tile.chunkX(), tile.chunkZ()).orElseThrow().contentHash());
		store.stop();
	}

	@Test
	void laterSnapshotHashReplacesEarlierSnapshotForTheSameCoordinate() throws Exception {
		MapTile first = tile("minecraft:overworld", 8, -3, 30);
		MapTile latest = tile("minecraft:overworld", 8, -3, 31);
		MapTileDataStore store = new MapTileDataStore();
		store.start(tempDir);
		CountDownLatch completed = new CountDownLatch(2);

		assertTrue(store.putAsynchronously(first, result -> completed.countDown()));
		assertTrue(store.putAsynchronously(latest, result -> completed.countDown()));
		assertTrue(completed.await(5, TimeUnit.SECONDS));
		assertEquals(latest.contentHash(), store.find(latest.dimension(), latest.chunkX(), latest.chunkZ())
				.orElseThrow().contentHash());
		assertEquals(first.contentHash(), store.find(first.dimension(), first.chunkX(), first.chunkZ(),
				first.contentHash()).orElseThrow().contentHash());
		store.stop();
	}

	@Test
	void stagedSnapshotDoesNotReplaceTheCurrentBodyUntilCommitted() throws Exception {
		MapTile current = tile("minecraft:overworld", 8, -3, 30);
		MapTile latest = tile("minecraft:overworld", 8, -3, 31);
		MapTileDataStore store = new MapTileDataStore();
		store.start(tempDir);
		assertTrue(store.putSynchronously(current));
		CountDownLatch completed = new CountDownLatch(1);
		java.util.concurrent.atomic.AtomicReference<MapTileDataStore.StagedTile> staged =
				new java.util.concurrent.atomic.AtomicReference<>();

		assertTrue(store.stageAsynchronously(latest, result -> {
			staged.set(result.orElse(null));
			completed.countDown();
		}));
		assertTrue(completed.await(5, TimeUnit.SECONDS));
		assertEquals(current.contentHash(), store.find(current.dimension(), current.chunkX(), current.chunkZ())
				.orElseThrow().contentHash());
		assertTrue(store.commitStaged(staged.get()));
		assertEquals(latest.contentHash(), store.find(latest.dimension(), latest.chunkX(), latest.chunkZ())
				.orElseThrow().contentHash());
		assertEquals(current.contentHash(), store.find(current.dimension(), current.chunkX(), current.chunkZ(),
				current.contentHash()).orElseThrow().contentHash());
		store.stop();
	}

	@Test
	void asynchronousWriteFailureDoesNotPublishMemoryTile() throws Exception {
		Path blockedRoot = tempDir.resolve("async-blocked-root");
		Files.writeString(blockedRoot, "not a directory");
		MapTile tile = tile("minecraft:overworld", 9, -4, 40);
		MapTileDataStore store = new MapTileDataStore();
		store.start(blockedRoot);
		CountDownLatch completed = new CountDownLatch(1);
		AtomicBoolean successful = new AtomicBoolean(true);

		assertTrue(store.putAsynchronously(tile, result -> {
			successful.set(result);
			completed.countDown();
		}));
		assertTrue(completed.await(5, TimeUnit.SECONDS));
		assertFalse(successful.get());
		assertTrue(store.find(tile.dimension(), tile.chunkX(), tile.chunkZ()).isEmpty());
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

	@Test
	void recoveryRemovesOrphanedStagedTileFiles() throws Exception {
		String dimension = "minecraft:overworld";
		Path dimensionPath = tempDir.resolve(Base64.getUrlEncoder().withoutPadding()
				.encodeToString(dimension.getBytes(StandardCharsets.UTF_8)));
		Files.createDirectories(dimensionPath);
		Path orphanedStage = dimensionPath.resolve("4_-7.tile.stage-" + UUID.randomUUID());
		Files.writeString(orphanedStage, "incomplete staged body");
		MapTileDataStore store = new MapTileDataStore();
		store.start(tempDir);

		assertEquals(0, store.recoverIndex(new MapTileIndexStore()));
		assertFalse(Files.exists(orphanedStage));
		store.stop();
	}

	@Test
	void rebuildsMissingIndexAsynchronously() throws Exception {
		MapTile tile = tile("minecraft:overworld", 5, -6, 30);
		MapTileDataStore store = new MapTileDataStore();
		store.start(tempDir);
		assertTrue(store.putSynchronously(tile));
		MapTileIndexStore index = new MapTileIndexStore();
		CountDownLatch completed = new CountDownLatch(1);
		AtomicInteger recovered = new AtomicInteger();

		assertTrue(store.recoverIndexAsynchronously(index, count -> {
			recovered.set(count);
			completed.countDown();
		}));
		assertTrue(completed.await(5, TimeUnit.SECONDS));
		store.stop();

		assertEquals(1, recovered.get());
		assertEquals(tile.contentHash(), index.find(tile.dimension(), tile.chunkX(), tile.chunkZ())
				.orElseThrow().contentHash());
	}

	@Test
	void recoveryReadsOnlyMissingOrNewerTileBodies() {
		MapTileIndexEntry existing = new MapTileIndexEntry("minecraft:overworld", 1, 2, 3L, 4L, 100L);

		assertTrue(MapTileDataStore.shouldReadForRecovery(Optional.empty(), 1L));
		assertFalse(MapTileDataStore.shouldReadForRecovery(Optional.of(existing), 100L));
		assertFalse(MapTileDataStore.shouldReadForRecovery(Optional.of(existing), 99L));
		assertTrue(MapTileDataStore.shouldReadForRecovery(Optional.of(existing), 101L));
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
					? List.of(new MapTile.Overlay(offset + 3000 + index, 0.66F, (byte) ((index + 1) % 16), false, 2))
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
