package cn.net.rms.xaeromapsync_r.xaero;

import java.util.List;
import java.util.Map;

interface XaeroWaypointBridge {
	String PUBLIC_WAYPOINT_SET = "gui.xaero-mapsync_r.public_waypoints";

	Target currentTarget() throws ReflectiveOperationException;

	SelectedWaypoint selectedWaypoint(Object screen) throws ReflectiveOperationException;

	Object create(WaypointValues values) throws ReflectiveOperationException;

	WaypointValues read(Object waypoint) throws ReflectiveOperationException;

	void update(Object waypoint, WaypointValues values) throws ReflectiveOperationException;

	void save(Object world) throws ReflectiveOperationException;

	void clearWaypointScreenSelection() throws ReflectiveOperationException;

	void removeSet(Object world, String setKey) throws ReflectiveOperationException;

	final class Target {
		private final Object world;
		private final Map<String, List<Object>> waypointSets;
		private final boolean publicSetCreated;

		Target(Object world, Map<String, List<Object>> waypointSets, boolean publicSetCreated) {
			this.world = world;
			this.waypointSets = waypointSets;
			this.publicSetCreated = publicSetCreated;
		}

		Object world() {
			return world;
		}

		List<Object> waypoints() {
			return waypointSets.get(PUBLIC_WAYPOINT_SET);
		}

		Map<String, List<Object>> waypointSets() {
			return waypointSets;
		}

		boolean publicSetCreated() {
			return publicSetCreated;
		}
	}

	final class SelectedWaypoint {
		private final Object nativeWaypoint;
		private final Object world;
		private final WaypointValues values;
		private final String category;
		private final String dimension;

		SelectedWaypoint(Object nativeWaypoint, Object world, WaypointValues values, String category, String dimension) {
			this.nativeWaypoint = nativeWaypoint;
			this.world = world;
			this.values = values;
			this.category = category;
			this.dimension = dimension;
		}

		Object nativeWaypoint() {
			return nativeWaypoint;
		}

		Object world() {
			return world;
		}

		WaypointValues values() {
			return values;
		}

		String category() {
			return category;
		}

		String dimension() {
			return dimension;
		}
	}
}
