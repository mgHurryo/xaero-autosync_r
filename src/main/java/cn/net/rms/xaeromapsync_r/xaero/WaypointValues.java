package cn.net.rms.xaeromapsync_r.xaero;

import java.util.Objects;

final class WaypointValues {
	final int x;
	final int y;
	final int z;
	final String name;
	final String symbol;
	final int color;

	WaypointValues(int x, int y, int z, String name, String symbol, int color) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.name = name;
		this.symbol = symbol;
		this.color = color;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof WaypointValues)) {
			return false;
		}
		WaypointValues that = (WaypointValues) other;
		return x == that.x && y == that.y && z == that.z && color == that.color && name.equals(that.name) && symbol.equals(that.symbol);
	}

	@Override
	public int hashCode() {
		return Objects.hash(x, y, z, name, symbol, color);
	}
}
