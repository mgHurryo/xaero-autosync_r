package cn.net.rms.xaeromapsync_r.map;

import java.util.Objects;

/** Identifies one square patch contained by a single Xaero 32x32 chunk region. */
public final class MapPatchKey implements Comparable<MapPatchKey> {
	public static final int LEGACY_CHUNKS_PER_SIDE = 4;
	public static final int CHUNKS_PER_SIDE = LEGACY_CHUNKS_PER_SIDE;
	public static final int TILE_COUNT = LEGACY_CHUNKS_PER_SIDE * LEGACY_CHUNKS_PER_SIDE;
	public static final int MAX_CHUNKS_PER_SIDE = 32;
	public static final int MAX_TILE_COUNT = MAX_CHUNKS_PER_SIDE * MAX_CHUNKS_PER_SIDE;
	public static final int XAERO_REGION_CHUNKS = 32;

	private final String dimension;
	private final int minChunkX;
	private final int minChunkZ;
	private final int sideLength;

	/** Compatibility constructor for the former fixed 4x4 patch grid. */
	public MapPatchKey(String dimension, int patchX, int patchZ) {
		this(dimension, patchX * LEGACY_CHUNKS_PER_SIDE, patchZ * LEGACY_CHUNKS_PER_SIDE,
				LEGACY_CHUNKS_PER_SIDE);
	}

	private MapPatchKey(String dimension, int minChunkX, int minChunkZ, int sideLength) {
		if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Patch dimension is required");
		if (sideLength < 1 || sideLength > MAX_CHUNKS_PER_SIDE)
			throw new IllegalArgumentException("Patch side length must be between 1 and 32");
		int regionX = Math.floorDiv(minChunkX, XAERO_REGION_CHUNKS);
		int regionZ = Math.floorDiv(minChunkZ, XAERO_REGION_CHUNKS);
		if (Math.floorDiv(minChunkX + sideLength - 1, XAERO_REGION_CHUNKS) != regionX
				|| Math.floorDiv(minChunkZ + sideLength - 1, XAERO_REGION_CHUNKS) != regionZ) {
			throw new IllegalArgumentException("Square patch cannot cross a Xaero region boundary");
		}
		this.dimension = dimension;
		this.minChunkX = minChunkX;
		this.minChunkZ = minChunkZ;
		this.sideLength = sideLength;
	}

	public static MapPatchKey square(String dimension, int minChunkX, int minChunkZ, int sideLength) {
		return new MapPatchKey(dimension, minChunkX, minChunkZ, sideLength);
	}

	public static MapPatchKey fromChunk(String dimension, int chunkX, int chunkZ) {
		return new MapPatchKey(dimension, Math.floorDiv(chunkX, LEGACY_CHUNKS_PER_SIDE),
				Math.floorDiv(chunkZ, LEGACY_CHUNKS_PER_SIDE));
	}

	public String dimension() { return dimension; }
	public int minChunkX() { return minChunkX; }
	public int minChunkZ() { return minChunkZ; }
	public int sideLength() { return sideLength; }
	public int tileCount() { return sideLength * sideLength; }
	public int patchX() { return Math.floorDiv(minChunkX, LEGACY_CHUNKS_PER_SIDE); }
	public int patchZ() { return Math.floorDiv(minChunkZ, LEGACY_CHUNKS_PER_SIDE); }
	public int xaeroRegionX() { return Math.floorDiv(minChunkX, XAERO_REGION_CHUNKS); }
	public int xaeroRegionZ() { return Math.floorDiv(minChunkZ, XAERO_REGION_CHUNKS); }

	public boolean contains(int chunkX, int chunkZ) {
		return chunkX >= minChunkX && chunkX < minChunkX + sideLength
				&& chunkZ >= minChunkZ && chunkZ < minChunkZ + sideLength;
	}

	public String stableId() {
		return dimension + ":" + minChunkX + ":" + minChunkZ + ":" + sideLength;
	}

	@Override
	public int compareTo(MapPatchKey other) {
		int dimensionOrder = dimension.compareTo(other.dimension);
		if (dimensionOrder != 0) return dimensionOrder;
		int xOrder = Integer.compare(minChunkX, other.minChunkX);
		if (xOrder != 0) return xOrder;
		int zOrder = Integer.compare(minChunkZ, other.minChunkZ);
		return zOrder != 0 ? zOrder : Integer.compare(sideLength, other.sideLength);
	}

	@Override public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof MapPatchKey key)) return false;
		return minChunkX == key.minChunkX && minChunkZ == key.minChunkZ && sideLength == key.sideLength
				&& dimension.equals(key.dimension);
	}

	@Override public int hashCode() { return Objects.hash(dimension, minChunkX, minChunkZ, sideLength); }
	@Override public String toString() { return stableId(); }
}
