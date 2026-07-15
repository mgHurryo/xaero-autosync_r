package cn.net.rms.xaeromapsync_r.xaero;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class XaeroWaypointIdentityTest {
	@Test
	void markerRoundTripsWithoutXaeroFileDelimiters() {
		UUID id = UUID.fromString("12345678-1234-5678-9abc-def012345678");
		String managedName = XaeroWaypointIdentity.managedName("Village: north", id);

		assertEquals(id, XaeroWaypointIdentity.parse(managedName).orElseThrow());
		assertTrue(managedName.endsWith(" [xms-EjRWeBI0VniavN7wEjRWeA]"));
	}

	@Test
	void parserRejectsLookalikesAndNonCanonicalIds() {
		assertTrue(XaeroWaypointIdentity.parse("Home").isEmpty());
		assertTrue(XaeroWaypointIdentity.parse("Home [xms-not-a-managed-waypoint]").isEmpty());
		assertTrue(XaeroWaypointIdentity.parse("Home [XMS-EjRWeBI0VniavN7wEjRWeA]").isEmpty());
		assertTrue(XaeroWaypointIdentity.parse("Home [xms-EjRWeBI0VniavN7wEjRWeA] trailing").isEmpty());
	}
}
