package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ClientWaypointCacheTest {
	@Test
	void deleteRemovesWaypointInsteadOfRetainingTombstone() {
		ClientWaypointCache cache = new ClientWaypointCache();
		PublicWaypoint waypoint = waypoint(false);
		cache.upsert(waypoint);

		cache.delete(waypoint(true));

		assertEquals(0, cache.activeCount());
		assertTrue(cache.snapshot().isEmpty());
	}

	private static PublicWaypoint waypoint(boolean deleted) {
		return new PublicWaypoint(UUID.fromString("e33eb10d-85d1-4fe4-b99d-fbf94d39bc74"), UUID.randomUUID(),
				"Creator", "Spawn", "minecraft:overworld", 0.0D, 64.0D, 0.0D, "S", 0xffffff,
				"default", WaypointVisibility.PUBLIC, deleted ? 2L : 1L, deleted, 1L, 2L);
	}
}
