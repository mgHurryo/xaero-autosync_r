package cn.net.rms.xaeromapsync_r.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.nio.charset.StandardCharsets;

public final class MapTile {
	public static final int FORMAT_VERSION = 6;
	public static final int MAX_OVERLAYS_PER_COLUMN = 10;
	public static final int MAX_BIOME_KEY_BYTES = 128;

	private final String dimension;
	private final int chunkX;
	private final int chunkZ;
	private final int[] baseStateIds;
	private final int[] baseHeights;
	private final int[] topHeights;
	private final String[] biomeKeys;
	private final byte[] lightAbove;
	private final boolean[] glowing;
	private final boolean[] cave;
	private final List<List<Overlay>> overlays;
	private final long contentHash;

	public MapTile(String dimension, int chunkX, int chunkZ, int[] heights, long contentHash) {
		this(dimension, chunkX, chunkZ, new int[heights.length], heights, heights, defaultBiomeKeys(heights.length),
				new byte[heights.length], new boolean[heights.length], new boolean[heights.length], emptyOverlays(heights.length),
				contentHash);
	}

	public MapTile(String dimension, int chunkX, int chunkZ, int[] heights, int[] blockStateIds, int[] biomeIds,
			int[] lightLevels, long contentHash) {
		this(dimension, chunkX, chunkZ, blockStateIds, heights, heights, legacyBiomeKeys(biomeIds), toLights(lightLevels),
				new boolean[heights.length], new boolean[heights.length], emptyOverlays(heights.length), contentHash);
	}

	public MapTile(String dimension, int chunkX, int chunkZ, int[] baseStateIds, int[] baseHeights,
			int[] topHeights, int[] biomeIds, byte[] lightAbove, boolean[] glowing, boolean[] cave,
			List<List<Overlay>> overlays, long contentHash) {
		this(dimension, chunkX, chunkZ, baseStateIds, baseHeights, topHeights, legacyBiomeKeys(biomeIds), lightAbove,
				glowing, cave, overlays, contentHash);
	}

	public MapTile(String dimension, int chunkX, int chunkZ, int[] baseStateIds, int[] baseHeights,
			int[] topHeights, String[] biomeKeys, byte[] lightAbove, boolean[] glowing, boolean[] cave,
			List<List<Overlay>> overlays, long contentHash) {
		int columnCount = baseStateIds.length;
		if (baseHeights.length != columnCount || topHeights.length != columnCount || biomeKeys.length != columnCount
				|| lightAbove.length != columnCount || glowing.length != columnCount || cave.length != columnCount
				|| overlays.size() != columnCount) {
			throw new IllegalArgumentException("Map tile surface data must have equal column counts");
		}
		this.dimension = dimension;
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		this.baseStateIds = Arrays.copyOf(baseStateIds, columnCount);
		this.baseHeights = Arrays.copyOf(baseHeights, columnCount);
		this.topHeights = Arrays.copyOf(topHeights, columnCount);
		this.biomeKeys = Arrays.copyOf(biomeKeys, columnCount);
		this.lightAbove = Arrays.copyOf(lightAbove, columnCount);
		this.glowing = Arrays.copyOf(glowing, columnCount);
		this.cave = Arrays.copyOf(cave, columnCount);
		this.overlays = copyOverlays(overlays);
		this.contentHash = contentHash;
		validateColumns();
	}

	public String dimension() { return dimension; }
	public int chunkX() { return chunkX; }
	public int chunkZ() { return chunkZ; }
	public int[] baseStateIds() { return Arrays.copyOf(baseStateIds, baseStateIds.length); }
	public int[] baseHeights() { return Arrays.copyOf(baseHeights, baseHeights.length); }
	public int[] topHeights() { return Arrays.copyOf(topHeights, topHeights.length); }
	public String[] biomeKeys() { return Arrays.copyOf(biomeKeys, biomeKeys.length); }
	public byte[] lightAbove() { return Arrays.copyOf(lightAbove, lightAbove.length); }
	public boolean[] glowing() { return Arrays.copyOf(glowing, glowing.length); }
	public boolean[] cave() { return Arrays.copyOf(cave, cave.length); }
	public List<List<Overlay>> overlays() { return overlays; }
	public List<Overlay> overlaysAt(int column) { return overlays.get(column); }
	public long contentHash() { return contentHash; }

	public boolean hasRenderableSurface() {
		for (int stateId : baseStateIds) {
			if (stateId != 0) return true;
		}
		for (List<Overlay> column : overlays) {
			if (!column.isEmpty()) return true;
		}
		return false;
	}

	/** Compatibility alias for the pre-v2 surface model. */
	public int[] heights() { return baseHeights(); }

	/** Compatibility alias for the pre-v2 surface model. */
	public int[] blockStateIds() { return baseStateIds(); }

	/** Compatibility alias for the pre-v2 surface model. */
	public int[] lightLevels() {
		int[] values = new int[lightAbove.length];
		for (int index = 0; index < values.length; index++) values[index] = Byte.toUnsignedInt(lightAbove[index]);
		return values;
	}

	private void validateColumns() {
		for (int index = 0; index < baseHeights.length; index++) {
			if (biomeKeys[index] == null || biomeKeys[index].isBlank()) {
				throw new IllegalArgumentException("Biome key is missing at column " + index);
			}
			if (biomeKeys[index].getBytes(StandardCharsets.UTF_8).length > MAX_BIOME_KEY_BYTES) {
				throw new IllegalArgumentException("Biome key is too long at column " + index);
			}
			if (topHeights[index] < baseHeights[index]) {
				throw new IllegalArgumentException("Top height is below base height at column " + index);
			}
			int light = Byte.toUnsignedInt(lightAbove[index]);
			if (light > 15) throw new IllegalArgumentException("Invalid light level " + light + " at column " + index);
			if (overlays.get(index).size() > MAX_OVERLAYS_PER_COLUMN) {
				throw new IllegalArgumentException("Too many overlays at column " + index);
			}
		}
	}

	private static String[] defaultBiomeKeys(int count) {
		String[] keys = new String[count];
		Arrays.fill(keys, "minecraft:plains");
		return keys;
	}

	static String[] legacyBiomeKeys(int[] biomeIds) {
		// Legacy constructors do not carry registry keys. Synthetic keys preserve hash
		// distinctions for compatibility tests; v4 production tiles never serialize them.
		String[] keys = new String[biomeIds.length];
		for (int index = 0; index < biomeIds.length; index++) keys[index] = "legacy:" + biomeIds[index];
		return keys;
	}

	private static byte[] toLights(int[] values) {
		byte[] result = new byte[values.length];
		for (int index = 0; index < values.length; index++) {
			if (values[index] < 0 || values[index] > 15) {
				throw new IllegalArgumentException("Invalid light level " + values[index] + " at column " + index);
			}
			result[index] = (byte) values[index];
		}
		return result;
	}

	private static List<List<Overlay>> copyOverlays(List<List<Overlay>> source) {
		List<List<Overlay>> result = new ArrayList<>(source.size());
		for (List<Overlay> column : source) {
			if (column == null) throw new IllegalArgumentException("Overlay column is missing");
			result.add(List.copyOf(column));
		}
		return Collections.unmodifiableList(result);
	}

	public static List<List<Overlay>> emptyOverlays(int columnCount) {
		List<List<Overlay>> result = new ArrayList<>(columnCount);
		for (int index = 0; index < columnCount; index++) result.add(List.of());
		return Collections.unmodifiableList(result);
	}

	public record Overlay(int blockStateId, float transparency, byte lightAbove, boolean glowing, int opacity) {
		public Overlay(int blockStateId, float transparency, byte lightAbove, boolean glowing) {
			this(blockStateId, transparency, lightAbove, glowing, 0);
		}

		public Overlay {
			if (!Float.isFinite(transparency) || transparency < 0.0F || transparency > 1.0F) {
				throw new IllegalArgumentException("Invalid overlay transparency: " + transparency);
			}
			int light = Byte.toUnsignedInt(lightAbove);
			if (light > 15) throw new IllegalArgumentException("Invalid overlay light level: " + light);
			if (opacity < 0 || opacity > Short.MAX_VALUE) {
				throw new IllegalArgumentException("Invalid overlay opacity: " + opacity);
			}
		}
	}
}
