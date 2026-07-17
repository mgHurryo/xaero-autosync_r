package cn.net.rms.xaeromapsync_r.server.activity;

import java.util.Objects;

/** Identifies an 8x8 chunk region within a dimension. */
public record RegionKey(String dimension, int regionX, int regionZ) {
	public static final int REGION_SIZE_CHUNKS = 8;
	public static final int REGION_SIZE_BLOCKS = REGION_SIZE_CHUNKS * 16;

	public RegionKey {
		Objects.requireNonNull(dimension, "dimension");
		if (dimension.isBlank()) {
			throw new IllegalArgumentException("Dimension must not be blank");
		}
	}

	public static RegionKey fromChunk(String dimension, int chunkX, int chunkZ) {
		return new RegionKey(
				dimension,
				Math.floorDiv(chunkX, REGION_SIZE_CHUNKS),
				Math.floorDiv(chunkZ, REGION_SIZE_CHUNKS));
	}

	public static RegionKey fromBlock(String dimension, int blockX, int blockZ) {
		return new RegionKey(
				dimension,
				Math.floorDiv(blockX, REGION_SIZE_BLOCKS),
				Math.floorDiv(blockZ, REGION_SIZE_BLOCKS));
	}
}
