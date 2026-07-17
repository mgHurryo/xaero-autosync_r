package cn.net.rms.xaeromapsync_r.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexStore;
import cn.net.rms.xaeromapsync_r.server.dirty.DirtyChunkStore;
import cn.net.rms.xaeromapsync_r.server.exploration.ExploredChunkStore;
import org.junit.jupiter.api.Test;

final class StartupMapWorkPlannerTest {
	@Test
	void queuesOnlyMissingExploredChunksAndIsIdempotent() {
		ExploredChunkStore explored = new ExploredChunkStore();
		explored.markExplored("minecraft:overworld", 1, 2);
		explored.markExplored("minecraft:overworld", 3, 4);
		explored.markExplored("minecraft:overworld", 5, 6);
		MapTileIndexStore index = new MapTileIndexStore();
		index.upsert(new MapTile("minecraft:overworld", 1, 2, new int[] {64}, 10L));
		DirtyChunkStore dirty = new DirtyChunkStore();
		dirty.markDiscovered("minecraft:overworld", 3, 4);

		int queued = StartupMapWorkPlanner.queueMissingExploredChunks(explored.snapshot(), index, dirty);

		assertEquals(1, queued);
		assertEquals(2, dirty.totalCount());
		assertEquals(0, StartupMapWorkPlanner.queueMissingExploredChunks(explored.snapshot(), index, dirty));
		assertEquals(2, dirty.totalCount());
	}
}
