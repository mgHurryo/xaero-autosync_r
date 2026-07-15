package cn.net.rms.xaeromapsync_r.xaero;

import java.util.List;

interface XaeroWaypointBridge {
	Target currentTarget() throws ReflectiveOperationException;

	Object create(WaypointValues values) throws ReflectiveOperationException;

	WaypointValues read(Object waypoint) throws ReflectiveOperationException;

	void update(Object waypoint, WaypointValues values) throws ReflectiveOperationException;

	void save(Object world) throws ReflectiveOperationException;

	final class Target {
		private final Object world;
		private final List<Object> waypoints;

		Target(Object world, List<Object> waypoints) {
			this.world = world;
			this.waypoints = waypoints;
		}

		Object world() {
			return world;
		}

		List<Object> waypoints() {
			return waypoints;
		}
	}
}
