package cn.net.rms.xaeromapsync_r.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.function.LongSupplier;
import java.util.LinkedHashMap;

/** Coalesces index changes and partitions each Xaero region into maximal square patches. */
public final class MapPatchCatalog {
	static final long COALESCE_WINDOW_MILLIS = 2_000L;
	static final int MAX_SNAPSHOT_HISTORY = 128;
	// A tile payload is capped at 64 KiB. Eleven squared plus manifest and
	// per-tile framing remains below the fragmented transfer's 8 MiB ceiling.
	static final int MAX_TRANSFER_SAFE_SIDE = 11;
	private final MapTileIndexStore index;
	private final MapTileDataStore bodies;
	private final LongSupplier clock;
	private final Map<String, Snapshot> published = new HashMap<>();
	private final Map<String, Long> publishedRevision = new HashMap<>();
	private final Map<String, LinkedHashMap<Long, Snapshot>> history = new HashMap<>();
	private final Map<String, Long> pendingSince = new HashMap<>();

	public MapPatchCatalog(MapTileIndexStore index, MapTileDataStore bodies) {
		this(index, bodies, System::currentTimeMillis);
	}

	MapPatchCatalog(MapTileIndexStore index, MapTileDataStore bodies, LongSupplier clock) {
		this.index = index;
		this.bodies = bodies;
		this.clock = clock;
	}

	public List<MapPatchManifest> manifests(String dimension) {
		return snapshot(dimension).manifests();
	}

	public synchronized Snapshot snapshot(String dimension) {
		Candidate candidate = buildCandidate(dimension);
		Snapshot current = published.get(dimension);
		if (current == null) return publish(dimension, candidate);
		if (candidate.snapshot().epoch() == current.epoch()) {
			pendingSince.remove(dimension);
			return current;
		}
		long now = clock.getAsLong();
		long lastPublishedRevision = publishedRevision.getOrDefault(dimension, 0L);
		long firstUnpublishedAt = candidate.entries().stream()
				.filter(entry -> entry.revision() > lastPublishedRevision)
				.mapToLong(MapTileIndexEntry::updatedAtMillis).min().orElse(now);
		long started = pendingSince.computeIfAbsent(dimension, ignored -> Math.min(now, firstUnpublishedAt));
		if (now - started < COALESCE_WINDOW_MILLIS) return current;
		return publish(dimension, candidate);
	}

	/**
	 * Keeps a paginated client on its immutable catalog epoch when that epoch is
	 * still retained. Falling back to the current snapshot lets an expired client
	 * restart from cursor zero without holding history forever.
	 */
	public synchronized Snapshot snapshot(String dimension, long expectedEpoch) {
		if (expectedEpoch != 0L) {
			LinkedHashMap<Long, Snapshot> dimensionHistory = history.get(dimension);
			Snapshot retained = dimensionHistory == null ? null : dimensionHistory.get(expectedEpoch);
			if (retained != null) return retained;
		}
		return snapshot(dimension);
	}

	private Candidate buildCandidate(String dimension) {
		MapTileIndexStore.DimensionSnapshot indexSnapshot = index.dimensionSnapshot(dimension);
		Map<RegionCoordinate, List<MapTileIndexEntry>> grouped = new HashMap<>();
		for (MapTileIndexEntry entry : indexSnapshot.entries()) {
			RegionCoordinate region = new RegionCoordinate(Math.floorDiv(entry.chunkX(), MapPatchKey.XAERO_REGION_CHUNKS),
					Math.floorDiv(entry.chunkZ(), MapPatchKey.XAERO_REGION_CHUNKS));
			grouped.computeIfAbsent(region, ignored -> new ArrayList<>())
					.add(entry);
		}
		List<MapPatchManifest> result = new ArrayList<>();
		for (Map.Entry<RegionCoordinate, List<MapTileIndexEntry>> group : grouped.entrySet()) {
			for (Square square : maximalSquares(group.getKey(), group.getValue())) {
				MapPatchKey key = MapPatchKey.square(dimension, square.minChunkX(), square.minChunkZ(), square.sideLength());
				result.add(toManifest(key, indexSnapshot.epoch(), square.entries()));
			}
		}
		result.sort(java.util.Comparator.comparing(MapPatchManifest::key));
		long maxRevision = indexSnapshot.entries().stream().mapToLong(MapTileIndexEntry::revision).max().orElse(0L);
		return new Candidate(new Snapshot(indexSnapshot.epoch(), List.copyOf(result)), maxRevision,
				indexSnapshot.entries());
	}

	public synchronized Optional<MapPatchManifest> manifest(MapPatchKey key) {
		Snapshot current = published.get(key.dimension());
		if (current == null) current = snapshot(key.dimension());
		return current.manifests().stream().filter(item -> item.key().equals(key)).findFirst();
	}

	public synchronized Optional<MapPatchManifest> manifest(MapPatchKey key, long epoch) {
		LinkedHashMap<Long, Snapshot> dimensionHistory = history.get(key.dimension());
		Snapshot snapshot = dimensionHistory == null ? null : dimensionHistory.get(epoch);
		if (snapshot == null) return Optional.empty();
		return snapshot.manifests().stream().filter(item -> item.key().equals(key)).findFirst();
	}

	public Optional<MapPatch> load(MapPatchManifest manifest) {
		List<MapTile> tiles = new ArrayList<>(manifest.key().tileCount());
		for (MapPatchManifest.TileReference reference : manifest.tiles()) {
			MapTile tile = bodies.find(manifest.key().dimension(), reference.chunkX(), reference.chunkZ(),
					reference.contentHash()).orElse(null);
			if (tile == null || !tile.hasRenderableSurface()) return Optional.empty();
			tiles.add(tile);
		}
		return Optional.of(new MapPatch(manifest, tiles));
	}

	private static MapPatchManifest toManifest(MapPatchKey key, long epoch, Collection<MapTileIndexEntry> entries) {
		long revision = 0L;
		List<MapPatchManifest.TileReference> references = new ArrayList<>(key.tileCount());
		for (MapTileIndexEntry entry : entries) {
			revision = Math.max(revision, entry.revision());
			references.add(new MapPatchManifest.TileReference(entry.chunkX(), entry.chunkZ(), entry.revision(), entry.contentHash()));
		}
		return new MapPatchManifest(key, epoch, revision, references);
	}

	private Snapshot publish(String dimension, Candidate candidate) {
		Snapshot snapshot = candidate.snapshot();
		published.put(dimension, snapshot);
		publishedRevision.put(dimension, candidate.maxRevision());
		LinkedHashMap<Long, Snapshot> dimensionHistory = history.computeIfAbsent(dimension, ignored -> new LinkedHashMap<>());
		dimensionHistory.put(snapshot.epoch(), snapshot);
		// Clients intentionally drain small hole patches slowly. Keep enough immutable
		// epochs for those queued requests while newer upload waves are published.
		while (dimensionHistory.size() > MAX_SNAPSHOT_HISTORY)
			dimensionHistory.remove(dimensionHistory.keySet().iterator().next());
		pendingSince.remove(dimension);
		return snapshot;
	}

	static List<Square> maximalSquares(RegionCoordinate region, Collection<MapTileIndexEntry> source) {
		MapTileIndexEntry[][] remaining = new MapTileIndexEntry[MapPatchKey.XAERO_REGION_CHUNKS][MapPatchKey.XAERO_REGION_CHUNKS];
		int count = 0;
		for (MapTileIndexEntry entry : source) {
			int localX = Math.floorMod(entry.chunkX(), MapPatchKey.XAERO_REGION_CHUNKS);
			int localZ = Math.floorMod(entry.chunkZ(), MapPatchKey.XAERO_REGION_CHUNKS);
			if (remaining[localX][localZ] == null) count++;
			remaining[localX][localZ] = entry;
		}
		List<Square> squares = new ArrayList<>();
		while (count > 0) {
			int[][] sizes = new int[MapPatchKey.XAERO_REGION_CHUNKS + 1][MapPatchKey.XAERO_REGION_CHUNKS + 1];
			int bestSize = 0;
			int bestX = 0;
			int bestZ = 0;
			for (int localX = MapPatchKey.XAERO_REGION_CHUNKS - 1; localX >= 0; localX--) {
				for (int localZ = MapPatchKey.XAERO_REGION_CHUNKS - 1; localZ >= 0; localZ--) {
					if (remaining[localX][localZ] == null) continue;
					sizes[localX][localZ] = 1 + Math.min(sizes[localX + 1][localZ],
							Math.min(sizes[localX][localZ + 1], sizes[localX + 1][localZ + 1]));
					int candidateSize = Math.min(sizes[localX][localZ], MAX_TRANSFER_SAFE_SIDE);
					if (candidateSize > bestSize
							|| (candidateSize == bestSize
							&& (localX < bestX || (localX == bestX && localZ < bestZ)))) {
						bestSize = candidateSize;
						bestX = localX;
						bestZ = localZ;
					}
				}
			}
			List<MapTileIndexEntry> entries = new ArrayList<>(bestSize * bestSize);
			for (int dx = 0; dx < bestSize; dx++) for (int dz = 0; dz < bestSize; dz++) {
				entries.add(remaining[bestX + dx][bestZ + dz]);
				remaining[bestX + dx][bestZ + dz] = null;
				count--;
			}
			squares.add(new Square(region.regionX() * MapPatchKey.XAERO_REGION_CHUNKS + bestX,
					region.regionZ() * MapPatchKey.XAERO_REGION_CHUNKS + bestZ, bestSize, List.copyOf(entries)));
		}
		return List.copyOf(squares);
	}

	public record Snapshot(long epoch, List<MapPatchManifest> manifests) { }
	private record Candidate(Snapshot snapshot, long maxRevision, List<MapTileIndexEntry> entries) { }
	record RegionCoordinate(int regionX, int regionZ) { }
	record Square(int minChunkX, int minChunkZ, int sideLength, List<MapTileIndexEntry> entries) { }
}
