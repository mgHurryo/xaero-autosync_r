package cn.net.rms.xaeromapsync_r.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable declaration of every tile required for one atomic visual patch. */
public final class MapPatchManifest {
	private final MapPatchKey key;
	private final long epoch;
	private final long revision;
	private final long contentHash;
	private final List<TileReference> tiles;

	public MapPatchManifest(MapPatchKey key, long epoch, long revision, List<TileReference> tiles) {
		if (key == null) throw new IllegalArgumentException("Patch key is required");
		if (epoch < 0L || revision < 0L) throw new IllegalArgumentException("Patch epoch and revision must be non-negative");
		List<TileReference> ordered = new ArrayList<>(tiles == null ? List.of() : tiles);
		ordered.sort(Comparator.comparingInt(TileReference::chunkX).thenComparingInt(TileReference::chunkZ));
		validateComplete(key, ordered);
		this.key = key;
		this.epoch = epoch;
		this.revision = revision;
		this.tiles = List.copyOf(ordered);
		// The catalog epoch is a paging-consistency token, not patch content. Including
		// it here would invalidate and re-download every patch when one unrelated tile changes.
		long[] hashes = new long[1 + ordered.size() * 3];
		hashes[0] = revision;
		int index = 1;
		for (TileReference tile : ordered) {
			hashes[index++] = (((long) tile.chunkX()) << 32) ^ (tile.chunkZ() & 0xffffffffL);
			hashes[index++] = tile.revision();
			hashes[index++] = tile.contentHash();
		}
		this.contentHash = MapTileHasher.combine(hashes);
	}

	public MapPatchKey key() { return key; }
	public long epoch() { return epoch; }
	public long revision() { return revision; }
	public long contentHash() { return contentHash; }
	public List<TileReference> tiles() { return tiles; }

	private static void validateComplete(MapPatchKey key, List<TileReference> tiles) {
		if (tiles.size() != MapPatchKey.TILE_COUNT) {
			throw new IllegalArgumentException("Atomic patch requires exactly " + MapPatchKey.TILE_COUNT + " tiles");
		}
		Set<Long> coordinates = new HashSet<>();
		for (TileReference tile : tiles) {
			if (tile == null || !key.contains(tile.chunkX(), tile.chunkZ())) {
				throw new IllegalArgumentException("Patch contains an out-of-range tile");
			}
			long coordinate = (((long) tile.chunkX()) << 32) ^ (tile.chunkZ() & 0xffffffffL);
			if (!coordinates.add(coordinate)) throw new IllegalArgumentException("Patch contains a duplicate tile");
		}
		for (int dx = 0; dx < MapPatchKey.CHUNKS_PER_SIDE; dx++) {
			for (int dz = 0; dz < MapPatchKey.CHUNKS_PER_SIDE; dz++) {
				long expected = (((long) (key.minChunkX() + dx)) << 32)
						^ ((key.minChunkZ() + dz) & 0xffffffffL);
				if (!coordinates.contains(expected)) throw new IllegalArgumentException("Patch tile grid is incomplete");
			}
		}
	}

	public record TileReference(int chunkX, int chunkZ, long revision, long contentHash) {
		public TileReference {
			if (revision < 0L) throw new IllegalArgumentException("Tile revision must be non-negative");
		}
	}
}
