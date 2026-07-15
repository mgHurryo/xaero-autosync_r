package cn.net.rms.xaeromapsync_r.map;

import java.util.Arrays;

public final class MapTile {
	private final String dimension;
	private final int chunkX;
	private final int chunkZ;
	private final int[] heights;
	private final int[] blockStateIds;
	private final int[] biomeIds;
	private final int[] lightLevels;
	private final long contentHash;

	public MapTile(String dimension, int chunkX, int chunkZ, int[] heights, long contentHash) {
		this(dimension, chunkX, chunkZ, heights, new int[heights.length], new int[heights.length], new int[heights.length], contentHash);
	}

	public MapTile(String dimension, int chunkX, int chunkZ, int[] heights, int[] blockStateIds, int[] biomeIds,
			int[] lightLevels, long contentHash) {
		if (heights.length != blockStateIds.length || heights.length != biomeIds.length || heights.length != lightLevels.length) {
			throw new IllegalArgumentException("Map tile surface arrays must have equal lengths");
		}
		this.dimension = dimension;
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		this.heights = Arrays.copyOf(heights, heights.length);
		this.blockStateIds = Arrays.copyOf(blockStateIds, blockStateIds.length);
		this.biomeIds = Arrays.copyOf(biomeIds, biomeIds.length);
		this.lightLevels = Arrays.copyOf(lightLevels, lightLevels.length);
		this.contentHash = contentHash;
	}

	public String dimension() {
		return dimension;
	}

	public int chunkX() {
		return chunkX;
	}

	public int chunkZ() {
		return chunkZ;
	}

	public int[] heights() {
		return Arrays.copyOf(heights, heights.length);
	}

	public int[] blockStateIds() { return Arrays.copyOf(blockStateIds, blockStateIds.length); }
	public int[] biomeIds() { return Arrays.copyOf(biomeIds, biomeIds.length); }
	public int[] lightLevels() { return Arrays.copyOf(lightLevels, lightLevels.length); }

	public long contentHash() {
		return contentHash;
	}
}
