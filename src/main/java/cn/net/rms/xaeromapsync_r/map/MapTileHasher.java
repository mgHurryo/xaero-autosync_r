package cn.net.rms.xaeromapsync_r.map;

import java.util.List;

public final class MapTileHasher {
	private static final long FNV_OFFSET = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;

	private MapTileHasher() {
	}

	public static long hashHeights(int[] heights) {
		long hash = FNV_OFFSET;
		for (int height : heights) hash = append(hash, height);
		return hash;
	}

	/** Compatibility hash for legacy callers; versioned tiles use {@link #hashSurface(MapTile)}. */
	public static long hashSurface(int[] heights, int[] blockStateIds, int[] biomeIds, int[] lightLevels) {
		return combine(hashHeights(heights), hashHeights(blockStateIds), hashHeights(biomeIds), hashHeights(lightLevels));
	}

	public static long hashSurface(MapTile tile) {
		return hashSurface(tile.baseStateIds(), tile.baseHeights(), tile.topHeights(), tile.biomeKeys(), tile.lightAbove(),
				tile.glowing(), tile.cave(), tile.overlays());
	}

	public static long hashSurface(int[] baseStateIds, int[] baseHeights, int[] topHeights, String[] biomeKeys,
			byte[] lightAbove, boolean[] glowing, boolean[] cave, List<List<MapTile.Overlay>> overlays) {
		long hash = append(FNV_OFFSET, MapTile.FORMAT_VERSION);
		hash = append(hash, baseStateIds.length);
		for (int value : baseStateIds) hash = append(hash, value);
		for (int value : baseHeights) hash = append(hash, value);
		for (int value : topHeights) hash = append(hash, value);
		for (String value : biomeKeys) hash = append(hash, hashString(value));
		for (byte value : lightAbove) hash = append(hash, Byte.toUnsignedInt(value));
		for (boolean value : glowing) hash = append(hash, value ? 1 : 0);
		for (boolean value : cave) hash = append(hash, value ? 1 : 0);
		for (List<MapTile.Overlay> column : overlays) {
			hash = append(hash, column.size());
			for (MapTile.Overlay overlay : column) {
				hash = append(hash, overlay.blockStateId());
				hash = append(hash, Float.floatToIntBits(overlay.transparency()));
				hash = append(hash, Byte.toUnsignedInt(overlay.lightAbove()));
				hash = append(hash, overlay.glowing() ? 1 : 0);
				hash = append(hash, overlay.opacity());
			}
		}
		return hash;
	}

	public static long hashSurface(int[] baseStateIds, int[] baseHeights, int[] topHeights, int[] biomeIds,
			byte[] lightAbove, boolean[] glowing, boolean[] cave, List<List<MapTile.Overlay>> overlays) {
		return hashSurface(baseStateIds, baseHeights, topHeights, MapTile.legacyBiomeKeys(biomeIds), lightAbove,
				glowing, cave, overlays);
	}

	public static long hashString(String value) {
		long hash = FNV_OFFSET;
		for (int index = 0; index < value.length(); index++) hash = append(hash, value.charAt(index));
		return hash;
	}

	public static long combine(long... childHashes) {
		long hash = FNV_OFFSET;
		for (long childHash : childHashes) hash = append(hash, childHash);
		return hash;
	}

	private static long append(long hash, long value) {
		return (hash ^ value) * FNV_PRIME;
	}
}
