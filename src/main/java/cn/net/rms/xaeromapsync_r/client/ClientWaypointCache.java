package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;

public final class ClientWaypointCache {
	private final Map<UUID, PublicWaypoint> waypoints = new LinkedHashMap<>();

	public synchronized void replace(Collection<PublicWaypoint> snapshot) {
		waypoints.clear();
		for (PublicWaypoint waypoint : snapshot) {
			waypoints.put(waypoint.id(), waypoint);
		}
	}

	public synchronized void upsert(PublicWaypoint waypoint) {
		waypoints.put(waypoint.id(), waypoint);
	}

	public synchronized void delete(PublicWaypoint waypoint) {
		waypoints.remove(waypoint.id());
	}

	public synchronized int activeCount() {
		int count = 0;
		for (PublicWaypoint waypoint : waypoints.values()) {
			if (!waypoint.deleted()) {
				count++;
			}
		}
		return count;
	}

	public synchronized List<PublicWaypoint> snapshot() {
		return List.copyOf(waypoints.values());
	}
}
