package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

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

	@Override
	public XaeroWaypointMutation prepareShare(Object screen, WaypointVisibility visibility,
			Collection<PublicWaypoint> knownWaypoints, UUID playerId, String playerName) {
		throw new IllegalStateException(reason);
	}

	@Override
	public PublicWaypoint prepareUnshare(Object screen, Collection<PublicWaypoint> knownWaypoints, UUID playerId) {
		throw new IllegalStateException(reason);
	}

	@Override
	public Optional<WaypointVisibility> selectedVisibility(Object screen,
			Collection<PublicWaypoint> knownWaypoints, UUID playerId) {
		return Optional.empty();
	}

	@Override
	public void clearPendingMutations() {
	}
}
