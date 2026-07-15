package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import java.util.Collection;

final class UnavailableXaeroWaypointAdapter implements XaeroWaypointAdapter {
	private final String reason;

	UnavailableXaeroWaypointAdapter(String reason) {
		this.reason = reason;
	}

	@Override
	public boolean isAvailable() {
		return false;
	}

	@Override
	public XaeroWaypointReconcileResult reconcile(Collection<PublicWaypoint> waypoints) {
		return XaeroWaypointReconcileResult.unavailable(reason);
	}
}
