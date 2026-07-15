package cn.net.rms.xaeromapsync_r.map;

public final class MapTileHasher {
	private static final long FNV_OFFSET = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;

	private MapTileHasher() {
	}

	public static long hashHeights(int[] heights) {
		long hash = FNV_OFFSET;
		for (int height : heights) {
			hash ^= height;
			hash *= FNV_PRIME;
		}
		return hash;
	}

	public static long hashSurface(int[] heights, int[] blockStateIds, int[] biomeIds, int[] lightLevels) {
		return combine(hashHeights(heights), hashHeights(blockStateIds), hashHeights(biomeIds), hashHeights(lightLevels));
	}

	public static long hashString(String value) {
		long hash = FNV_OFFSET;
		for (int index = 0; index < value.length(); index++) {
			hash ^= value.charAt(index);
			hash *= FNV_PRIME;
		}
		return hash;
	}

	public static long combine(long... childHashes) {
		long hash = FNV_OFFSET;
		for (long childHash : childHashes) {
			hash ^= childHash;
			hash *= FNV_PRIME;
		}
		return hash;
	}
}
