package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.map.MapPatch;
import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.xaero.XaeroMapAdapter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class AtomicPatchCoordinatorTest {
	@Test
	void commitsCompletePatchAsSingleAdapterBatch() {
		FakeAdapter adapter = new FakeAdapter();
		List<MapPatch> applied = new ArrayList<>();
		AtomicPatchCoordinator coordinator = new AtomicPatchCoordinator(adapter, applied::add, ignored -> { });
		coordinator.enqueueVerified(patch(new MapPatchKey("minecraft:overworld", 0, 0)));

		coordinator.tick(100L, Long.MAX_VALUE);

		assertEquals(1, adapter.calls);
		assertEquals(16, adapter.lastBatchSize);
		assertEquals(1, applied.size());
		assertEquals(0, coordinator.pendingCount());
	}

	@Test
	void preservesLocalXaeroTilesAndWaitsForGeneration() {
		FakeAdapter adapter = new FakeAdapter();
		adapter.generatingTiles = ignored -> true;
		AtomicPatchCoordinator coordinator = new AtomicPatchCoordinator(adapter, ignored -> { }, ignored -> { });
		coordinator.enqueueVerified(patch(new MapPatchKey("minecraft:overworld", 0, 0)));
		coordinator.tick(100L, Long.MAX_VALUE);
		assertEquals(0, adapter.calls);

		adapter.generatingTiles = ignored -> false;
		adapter.readyTiles = ignored -> true;
		coordinator.tick(10_000L, Long.MAX_VALUE);
		assertEquals(0, adapter.calls);
		assertEquals(0, coordinator.pendingCount());
	}

	@Test
	void forcesRemoteCommitAfterTwoSecondLocalGenerationDeadline() {
		FakeAdapter adapter = new FakeAdapter();
		adapter.generatingTiles = ignored -> true;
		AtomicPatchCoordinator coordinator = new AtomicPatchCoordinator(adapter, ignored -> { }, ignored -> { });
		MapPatch patch = patch(new MapPatchKey("minecraft:overworld", 0, 0));
		coordinator.enqueueVerified(patch);

		coordinator.tick(100L, Long.MAX_VALUE);
		assertEquals(0, adapter.calls);
		assertTrue(coordinator.hasPending(patch.manifest().key()));
		assertEquals(1, coordinator.statistics(1_000L).localWaiting());
		assertEquals(900L, coordinator.statistics(1_000L).oldestLocalWaitMillis());

		coordinator.tick(2_099L, Long.MAX_VALUE);
		assertEquals(0, adapter.calls);
		coordinator.tick(2_100L, Long.MAX_VALUE);

		assertEquals(1, adapter.calls);
		assertEquals(16, adapter.lastBatchSize);
		assertFalse(coordinator.hasPending(patch.manifest().key()));
		assertEquals(1L, coordinator.statistics(2_100L).forcedRemoteCommits());
	}

	@Test
	void timeoutNeverOverwritesReadyLocalTiles() {
		FakeAdapter adapter = new FakeAdapter();
		adapter.readyTiles = tile -> tile.chunkX() == 0 && tile.chunkZ() == 0;
		adapter.generatingTiles = tile -> !adapter.readyTiles.test(tile);
		AtomicPatchCoordinator coordinator = new AtomicPatchCoordinator(adapter, ignored -> { }, ignored -> { });
		coordinator.enqueueVerified(patch(new MapPatchKey("minecraft:overworld", 0, 0)));

		coordinator.tick(100L, Long.MAX_VALUE);
		coordinator.tick(2_100L, Long.MAX_VALUE);

		assertEquals(15, adapter.lastBatchSize);
		assertFalse(adapter.lastBatch.stream().anyMatch(tile -> tile.chunkX() == 0 && tile.chunkZ() == 0));
	}

	@Test
	void rotatesLocalGenerationWaitBehindLaterPatchInSameRegion() {
		FakeAdapter adapter = new FakeAdapter();
		adapter.generatingTiles = tile -> tile.chunkX() < 4;
		List<MapPatch> applied = new ArrayList<>();
		AtomicPatchCoordinator coordinator = new AtomicPatchCoordinator(adapter, applied::add, ignored -> { });
		MapPatch waiting = patch(new MapPatchKey("minecraft:overworld", 0, 0));
		MapPatch ready = patch(new MapPatchKey("minecraft:overworld", 1, 0));
		coordinator.enqueueVerified(waiting);
		coordinator.enqueueVerified(ready);

		coordinator.tick(100L, Long.MAX_VALUE);
		coordinator.tick(150L, Long.MAX_VALUE);

		assertEquals(List.of(ready), applied);
		assertTrue(coordinator.hasPending(waiting.manifest().key()));
		assertEquals(1, coordinator.pendingCount());
	}

	@Test
	void keepsRefreshWaitExclusiveWithinRegion() {
		FakeAdapter adapter = new FakeAdapter();
		adapter.applyResult = XaeroMapAdapter.ApplyResult.RETRY_LATER;
		AtomicPatchCoordinator coordinator = new AtomicPatchCoordinator(adapter, ignored -> { }, ignored -> { });
		coordinator.enqueueVerified(patch(new MapPatchKey("minecraft:overworld", 0, 0)));
		coordinator.enqueueVerified(patch(new MapPatchKey("minecraft:overworld", 1, 0)));

		coordinator.tick(100L, Long.MAX_VALUE);
		coordinator.tick(150L, Long.MAX_VALUE);

		assertEquals(1, adapter.calls);
		assertEquals(2, coordinator.pendingCount());
		assertEquals(1, coordinator.statistics(150L).phaseCounts().get(AtomicPatchCoordinator.Phase.WAIT_REFRESH));
	}

	@Test
	void expiresQueuedLocalWaitWithoutBypassingRefreshExclusivity() {
		FakeAdapter adapter = new FakeAdapter();
		adapter.generatingTiles = tile -> tile.chunkX() >= 4;
		adapter.applyResult = XaeroMapAdapter.ApplyResult.RETRY_LATER;
		AtomicPatchCoordinator coordinator = new AtomicPatchCoordinator(adapter, ignored -> { }, ignored -> { });
		coordinator.enqueueVerified(patch(new MapPatchKey("minecraft:overworld", 1, 0)));
		coordinator.enqueueVerified(patch(new MapPatchKey("minecraft:overworld", 0, 0)));

		coordinator.tick(100L, Long.MAX_VALUE);
		coordinator.tick(150L, Long.MAX_VALUE);
		assertEquals(1, coordinator.statistics(150L).localWaiting());
		assertEquals(1, coordinator.statistics(150L).phaseCounts().get(AtomicPatchCoordinator.Phase.WAIT_REFRESH));

		coordinator.tick(2_100L, Long.MAX_VALUE);

		AtomicPatchCoordinator.Statistics statistics = coordinator.statistics(2_100L);
		assertEquals(0, statistics.localWaiting());
		assertEquals(1, statistics.phaseCounts().get(AtomicPatchCoordinator.Phase.PREPARED));
		assertEquals(1, statistics.phaseCounts().get(AtomicPatchCoordinator.Phase.WAIT_REFRESH));
		assertEquals(2, adapter.calls);
	}

	@Test
	void manifestPollingDoesNotWaitForCommitQueueToDrain() {
		FakeAdapter adapter = new FakeAdapter();
		AtomicPatchCoordinator coordinator = new AtomicPatchCoordinator(adapter, ignored -> { }, ignored -> { });
		coordinator.enqueueVerified(patch(new MapPatchKey("minecraft:overworld", 0, 0)));

		assertEquals(1, coordinator.pendingCount());
		assertTrue(AtomicMapSyncClient.shouldPollManifest(100, 0, 0));
		assertFalse(AtomicMapSyncClient.shouldPollManifest(100, 1, 0));
		assertFalse(AtomicMapSyncClient.shouldPollManifest(99, 0, 0));
	}

	@Test
	void pendingPatchIsNotDownloadedAgain() {
		assertFalse(AtomicMapSyncClient.shouldDownloadManifest(null, 7L, true, false, false));
		assertFalse(AtomicMapSyncClient.shouldDownloadManifest(7L, 7L, false, false, false));
		assertTrue(AtomicMapSyncClient.shouldDownloadManifest(null, Long.MIN_VALUE, false, false, false));
	}

	@Test
	void localGenerationRechecksUseBoundedBackoff() {
		FakeAdapter adapter = new FakeAdapter();
		adapter.generatingTiles = ignored -> true;
		AtomicPatchCoordinator coordinator = new AtomicPatchCoordinator(adapter, ignored -> { }, ignored -> { });
		coordinator.enqueueVerified(patch(new MapPatchKey("minecraft:overworld", 0, 0)));

		coordinator.tick(100L, Long.MAX_VALUE);
		coordinator.tick(199L, Long.MAX_VALUE);
		assertEquals(0L, coordinator.statistics(199L).localGenerationRechecks());
		coordinator.tick(200L, Long.MAX_VALUE);
		coordinator.tick(399L, Long.MAX_VALUE);
		assertEquals(1L, coordinator.statistics(399L).localGenerationRechecks());
		coordinator.tick(400L, Long.MAX_VALUE);
		coordinator.tick(799L, Long.MAX_VALUE);
		assertEquals(2L, coordinator.statistics(799L).localGenerationRechecks());
		coordinator.tick(800L, Long.MAX_VALUE);
		coordinator.tick(1_299L, Long.MAX_VALUE);
		assertEquals(3L, coordinator.statistics(1_299L).localGenerationRechecks());
		coordinator.tick(1_300L, Long.MAX_VALUE);
		assertEquals(4L, coordinator.statistics(1_300L).localGenerationRechecks());
		assertEquals(0, adapter.calls);
	}

	private static MapPatch patch(MapPatchKey key) {
		List<MapTile> tiles = new ArrayList<>();
		List<MapPatchManifest.TileReference> references = new ArrayList<>();
		for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) {
			long hash = dx * 4L + dz + 1L;
			int chunkX = key.minChunkX() + dx;
			int chunkZ = key.minChunkZ() + dz;
			tiles.add(tile(chunkX, chunkZ, hash));
			references.add(new MapPatchManifest.TileReference(chunkX, chunkZ, hash, hash));
		}
		return new MapPatch(new MapPatchManifest(key, 1L, 16L, references), tiles);
	}

	private static MapTile tile(int chunkX, int chunkZ, long hash) {
		int[] states = new int[256]; Arrays.fill(states, 1);
		int[] heights = new int[256]; Arrays.fill(heights, 64);
		String[] biomes = new String[256]; Arrays.fill(biomes, "minecraft:plains");
		return new MapTile("minecraft:overworld", chunkX, chunkZ, states, heights, heights, biomes,
				new byte[256], new boolean[256], new boolean[256], MapTile.emptyOverlays(256), hash);
	}

	private static final class FakeAdapter implements XaeroMapAdapter {
		private int calls;
		private int lastBatchSize;
		private List<MapTile> lastBatch = List.of();
		private Predicate<MapTile> generatingTiles = ignored -> false;
		private Predicate<MapTile> readyTiles = ignored -> false;
		private ApplyResult applyResult = ApplyResult.APPLIED;
		@Override public boolean isAvailable() { return true; }
		@Override public boolean apply(MapTile tile) { return true; }
		@Override public LocalTileState localTileState(MapTile tile) {
			return readyTiles.test(tile) ? LocalTileState.READY
					: generatingTiles.test(tile) ? LocalTileState.GENERATING : LocalTileState.REMOTE;
		}
		@Override public ApplyResult applyBatchResult(List<MapTile> tiles) {
			calls++;
			lastBatchSize = tiles.size();
			lastBatch = List.copyOf(tiles);
			return applyResult;
		}
	}
}
