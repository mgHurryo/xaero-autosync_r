package cn.net.rms.xaeromapsync_r.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fully verified body for an atomic patch. Partial patches cannot be constructed. */
public final class MapPatch {
	private final MapPatchManifest manifest;
	private final List<MapTile> tiles;

	public MapPatch(MapPatchManifest manifest, List<MapTile> tiles) {
		if (manifest == null) throw new IllegalArgumentException("Patch manifest is required");
		if (tiles == null || tiles.size() != MapPatchKey.TILE_COUNT) {
			throw new IllegalArgumentException("Atomic patch body requires exactly " + MapPatchKey.TILE_COUNT + " tiles");
		}
		Map<Long, MapTile> byCoordinate = new HashMap<>();
		for (MapTile tile : tiles) {
			if (tile == null || !manifest.key().dimension().equals(tile.dimension())
					|| !manifest.key().contains(tile.chunkX(), tile.chunkZ()) || !tile.hasRenderableSurface()) {
				throw new IllegalArgumentException("Patch contains an invalid or unrenderable tile body");
			}
			long coordinate = coordinate(tile.chunkX(), tile.chunkZ());
			if (byCoordinate.put(coordinate, tile) != null) throw new IllegalArgumentException("Patch contains a duplicate tile body");
		}
		for (MapPatchManifest.TileReference reference : manifest.tiles()) {
			MapTile tile = byCoordinate.get(coordinate(reference.chunkX(), reference.chunkZ()));
			if (tile == null || tile.contentHash() != reference.contentHash()) {
				throw new IllegalArgumentException("Patch tile body does not match its manifest");
			}
		}
		List<MapTile> ordered = new ArrayList<>(tiles);
		ordered.sort(Comparator.comparingInt(MapTile::chunkX).thenComparingInt(MapTile::chunkZ));
		this.manifest = manifest;
		this.tiles = List.copyOf(ordered);
	}

	public MapPatchManifest manifest() { return manifest; }
	public List<MapTile> tiles() { return tiles; }

	private static long coordinate(int chunkX, int chunkZ) {
		return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
	}
}
