package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;

public final class XaeroWaypointMutation {
	private final PublicWaypoint waypoint;
	private final boolean update;

	XaeroWaypointMutation(PublicWaypoint waypoint, boolean update) {
		this.waypoint = waypoint;
		this.update = update;
	}

	public PublicWaypoint waypoint() {
		return waypoint;
	}

	public boolean update() {
		return update;
	}
}
