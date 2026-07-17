package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;

/** Detects isolated missing or stale tiles inside recently received map regions. */
final class MapGapDetector {
	static final long DETECTION_DELAY_MILLIS = 30_000L;
	private static final long REQUEST_COOLDOWN_MILLIS = 300_000L;
	private static final int MIN_PRESENT_NEIGHBORS = 6;
	private static final int MAX_TRACKED_REGIONS = 128;
	private static final int MAX_REQUESTS_PER_SCAN = 32;
	private final Map<RegionKey, Window> windows = new LinkedHashMap<>();
	private final Map<String, Long> requestedAt = new LinkedHashMap<>();

	interface RevisionLookup {
		long appliedRevision(String dimension, int chunkX, int chunkZ);
	}

	void record(MapPatchManifest manifest, long nowMillis) {
		RegionKey key = new RegionKey(manifest.key().dimension(), manifest.key().xaeroRegionX(),
				manifest.key().xaeroRegionZ());
		Window window = windows.computeIfAbsent(key, ignored -> new Window(nowMillis + DETECTION_DELAY_MILLIS));
		window.dueAtMillis = Math.max(window.dueAtMillis, nowMillis + DETECTION_DELAY_MILLIS);
		for (MapPatchManifest.TileReference tile : manifest.tiles()) window.add(tile);
		while (windows.size() > MAX_TRACKED_REGIONS) windows.remove(windows.keySet().iterator().next());
	}

	List<MapTileIndexEntry> poll(long nowMillis, int regionBudget, RevisionLookup revisions) {
		if (regionBudget <= 0) return List.of();
		pruneCooldown(nowMillis);
		List<MapTileIndexEntry> result = new ArrayList<>();
		Iterator<Map.Entry<RegionKey, Window>> iterator = windows.entrySet().iterator();
		int scanned = 0;
		while (iterator.hasNext() && scanned < regionBudget && result.size() < MAX_REQUESTS_PER_SCAN) {
			Map.Entry<RegionKey, Window> entry = iterator.next();
			if (nowMillis < entry.getValue().dueAtMillis) continue;
			iterator.remove();
			scanned++;
			detect(entry.getKey(), entry.getValue(), nowMillis, revisions, result);
		}
		return List.copyOf(result);
	}

	void clear() {
		windows.clear();
		requestedAt.clear();
	}

	private void detect(RegionKey region, Window window, long nowMillis, RevisionLookup revisions,
			List<MapTileIndexEntry> result) {
		if (window.tiles.isEmpty()) return;
		for (int chunkX = window.minX; chunkX <= window.maxX && result.size() < MAX_REQUESTS_PER_SCAN; chunkX++) {
			for (int chunkZ = window.minZ; chunkZ <= window.maxZ && result.size() < MAX_REQUESTS_PER_SCAN; chunkZ++) {
				long coordinate = ChunkPos.asLong(chunkX, chunkZ);
				MapPatchManifest.TileReference expected = window.tiles.get(coordinate);
				long applied = revisions.appliedRevision(region.dimension, chunkX, chunkZ);
				if (expected == null ? applied >= 0L : applied >= expected.revision()) continue;
				if (presentNeighbors(region.dimension, chunkX, chunkZ, window, revisions) < MIN_PRESENT_NEIGHBORS)
					continue;
				String requestKey = region.dimension + ":" + coordinate;
				Long previousRequest = requestedAt.get(requestKey);
				if (previousRequest != null && nowMillis - previousRequest < REQUEST_COOLDOWN_MILLIS) continue;
				requestedAt.put(requestKey, nowMillis);
				result.add(new MapTileIndexEntry(region.dimension, chunkX, chunkZ,
						expected == null ? 0L : expected.contentHash(),
						expected == null ? 0L : expected.revision(), nowMillis));
			}
		}
	}

	private static int presentNeighbors(String dimension, int chunkX, int chunkZ, Window window,
			RevisionLookup revisions) {
		int present = 0;
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			if (dx == 0 && dz == 0) continue;
			int neighborX = chunkX + dx;
			int neighborZ = chunkZ + dz;
			MapPatchManifest.TileReference expected = window.tiles.get(ChunkPos.asLong(neighborX, neighborZ));
			long applied = revisions.appliedRevision(dimension, neighborX, neighborZ);
			if (expected == null ? applied >= 0L : applied >= expected.revision()) present++;
		}
		return present;
	}

	private void pruneCooldown(long nowMillis) {
		requestedAt.entrySet().removeIf(entry -> nowMillis - entry.getValue() >= REQUEST_COOLDOWN_MILLIS);
	}

	private record RegionKey(String dimension, int regionX, int regionZ) { }

	private static final class Window {
		private long dueAtMillis;
		private final Map<Long, MapPatchManifest.TileReference> tiles = new HashMap<>();
		private int minX = Integer.MAX_VALUE;
		private int maxX = Integer.MIN_VALUE;
		private int minZ = Integer.MAX_VALUE;
		private int maxZ = Integer.MIN_VALUE;

		private Window(long dueAtMillis) { this.dueAtMillis = dueAtMillis; }

		private void add(MapPatchManifest.TileReference tile) {
			tiles.merge(ChunkPos.asLong(tile.chunkX(), tile.chunkZ()), tile,
					(left, right) -> left.revision() >= right.revision() ? left : right);
			minX = Math.min(minX, tile.chunkX());
			maxX = Math.max(maxX, tile.chunkX());
			minZ = Math.min(minZ, tile.chunkZ());
			maxZ = Math.max(maxZ, tile.chunkZ());
		}
	}
}
