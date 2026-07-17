package cn.net.rms.xaeromapsync_r.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapPatchCatalogTest {
	@TempDir Path tempDir;

	@Test
	void partitionsEveryRegionWithoutExceedingTransferSafeSquareSize() {
		for (int side = 1; side <= 32; side++) {
			List<MapTileIndexEntry> entries = new ArrayList<>();
			for (int x = 0; x < side; x++) for (int z = 0; z < side; z++) {
				entries.add(new MapTileIndexEntry("minecraft:overworld", x, z, x * 32L + z + 1L, 1L, 1L));
			}
			List<MapPatchCatalog.Square> squares = MapPatchCatalog.maximalSquares(
					new MapPatchCatalog.RegionCoordinate(0, 0), entries);
			assertEquals(side * side, squares.stream().mapToInt(square -> square.entries().size()).sum(), "side=" + side);
			assertTrue(squares.stream().allMatch(square -> square.sideLength() <= MapPatchCatalog.MAX_TRANSFER_SAFE_SIDE),
					"side=" + side);
			if (side <= MapPatchCatalog.MAX_TRANSFER_SAFE_SIDE) assertEquals(1, squares.size(), "side=" + side);
		}
	}

	@Test
	void coalescesForTwoSecondsThenPublishesLargestAvailableSquares() {
		MapTileIndexStore index = new MapTileIndexStore();
		MapTileDataStore bodies = new MapTileDataStore();
		AtomicLong now = new AtomicLong(1_000L);
		bodies.start(tempDir.resolve("tiles-v6"));
		try {
			MapPatchCatalog catalog = new MapPatchCatalog(index, bodies, now::get);
			for (int value = 0; value < 15; value++) {
				MapTile tile = tile(value / 4, value % 4);
				assertTrue(bodies.putSynchronously(tile));
				index.upsert(tile);
			}
			MapPatchCatalog.Snapshot initial = catalog.snapshot("minecraft:overworld");
			assertEquals(15, initial.manifests().stream().mapToInt(item -> item.tiles().size()).sum());
			assertTrue(initial.manifests().stream().allMatch(item -> catalog.load(item).isPresent()));

			MapTile finalTile = tile(3, 3);
			assertTrue(bodies.putSynchronously(finalTile));
			index.upsert(finalTile);
			assertEquals(initial.epoch(), catalog.snapshot("minecraft:overworld").epoch());
			now.addAndGet(MapPatchCatalog.COALESCE_WINDOW_MILLIS);
			MapPatchCatalog.Snapshot published = catalog.snapshot("minecraft:overworld");
			assertEquals(1, published.manifests().size());
			assertEquals(4, published.manifests().get(0).key().sideLength());
			assertEquals(16, published.manifests().get(0).tiles().size());
			assertTrue(catalog.load(published.manifests().get(0)).isPresent());
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

	@Test
	void retainsOldEpochWhileSlowHolePackagesDrain() {
		MapTileIndexStore index = new MapTileIndexStore();
		MapTileDataStore bodies = new MapTileDataStore();
		AtomicLong now = new AtomicLong(1_000L);
		bodies.start(tempDir.resolve("history-tiles-v6"));
		try {
			MapPatchCatalog catalog = new MapPatchCatalog(index, bodies, now::get);
			MapTile initialTile = tile(0, 0, 1);
			assertTrue(bodies.putSynchronously(initialTile));
			index.upsert(initialTile);
			MapPatchManifest initial = catalog.snapshot("minecraft:overworld").manifests().get(0);

			for (int revision = 2; revision < 20; revision++) {
				MapTile updated = tile(0, 0, revision);
				assertTrue(bodies.putSynchronously(updated));
				index.upsert(updated);
				now.addAndGet(MapPatchCatalog.COALESCE_WINDOW_MILLIS);
				catalog.snapshot("minecraft:overworld");
			}

			MapPatchManifest retained = catalog.manifest(initial.key(), initial.epoch()).orElseThrow();
			assertTrue(catalog.load(retained).isPresent());
			assertEquals(128, MapPatchCatalog.MAX_SNAPSHOT_HISTORY);
		} finally {
			bodies.stop();
		}
	}

	@Test
	void paginatedClientsKeepTheirRetainedSnapshotWhileNewWavesPublish() {
		MapTileIndexStore index = new MapTileIndexStore();
		MapTileDataStore bodies = new MapTileDataStore();
		AtomicLong now = new AtomicLong(1_000L);
		bodies.start(tempDir.resolve("stable-pagination-tiles-v6"));
		try {
			MapPatchCatalog catalog = new MapPatchCatalog(index, bodies, now::get);
			MapTile initialTile = tile(0, 0, 1);
			assertTrue(bodies.putSynchronously(initialTile));
			index.upsert(initialTile);
			MapPatchCatalog.Snapshot initial = catalog.snapshot("minecraft:overworld");

			MapTile updatedTile = tile(0, 0, 2);
			assertTrue(bodies.putSynchronously(updatedTile));
			index.upsert(updatedTile);
			assertEquals(initial, catalog.snapshot("minecraft:overworld"));
			now.addAndGet(MapPatchCatalog.COALESCE_WINDOW_MILLIS);
			MapPatchCatalog.Snapshot current = catalog.snapshot("minecraft:overworld");

			assertTrue(current.epoch() != initial.epoch());
			assertEquals(initial, catalog.snapshot("minecraft:overworld", initial.epoch()));
			assertEquals(current, catalog.snapshot("minecraft:overworld", Long.MAX_VALUE));
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
