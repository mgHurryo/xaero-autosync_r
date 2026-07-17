package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.xaeromapsync_r.map.MapPatch;
import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.xaero.XaeroMapAdapter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
		adapter.generating = true;
		AtomicPatchCoordinator coordinator = new AtomicPatchCoordinator(adapter, ignored -> { }, ignored -> { });
		coordinator.enqueueVerified(patch(new MapPatchKey("minecraft:overworld", 0, 0)));
		coordinator.tick(100L, Long.MAX_VALUE);
		assertEquals(0, adapter.calls);

		adapter.generating = false;
		adapter.localReady = true;
		coordinator.tick(10_000L, Long.MAX_VALUE);
		assertEquals(0, adapter.calls);
		assertEquals(0, coordinator.pendingCount());
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
		private boolean generating;
		private boolean localReady;
		@Override public boolean isAvailable() { return true; }
		@Override public boolean apply(MapTile tile) { return true; }
		@Override public LocalTileState localTileState(MapTile tile) {
			return generating ? LocalTileState.GENERATING : localReady ? LocalTileState.READY : LocalTileState.REMOTE;
		}
		@Override public ApplyResult applyBatchResult(List<MapTile> tiles) {
			calls++;
			lastBatchSize = tiles.size();
			return ApplyResult.APPLIED;
		}
	}
}
