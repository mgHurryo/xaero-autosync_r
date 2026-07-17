package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MapGapDetectorTest {
	@Test
	void waitsThirtySecondsAndFindsAnIsolatedHole() {
		MapGapDetector detector = new MapGapDetector();
		for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
			if (x != 1 || z != 1) detector.record(single(x, z, 4L), 1_000L);
		}

		assertTrue(detector.poll(30_999L, 2, (dimension, x, z) -> -1L).isEmpty());
		List<MapTileIndexEntry> recovery = detector.poll(31_000L, 2,
				(dimension, x, z) -> x == 1 && z == 1 ? -1L : 4L);

		assertEquals(1, recovery.size());
		assertEquals(1, recovery.get(0).chunkX());
		assertEquals(1, recovery.get(0).chunkZ());
	}

	@Test
	void doesNotTreatDeclaredButUnappliedNeighborsAsPresent() {
		MapGapDetector detector = new MapGapDetector();
		MapPatchKey key = MapPatchKey.square("minecraft:overworld", 0, 0, 3);
		List<MapPatchManifest.TileReference> tiles = java.util.stream.IntStream.range(0, 9)
				.mapToObj(index -> new MapPatchManifest.TileReference(index / 3, index % 3, 9L, index + 1L))
				.toList();
		detector.record(new MapPatchManifest(key, 1L, 9L, tiles), 0L);

		assertTrue(detector.poll(30_000L, 1, (dimension, x, z) -> -1L).isEmpty());
	}

	@Test
	void laterPatchExtendsRegionStabilityWindow() {
		MapGapDetector detector = new MapGapDetector();
		for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
			if (x != 1 || z != 1) detector.record(single(x, z, 4L), 0L);
		}
		detector.record(single(0, 0, 4L), 20_000L);

		MapGapDetector.RevisionLookup revisions = (dimension, x, z) -> x == 1 && z == 1 ? -1L : 4L;
		assertTrue(detector.poll(49_999L, 1, revisions).isEmpty());
		assertEquals(1, detector.poll(50_000L, 1, revisions).size());
	}

	@Test
	void findsAnExpectedTileWhoseAppliedRevisionStayedOld() {
		MapGapDetector detector = new MapGapDetector();
		MapPatchKey key = MapPatchKey.square("minecraft:overworld", 0, 0, 3);
		List<MapPatchManifest.TileReference> tiles = java.util.stream.IntStream.range(0, 9)
				.mapToObj(index -> new MapPatchManifest.TileReference(index / 3, index % 3, 9L, index + 1L))
				.toList();
		detector.record(new MapPatchManifest(key, 1L, 9L, tiles), 0L);

		List<MapTileIndexEntry> recovery = detector.poll(30_000L, 1,
				(dimension, x, z) -> x == 1 && z == 1 ? 8L : 9L);

		assertEquals(1, recovery.size());
		assertEquals(9L, recovery.get(0).revision());
	}

	private static MapPatchManifest single(int x, int z, long revision) {
		return new MapPatchManifest(MapPatchKey.square("minecraft:overworld", x, z, 1), 1L, revision,
				List.of(new MapPatchManifest.TileReference(x, z, revision, x * 31L + z + 1L)));
	}
}
