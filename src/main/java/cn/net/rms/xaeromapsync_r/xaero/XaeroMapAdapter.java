package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import java.util.List;

public interface XaeroMapAdapter {
	enum ApplyResult {
		APPLIED,
		RETRY_LATER,
		UNAVAILABLE
	}

	enum LocalTileState {
		REMOTE,
		GENERATING,
		READY
	}

	boolean isAvailable();

	boolean apply(MapTile tile);

	default LocalTileState localTileState(MapTile tile) {
		return localTileState(tile.dimension(), tile.chunkX(), tile.chunkZ());
	}

	default LocalTileState localTileState(String dimension, int chunkX, int chunkZ) {
		return LocalTileState.REMOTE;
	}

	default ApplyResult applyResult(MapTile tile) {
		if (apply(tile)) return ApplyResult.APPLIED;
		return isAvailable() ? ApplyResult.RETRY_LATER : ApplyResult.UNAVAILABLE;
	}

	default ApplyResult applyBatchResult(List<MapTile> tiles) {
		for (MapTile tile : tiles) {
			ApplyResult result = applyResult(tile);
			if (result != ApplyResult.APPLIED) return result;
		}
		return ApplyResult.APPLIED;
	}
}
