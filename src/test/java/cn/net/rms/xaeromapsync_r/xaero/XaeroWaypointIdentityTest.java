package cn.net.rms.xaeromapsync_r.xaero;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class XaeroWaypointIdentityTest {
	@Test
	void formattingMarkerRoundTripsWithoutChangingTheVisibleName() {
		UUID id = UUID.fromString("12345678-1234-5678-9abc-def012345678");
		String managedName = XaeroWaypointIdentity.managedName("Village: north", id);

		assertEquals(id, XaeroWaypointIdentity.parse(managedName).orElseThrow());
		assertEquals("Village: north", XaeroWaypointIdentity.displayName(managedName));
		assertTrue(managedName.startsWith("\u26BF "));
		assertTrue(!managedName.contains("[xms-"));
		assertTrue(!managedName.contains("\uFE00"));
	}

	@Test
	void parserRejectsLookalikesAndNonCanonicalIds() {
		assertTrue(XaeroWaypointIdentity.parse("Home").isEmpty());
		assertTrue(XaeroWaypointIdentity.parse("Home [xms-not-a-managed-waypoint]").isEmpty());
		assertTrue(XaeroWaypointIdentity.parse("Home [XMS-EjRWeBI0VniavN7wEjRWeA]").isEmpty());
		assertTrue(XaeroWaypointIdentity.parse("Home [xms-EjRWeBI0VniavN7wEjRWeA] trailing").isEmpty());
		assertEquals(UUID.fromString("12345678-1234-5678-9abc-def012345678"),
				XaeroWaypointIdentity.parse("Home [xms-EjRWeBI0VniavN7wEjRWeA]").orElseThrow());
	}

	@Test
	void managedNameFilterRejectsAllTrailingXmsMarkers() {
		assertTrue(XaeroWaypointIdentity.isManagedName("Home [xms-EjRWeBI0VniavN7wEjRWeA]"));
		assertTrue(XaeroWaypointIdentity.isManagedName("Home [xms-malformed-but-managed]"));
		assertTrue(!XaeroWaypointIdentity.isManagedName("Home [xms-marker] trailing"));
		assertTrue(!XaeroWaypointIdentity.isManagedName("Home"));
	}
}
