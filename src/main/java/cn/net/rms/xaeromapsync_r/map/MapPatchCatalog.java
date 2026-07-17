package cn.net.rms.xaeromapsync_r.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds complete patch manifests from the durable server tile index. */
public final class MapPatchCatalog {
	private final MapTileIndexStore index;
	private final MapTileDataStore bodies;

	public MapPatchCatalog(MapTileIndexStore index, MapTileDataStore bodies) {
		this.index = index;
		this.bodies = bodies;
	}

	public List<MapPatchManifest> manifests(String dimension) {
		return snapshot(dimension).manifests();
	}

	public Snapshot snapshot(String dimension) {
		MapTileIndexStore.DimensionSnapshot indexSnapshot = index.dimensionSnapshot(dimension);
		Map<MapPatchKey, List<MapTileIndexEntry>> grouped = new LinkedHashMap<>();
		for (MapTileIndexEntry entry : indexSnapshot.entries()) {
			grouped.computeIfAbsent(MapPatchKey.fromChunk(dimension, entry.chunkX(), entry.chunkZ()), ignored -> new ArrayList<>())
					.add(entry);
		}
		List<MapPatchManifest> result = new ArrayList<>();
		for (Map.Entry<MapPatchKey, List<MapTileIndexEntry>> group : grouped.entrySet()) {
			if (group.getValue().size() != MapPatchKey.TILE_COUNT) continue;
			result.add(toManifest(group.getKey(), indexSnapshot.epoch(), group.getValue()));
		}
		result.sort(java.util.Comparator.comparing(MapPatchManifest::key));
		return new Snapshot(indexSnapshot.epoch(), List.copyOf(result));
	}

	public Optional<MapPatchManifest> manifest(MapPatchKey key) {
		MapTileIndexStore.DimensionSnapshot indexSnapshot = index.dimensionSnapshot(key.dimension());
		List<MapTileIndexEntry> entries = new ArrayList<>(MapPatchKey.TILE_COUNT);
		for (MapTileIndexEntry entry : indexSnapshot.entries()) {
			if (key.contains(entry.chunkX(), entry.chunkZ())) entries.add(entry);
		}
		if (entries.size() != MapPatchKey.TILE_COUNT) return Optional.empty();
		return Optional.of(toManifest(key, indexSnapshot.epoch(), entries));
	}

	public Optional<MapPatch> load(MapPatchManifest manifest) {
		List<MapTile> tiles = new ArrayList<>(MapPatchKey.TILE_COUNT);
		for (MapPatchManifest.TileReference reference : manifest.tiles()) {
			MapTile tile = bodies.find(manifest.key().dimension(), reference.chunkX(), reference.chunkZ()).orElse(null);
			if (tile == null || tile.contentHash() != reference.contentHash() || !tile.hasRenderableSurface()) return Optional.empty();
			tiles.add(tile);
		}
		return Optional.of(new MapPatch(manifest, tiles));
	}

	private static MapPatchManifest toManifest(MapPatchKey key, long epoch, Collection<MapTileIndexEntry> entries) {
		long revision = 0L;
		List<MapPatchManifest.TileReference> references = new ArrayList<>(MapPatchKey.TILE_COUNT);
		for (MapTileIndexEntry entry : entries) {
			revision = Math.max(revision, entry.revision());
			references.add(new MapPatchManifest.TileReference(entry.chunkX(), entry.chunkZ(), entry.revision(), entry.contentHash()));
		}
		return new MapPatchManifest(key, epoch, revision, references);
	}

	public record Snapshot(long epoch, List<MapPatchManifest> manifests) { }
}
