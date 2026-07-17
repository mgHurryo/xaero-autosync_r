package cn.net.rms.xaeromapsync_r.server;

import cn.net.rms.xaeromapsync_r.map.MapTileIndexStore;
import cn.net.rms.xaeromapsync_r.server.dirty.DirtyChunkStore;
import cn.net.rms.xaeromapsync_r.server.exploration.ExploredChunkStore.ExploredChunk;

final class StartupMapWorkPlanner {
	private StartupMapWorkPlanner() {
	}

	static int queueMissingExploredChunks(Iterable<ExploredChunk> exploredChunks,
			MapTileIndexStore mapTiles, DirtyChunkStore dirtyChunks) {
		int queued = 0;
		for (ExploredChunk chunk : exploredChunks) {
			if (mapTiles.find(chunk.dimension(), chunk.chunkX(), chunk.chunkZ()).isEmpty()
					&& dirtyChunks.markDiscovered(chunk.dimension(), chunk.chunkX(), chunk.chunkZ())) {
				queued++;
			}
		}
		return queued;
	}
}
