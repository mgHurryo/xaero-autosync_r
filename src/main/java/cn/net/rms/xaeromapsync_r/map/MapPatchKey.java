package cn.net.rms.xaeromapsync_r.map;

import java.util.Objects;

/** Identifies one Xaero MapTileChunk-aligned 4x4 Minecraft chunk patch. */
public record MapPatchKey(String dimension, int patchX, int patchZ) implements Comparable<MapPatchKey> {
	public static final int CHUNKS_PER_SIDE = 4;
	public static final int TILE_COUNT = CHUNKS_PER_SIDE * CHUNKS_PER_SIDE;
	public static final int XAERO_REGION_CHUNKS = 32;

	public MapPatchKey {
		if (dimension == null || dimension.isBlank()) {
			throw new IllegalArgumentException("Patch dimension is required");
		}
	}

	public static MapPatchKey fromChunk(String dimension, int chunkX, int chunkZ) {
		return new MapPatchKey(dimension, Math.floorDiv(chunkX, CHUNKS_PER_SIDE),
				Math.floorDiv(chunkZ, CHUNKS_PER_SIDE));
	}

	public int minChunkX() { return patchX * CHUNKS_PER_SIDE; }
	public int minChunkZ() { return patchZ * CHUNKS_PER_SIDE; }
	public int xaeroRegionX() { return Math.floorDiv(minChunkX(), XAERO_REGION_CHUNKS); }
	public int xaeroRegionZ() { return Math.floorDiv(minChunkZ(), XAERO_REGION_CHUNKS); }

	public boolean contains(int chunkX, int chunkZ) {
		return chunkX >= minChunkX() && chunkX < minChunkX() + CHUNKS_PER_SIDE
				&& chunkZ >= minChunkZ() && chunkZ < minChunkZ() + CHUNKS_PER_SIDE;
	}

	public String stableId() {
		return dimension + ":" + patchX + ":" + patchZ;
	}

	@Override
	public int compareTo(MapPatchKey other) {
		int dimensionOrder = dimension.compareTo(other.dimension);
		if (dimensionOrder != 0) return dimensionOrder;
		int xOrder = Integer.compare(patchX, other.patchX);
		return xOrder != 0 ? xOrder : Integer.compare(patchZ, other.patchZ);
	}
}
