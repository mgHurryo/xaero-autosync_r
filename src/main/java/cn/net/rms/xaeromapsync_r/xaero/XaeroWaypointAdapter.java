package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface XaeroWaypointAdapter {
	boolean isAvailable();

	XaeroWaypointReconcileResult reconcile(Collection<PublicWaypoint> waypoints);

	XaeroWaypointMutation prepareShare(Object screen, WaypointVisibility visibility,
			Collection<PublicWaypoint> knownWaypoints, UUID playerId, String playerName);

	PublicWaypoint prepareUnshare(Object screen, Collection<PublicWaypoint> knownWaypoints, UUID playerId);

	Optional<WaypointVisibility> selectedVisibility(Object screen, Collection<PublicWaypoint> knownWaypoints,
			UUID playerId);

	void clearPendingMutations();
}
