package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import java.util.List;
import java.util.Optional;

public interface XaeroMapAdapter {
	record LocalRegion(int regionX, int regionZ) { }

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

	default Optional<MapTile> localTile(String dimension, int chunkX, int chunkZ) {
		return Optional.empty();
	}

	default List<LocalRegion> knownLocalRegions(String dimension) {
		return List.of();
	}

	/** Requests native loading for a detected region and reports whether it is ready to read. */
	default boolean prepareLocalRegion(String dimension, LocalRegion region) {
		return false;
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
