package cn.net.rms.xaeromapsync_r.server;

import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.server.access.RegionAccessDecision;
import cn.net.rms.xaeromapsync_r.server.access.RegionAccessStore;
import cn.net.rms.xaeromapsync_r.server.access.SharedMapActor;
import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import cn.net.rms.xaeromapsync_r.waypoint.XaeroWaypointPalette;
import java.util.Objects;

public final class SharedMapPermissionPolicy {
	private final RegionAccessStore regionAccess;
	private final Limits limits;

	public SharedMapPermissionPolicy(RegionAccessStore regionAccess) {
		this(regionAccess, new ConfigLimits());
	}

	public SharedMapPermissionPolicy(RegionAccessStore regionAccess, Limits limits) {
		this.regionAccess = Objects.requireNonNull(regionAccess, "regionAccess");
		this.limits = Objects.requireNonNull(limits, "limits");
	}

	public PublicWaypoint prepareCreate(SharedMapActor actor, PublicWaypoint submitted, int activeTotal, int activeByCreator) {
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(submitted, "submitted").validate();
		if (!limits.allowPlayerUpload() && !actor.operator()) {
			throw new IllegalArgumentException("Waypoint upload is disabled for players");
		}
		validateVisibility(actor, submitted.visibility(), null);
		if (activeTotal >= limits.maxTotal()) {
			throw new IllegalArgumentException("Shared waypoint limit reached");
		}
		if (activeByCreator >= limits.maxPerPlayer() && !actor.operator()) {
			throw new IllegalArgumentException("Player waypoint limit reached");
		}
		validateRegionChange(actor, regionOf(submitted));
		String teamName = submitted.visibility() == WaypointVisibility.TEAM ? actor.teamName() : null;
		return submitted.withColor(XaeroWaypointPalette.normalize(submitted.color()))
				.withOwnership(actor.playerId(), actor.playerName(), teamName);
	}

	public PublicWaypoint prepareUpdate(SharedMapActor actor, PublicWaypoint current, PublicWaypoint submitted) {
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(current, "current");
		Objects.requireNonNull(submitted, "submitted").validate();
		if (!current.id().equals(submitted.id())) {
			throw new IllegalArgumentException("Waypoint id cannot be changed");
		}
		if (current.deleted()) {
			throw new IllegalArgumentException("Deleted waypoint cannot be updated");
		}
		throw new IllegalArgumentException("Shared waypoints are locked; delete and recreate the waypoint instead");
	}

	public void validateDelete(SharedMapActor actor, PublicWaypoint current) {
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(current, "current");
		if (current.deleted()) {
			throw new IllegalArgumentException("Waypoint is already deleted");
		}
	}

	public boolean canView(SharedMapActor actor, PublicWaypoint waypoint) {
		if (waypoint.visibility() == WaypointVisibility.PUBLIC) {
			return true;
		}
		if (waypoint.visibility() == WaypointVisibility.TEAM) {
			return actor.operator()
					|| (actor.playerId() != null && actor.playerId().equals(waypoint.creatorId()))
					|| (actor.teamName() != null && actor.teamName().equals(waypoint.teamName()));
		}
		return actor.operator()
				|| (actor.playerId() != null && actor.playerId().equals(waypoint.creatorId()));
	}

	public RegionKey regionOf(PublicWaypoint waypoint) {
		return RegionKey.fromBlock(waypoint.dimension(), floorBlock(waypoint.x()), floorBlock(waypoint.z()));
	}

	private void validateVisibility(SharedMapActor actor, WaypointVisibility visibility, String existingTeam) {
		if (visibility == WaypointVisibility.PRIVATE) {
			throw new IllegalArgumentException("Private waypoints cannot be uploaded");
		}
		if (visibility == WaypointVisibility.TEAM && existingTeam == null && actor.teamName() == null) {
			throw new IllegalArgumentException("Team waypoints require a scoreboard team");
		}
	}

	private void validateRegionChange(SharedMapActor actor, RegionKey region) {
		RegionAccessDecision decision = regionAccess.decision(region, actor.teamName());
		if (decision == RegionAccessDecision.DENIED_BY_REGION_DISABLE) {
			throw new IllegalArgumentException("Waypoint changes are disabled in this region");
		}
		if (!actor.operator() && decision != RegionAccessDecision.ALLOWED) {
			throw new IllegalArgumentException("Scoreboard team is not allowed to change waypoints in this region");
		}
	}

	private static int floorBlock(double coordinate) {
		if (coordinate <= Integer.MIN_VALUE || coordinate >= Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Waypoint coordinate is outside the supported block range");
		}
		return (int) Math.floor(coordinate);
	}

	public interface Limits {
		boolean allowPlayerUpload();
		int maxPerPlayer();
		int maxTotal();
	}

	private static final class ConfigLimits implements Limits {
		@Override
		public boolean allowPlayerUpload() {
			return SharedMapConfig.allowPlayerWaypointUpload();
		}

		@Override
		public int maxPerPlayer() {
			return SharedMapConfig.maxWaypointsPerPlayer();
		}

		@Override
		public int maxTotal() {
			return SharedMapConfig.maxPublicWaypoints();
		}
	}
}
