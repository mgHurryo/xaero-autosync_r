package cn.net.rms.xaeromapsync_r.waypoint;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PublicWaypointTest {
	@Test
	void validateAcceptsCompleteWaypointAtNameLimit() {
		PublicWaypoint waypoint = waypoint("a".repeat(PublicWaypoint.MAX_NAME_LENGTH), "minecraft:overworld", 1.0D, 64.0D, -1.5D, WaypointVisibility.PUBLIC);

		assertDoesNotThrow(waypoint::validate);
	}

	@Test
	void validateRejectsMissingRequiredValues() {
		assertThrows(IllegalArgumentException.class, () -> waypoint(null, "minecraft:overworld", 1.0D, 64.0D, 1.0D, WaypointVisibility.PUBLIC).validate());
		assertThrows(IllegalArgumentException.class, () -> waypoint("Spawn", " ", 1.0D, 64.0D, 1.0D, WaypointVisibility.PUBLIC).validate());
		assertThrows(IllegalArgumentException.class, () -> waypoint("Spawn", "minecraft:overworld", 1.0D, 64.0D, 1.0D, null).validate());
	}

	@Test
	void validateRejectsNameOverLimit() {
		PublicWaypoint waypoint = waypoint("a".repeat(PublicWaypoint.MAX_NAME_LENGTH + 1), "minecraft:overworld", 1.0D, 64.0D, 1.0D, WaypointVisibility.PUBLIC);

		assertThrows(IllegalArgumentException.class, waypoint::validate);
	}

	@Test
	void validateRejectsNonFiniteCoordinates() {
		assertThrows(IllegalArgumentException.class, () -> waypoint("Spawn", "minecraft:overworld", Double.NaN, 64.0D, 1.0D, WaypointVisibility.PUBLIC).validate());
		assertThrows(IllegalArgumentException.class, () -> waypoint("Spawn", "minecraft:overworld", 1.0D, Double.POSITIVE_INFINITY, 1.0D, WaypointVisibility.PUBLIC).validate());
		assertThrows(IllegalArgumentException.class, () -> waypoint("Spawn", "minecraft:overworld", 1.0D, 64.0D, Double.NEGATIVE_INFINITY, WaypointVisibility.PUBLIC).validate());
	}

	private static PublicWaypoint waypoint(String name, String dimension, double x, double y, double z, WaypointVisibility visibility) {
		return new PublicWaypoint(UUID.randomUUID(), UUID.randomUUID(), "Creator", name, dimension, x, y, z, "S", 0xffffff, "default", visibility, 0L, false, 100L, 100L);
	}
}
