package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import java.util.Collection;

public interface XaeroWaypointAdapter {
	boolean isAvailable();

	XaeroWaypointReconcileResult reconcile(Collection<PublicWaypoint> waypoints);
}
