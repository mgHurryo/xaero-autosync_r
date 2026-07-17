package cn.net.rms.xaeromapsync_r.xaero;

import java.util.Arrays;

/** Published Fabric compatibility boundaries for Minecraft 1.17.1. */
public final class XaeroCompatibility {
	public static final String WORLD_MAP_MIN_VERSION = "1.14.5.2";
	public static final String WORLD_MAP_MAX_VERSION = "1.37.8";
	public static final String MINIMAP_MIN_VERSION = "21.12.5.1";
	public static final String MINIMAP_MAX_VERSION = "23.9.7";

	private XaeroCompatibility() {
	}

	public static boolean supportsWorldMap(String version) {
		return isInRange(version, WORLD_MAP_MIN_VERSION, WORLD_MAP_MAX_VERSION);
	}

	public static boolean supportsMinimap(String version) {
		return isInRange(version, MINIMAP_MIN_VERSION, MINIMAP_MAX_VERSION);
	}

	public static String worldMapRange() {
		return WORLD_MAP_MIN_VERSION + ".." + WORLD_MAP_MAX_VERSION;
	}

	public static String minimapRange() {
		return MINIMAP_MIN_VERSION + ".." + MINIMAP_MAX_VERSION;
	}

	static boolean isInRange(String version, String minimum, String maximum) {
		int[] candidate = parseNumericVersion(version);
		return candidate != null && compare(candidate, parseNumericVersion(minimum)) >= 0
				&& compare(candidate, parseNumericVersion(maximum)) <= 0;
	}

	private static int[] parseNumericVersion(String version) {
		if (version == null || version.isBlank()) return null;
		String[] segments = version.split("\\.", -1);
		int[] parsed = new int[segments.length];
		try {
			for (int index = 0; index < segments.length; index++) {
				if (segments[index].isEmpty()) return null;
				parsed[index] = Integer.parseInt(segments[index]);
				if (parsed[index] < 0) return null;
			}
			return parsed;
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static int compare(int[] left, int[] right) {
		int size = Math.max(left.length, right.length);
		int[] paddedLeft = Arrays.copyOf(left, size);
		int[] paddedRight = Arrays.copyOf(right, size);
		for (int index = 0; index < size; index++) {
			int comparison = Integer.compare(paddedLeft[index], paddedRight[index]);
			if (comparison != 0) return comparison;
		}
		return 0;
	}
}
