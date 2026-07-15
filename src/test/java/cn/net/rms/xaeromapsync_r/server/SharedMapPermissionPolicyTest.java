package cn.net.rms.xaeromapsync_r.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.server.access.RegionAccessStore;
import cn.net.rms.xaeromapsync_r.server.access.SharedMapActor;
import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SharedMapPermissionPolicyTest {
	private static final UUID CREATOR_ID = UUID.randomUUID();
	private final RegionAccessStore regionAccess = new RegionAccessStore();
	private final SharedMapPermissionPolicy policy = new SharedMapPermissionPolicy(regionAccess, new TestLimits());
	private final SharedMapActor creator = new SharedMapActor(CREATOR_ID, "Creator", "builders", false);
	private final SharedMapActor teammate = new SharedMapActor(UUID.randomUUID(), "Teammate", "builders", false);
	private final SharedMapActor outsider = new SharedMapActor(UUID.randomUUID(), "Outsider", "visitors", false);
	private final SharedMapActor operator = new SharedMapActor(UUID.randomUUID(), "Operator", null, true);

	@Test
	void createBindsServerIdentityAndNativeTeam() {
		PublicWaypoint teamWaypoint = policy.prepareCreate(creator, waypoint(WaypointVisibility.TEAM, 1, 1), 0, 0);
		PublicWaypoint publicWaypoint = policy.prepareCreate(creator, waypoint(WaypointVisibility.PUBLIC, 1, 1), 1, 1);

		assertEquals(CREATOR_ID, teamWaypoint.creatorId());
		assertEquals("Creator", teamWaypoint.creatorName());
		assertEquals("builders", teamWaypoint.teamName());
		assertNull(publicWaypoint.teamName());
	}

	@Test
	void teamCreateRequiresScoreboardMembershipAndPrivateUploadIsRejected() {
		SharedMapActor unteamed = new SharedMapActor(UUID.randomUUID(), "Solo", null, false);

		assertThrows(IllegalArgumentException.class,
				() -> policy.prepareCreate(unteamed, waypoint(WaypointVisibility.TEAM, 1, 1), 0, 0));
		assertThrows(IllegalArgumentException.class,
				() -> policy.prepareCreate(creator, waypoint(WaypointVisibility.PRIVATE, 1, 1), 0, 0));
	}

	@Test
	void visibilityUsesPublicTeamAndOperatorRules() {
		PublicWaypoint publicWaypoint = storedWaypoint(WaypointVisibility.PUBLIC, null, 1, 1);
		PublicWaypoint teamWaypoint = storedWaypoint(WaypointVisibility.TEAM, "builders", 1, 1);

		assertTrue(policy.canView(outsider, publicWaypoint));
		assertTrue(policy.canView(teammate, teamWaypoint));
		assertEquals(false, policy.canView(outsider, teamWaypoint));
		assertTrue(policy.canView(operator, teamWaypoint));
		assertTrue(policy.canView(new SharedMapActor(CREATOR_ID, "Creator", "other-team", false), teamWaypoint));
	}

	@Test
	void sharedWaypointsRejectUpdatesButEveryPlayerCanDelete() {
		PublicWaypoint current = storedWaypoint(WaypointVisibility.PUBLIC, null, 1, 1);
		PublicWaypoint submitted = submittedFor(current, WaypointVisibility.PUBLIC, 2, 2);

		assertThrows(IllegalArgumentException.class,
				() -> policy.prepareUpdate(outsider, current, submitted));
		assertThrows(IllegalArgumentException.class, () -> policy.prepareUpdate(creator, current, submitted));
		assertThrows(IllegalArgumentException.class, () -> policy.prepareUpdate(operator, current, submitted));
		policy.validateDelete(outsider, current);
		policy.validateDelete(teammate, current);
		policy.validateDelete(creator, current);
		policy.validateDelete(operator, current);
	}

	@Test
	void updateLockAlsoAppliesWhenAnOperatorBypassesRegionAcl() {
		PublicWaypoint current = storedWaypoint(WaypointVisibility.PUBLIC, null, 1, 1);
		RegionKey region = policy.regionOf(current);
		regionAccess.allowTeam(region, "staff");
		PublicWaypoint submitted = submittedFor(current, WaypointVisibility.PUBLIC, 2, 2);

		assertThrows(IllegalArgumentException.class,
				() -> policy.prepareUpdate(creator, current, submitted));
		assertThrows(IllegalArgumentException.class, () -> policy.prepareUpdate(operator, current, submitted));
	}

	@Test
	void disabledRegionDoesNotBlockSharedWaypointDeletion() {
		PublicWaypoint current = storedWaypoint(WaypointVisibility.PUBLIC, null, 1, 1);
		regionAccess.setWaypointChangesDisabled(policy.regionOf(current), true);

		policy.validateDelete(outsider, current);
		policy.validateDelete(creator, current);
		policy.validateDelete(operator, current);
	}

	@Test
	void movingWaypointRequiresAccessToSourceAndDestinationRegions() {
		PublicWaypoint current = storedWaypoint(WaypointVisibility.PUBLIC, null, 1, 1);
		PublicWaypoint moved = submittedFor(current, WaypointVisibility.PUBLIC, 256, 1);
		regionAccess.denyTeam(policy.regionOf(moved), "builders");

		assertThrows(IllegalArgumentException.class, () -> policy.prepareUpdate(creator, current, moved));
	}

	private static PublicWaypoint waypoint(WaypointVisibility visibility, double x, double z) {
		return new PublicWaypoint(UUID.randomUUID(), UUID.randomUUID(), "Untrusted", "Spawn", "minecraft:overworld",
				x, 64, z, "S", 0xffffff, "default", visibility, 0, false, 0, 0);
	}

	private static PublicWaypoint storedWaypoint(WaypointVisibility visibility, String teamName, double x, double z) {
		return new PublicWaypoint(UUID.randomUUID(), CREATOR_ID, "Creator", "Spawn", "minecraft:overworld",
				x, 64, z, "S", 0xffffff, "default", visibility, teamName, 1, false, 1, 1);
	}

	private static PublicWaypoint submittedFor(PublicWaypoint current, WaypointVisibility visibility, double x, double z) {
		return new PublicWaypoint(current.id(), UUID.randomUUID(), "Untrusted", "Updated", current.dimension(),
				x, current.y(), z, "U", 0x112233, "updated", visibility, 0, false, 0, 0);
	}

	private static final class TestLimits implements SharedMapPermissionPolicy.Limits {
		@Override
		public boolean allowPlayerUpload() {
			return true;
		}

		@Override
		public int maxPerPlayer() {
			return 10;
		}

		@Override
		public int maxTotal() {
			return 100;
		}
	}
}
