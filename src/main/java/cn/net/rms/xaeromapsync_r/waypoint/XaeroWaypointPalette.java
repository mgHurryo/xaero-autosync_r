package cn.net.rms.xaeromapsync_r.waypoint;

/** Xaero 22.11.1 stores a palette index, not an RGB value, in Waypoint.color. */
public final class XaeroWaypointPalette {
	public static final int MIN_INDEX = 0;
	public static final int MAX_INDEX = 15;
	private static final int[] RGB = {
			0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
			0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
			0xFF0000, 0xFF55FF, 0xFFFF55, 0xFFFFFF
	};

	private XaeroWaypointPalette() {
	}

	public static int normalize(int color) {
		if (color >= MIN_INDEX && color <= MAX_INDEX) {
			return color;
		}
		int rgb = color & 0xFFFFFF;
		int bestIndex = 0;
		long bestDistance = Long.MAX_VALUE;
		for (int index = 0; index < RGB.length; index++) {
			int candidate = RGB[index];
			int red = ((rgb >>> 16) & 0xFF) - ((candidate >>> 16) & 0xFF);
			int green = ((rgb >>> 8) & 0xFF) - ((candidate >>> 8) & 0xFF);
			int blue = (rgb & 0xFF) - (candidate & 0xFF);
			long distance = (long) red * red + (long) green * green + (long) blue * blue;
			if (distance < bestDistance) {
				bestDistance = distance;
				bestIndex = index;
			}
		}
		return bestIndex;
	}
}
