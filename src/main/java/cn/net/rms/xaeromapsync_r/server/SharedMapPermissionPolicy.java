package cn.net.rms.xaeromapsync_r.server;

import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import net.minecraft.server.level.ServerPlayer;

public final class SharedMapPermissionPolicy {
	public void validateCreate(ServerPlayer player, PublicWaypoint submitted) {
		if (!SharedMapConfig.allowPlayerWaypointUpload() && !player.hasPermissions(2)) {
			throw new IllegalArgumentException("Waypoint upload is disabled for players");
		}
		if (submitted.visibility() != WaypointVisibility.PUBLIC) {
			throw new IllegalArgumentException("Only explicitly public waypoints can be uploaded");
		}
		if (SharedMapServer.waypoints().activeCount() >= SharedMapConfig.maxPublicWaypoints()) {
			throw new IllegalArgumentException("Public waypoint limit reached");
		}
		if (SharedMapServer.waypoints().activeCount(player.getUUID()) >= SharedMapConfig.maxWaypointsPerPlayer()
				&& !player.hasPermissions(2)) {
			throw new IllegalArgumentException("Player waypoint limit reached");
		}
	}

	public boolean canMutate(ServerPlayer player, PublicWaypoint waypoint) {
		return player.hasPermissions(2) || player.getUUID().equals(waypoint.creatorId());
	}

	public void validateUpdate(PublicWaypoint submitted) {
		if (submitted.visibility() != WaypointVisibility.PUBLIC) {
			throw new IllegalArgumentException("Only public waypoints are supported");
		}
	}
}
