package cn.net.rms.xaeromapsync_r.xaero;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ReflectiveXaeroWaypointAdapterTest {
	private static final String OVERWORLD = "minecraft:overworld";

	@Test
	void reconcileFiltersInputUpdatesInPlaceAndOnlyDeletesManagedPoints() {
		UUID updateId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID staleId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		UUID createId = UUID.fromString("00000000-0000-0000-0000-000000000003");
		FakePoint privatePoint = new FakePoint(new WaypointValues(1, 2, 3, "Private home", "H", 1));
		FakePoint updatePoint = managed(updateId, "Old", 1, 2, 3, "O", 2);
		FakePoint duplicatePoint = managed(updateId, "Duplicate", 4, 5, 6, "D", 3);
		FakePoint stalePoint = managed(staleId, "Stale", 7, 8, 9, "S", 4);
		FakeBridge bridge = new FakeBridge(privatePoint, updatePoint, duplicatePoint, stalePoint);
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);

		Collection<PublicWaypoint> input = Arrays.asList(
			waypoint(updateId, "Updated", OVERWORLD, 10.9, 64.0, -2.1, "U", 0x112233, WaypointVisibility.PUBLIC, false),
			waypoint(createId, "Created", OVERWORLD, -0.1, 70.8, 5.0, "", 0x445566, WaypointVisibility.PUBLIC, false),
			waypoint(UUID.randomUUID(), "Deleted", OVERWORLD, 0, 0, 0, "D", 0, WaypointVisibility.PUBLIC, true),
			waypoint(UUID.randomUUID(), "Private", OVERWORLD, 0, 0, 0, "P", 0, WaypointVisibility.PRIVATE, false),
			waypoint(UUID.randomUUID(), "Nether", "minecraft:the_nether", 0, 0, 0, "N", 0, WaypointVisibility.PUBLIC, false),
			null
		);

		XaeroWaypointReconcileResult result = adapter.reconcile(input);

		assertEquals(XaeroWaypointReconcileResult.Outcome.APPLIED, result.outcome());
		assertEquals(1, result.created());
		assertEquals(1, result.updated());
		assertEquals(2, result.deleted());
		assertEquals(4, result.ignored());
		assertTrue(result.saved());
		assertEquals(1, bridge.saveCount);
		assertEquals(3, bridge.points.size());
		assertSame(privatePoint, bridge.points.get(0));
		assertSame(updatePoint, bridge.points.get(1));
		assertEquals(new WaypointValues(10, 64, -3, XaeroWaypointIdentity.managedName("Updated", updateId), "U", 0x112233), updatePoint.values);
		assertTrue(bridge.points.stream().anyMatch(point -> XaeroWaypointIdentity.parse(point.values.name).filter(createId::equals).isPresent()));
		assertFalse(bridge.points.contains(duplicatePoint));
		assertFalse(bridge.points.contains(stalePoint));
	}

	@Test
	void reconcileDoesNotSaveWhenNoValuesChanged() {
		UUID id = UUID.randomUUID();
		PublicWaypoint waypoint = waypoint(id, "Spawn", OVERWORLD, 1, 64, 2, "S", 0xffffff, WaypointVisibility.PUBLIC, false);
		FakeBridge bridge = new FakeBridge(managed(id, "Spawn", 1, 64, 2, "S", 0xffffff));
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);

		XaeroWaypointReconcileResult result = adapter.reconcile(List.of(waypoint));

		assertEquals(XaeroWaypointReconcileResult.Outcome.NO_CHANGES, result.outcome());
		assertEquals(0, bridge.saveCount);
	}

	@Test
	void reconcileRejectsWrongThreadWithoutDisablingAdapter() {
		FakeBridge bridge = new FakeBridge();
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, false);

		XaeroWaypointReconcileResult result = adapter.reconcile(List.of());

		assertEquals(XaeroWaypointReconcileResult.Outcome.FAILED, result.outcome());
		assertTrue(adapter.isAvailable());
		assertEquals(0, bridge.saveCount);
	}

	@Test
	void runtimeBridgeFailureDisablesAdapterForSession() {
		FakeBridge bridge = new FakeBridge();
		bridge.failOnTarget = true;
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);

		XaeroWaypointReconcileResult first = adapter.reconcile(List.of());
		XaeroWaypointReconcileResult second = adapter.reconcile(List.of());

		assertEquals(XaeroWaypointReconcileResult.Outcome.FAILED, first.outcome());
		assertEquals(XaeroWaypointReconcileResult.Outcome.UNAVAILABLE, second.outcome());
		assertFalse(adapter.isAvailable());
	}

	@Test
	void saveFailureRollsBackListAndInPlaceUpdates() {
		UUID updateId = UUID.randomUUID();
		UUID staleId = UUID.randomUUID();
		FakePoint privatePoint = new FakePoint(new WaypointValues(0, 0, 0, "Private", "P", 1));
		FakePoint updatePoint = managed(updateId, "Before", 1, 2, 3, "B", 2);
		FakePoint stalePoint = managed(staleId, "Stale", 4, 5, 6, "S", 3);
		WaypointValues originalUpdate = updatePoint.values;
		FakeBridge bridge = new FakeBridge(privatePoint, updatePoint, stalePoint);
		bridge.failOnSave = true;
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);

		XaeroWaypointReconcileResult result = adapter.reconcile(List.of(
			waypoint(updateId, "After", OVERWORLD, 10, 20, 30, "A", 4, WaypointVisibility.PUBLIC, false),
			waypoint(UUID.randomUUID(), "New", OVERWORLD, 40, 50, 60, "N", 5, WaypointVisibility.PUBLIC, false)
		));

		assertEquals(XaeroWaypointReconcileResult.Outcome.FAILED, result.outcome());
		assertEquals(List.of(privatePoint, updatePoint, stalePoint), bridge.points);
		assertEquals(originalUpdate, updatePoint.values);
		assertFalse(adapter.isAvailable());
	}

	private static ReflectiveXaeroWaypointAdapter adapter(FakeBridge bridge, boolean clientThread) {
		return new ReflectiveXaeroWaypointAdapter(bridge, () -> OVERWORLD, () -> clientThread);
	}

	private static FakePoint managed(UUID id, String name, int x, int y, int z, String symbol, int color) {
		return new FakePoint(new WaypointValues(x, y, z, XaeroWaypointIdentity.managedName(name, id), symbol, color));
	}

	private static PublicWaypoint waypoint(UUID id, String name, String dimension, double x, double y, double z, String symbol, int color, WaypointVisibility visibility, boolean deleted) {
		return new PublicWaypoint(id, UUID.randomUUID(), "Creator", name, dimension, x, y, z, symbol, color, "default", visibility, 1L, deleted, 1L, 1L);
	}

	private static final class FakePoint {
		private WaypointValues values;

		private FakePoint(WaypointValues values) {
			this.values = values;
		}
	}

	private static final class FakeBridge implements XaeroWaypointBridge {
		private final Object world = new Object();
		private final List<FakePoint> points = new ArrayList<>();
		private int saveCount;
		private boolean failOnTarget;
		private boolean failOnSave;

		private FakeBridge(FakePoint... points) {
			this.points.addAll(Arrays.asList(points));
		}

		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		public Target currentTarget() throws ReflectiveOperationException {
			if (failOnTarget) {
				throw new ReflectiveOperationException("test failure");
			}
			return new Target(world, (List) points);
		}

		@Override
		public Object create(WaypointValues values) {
			return new FakePoint(values);
		}

		@Override
		public WaypointValues read(Object waypoint) {
			return ((FakePoint) waypoint).values;
		}

		@Override
		public void update(Object waypoint, WaypointValues values) {
			((FakePoint) waypoint).values = values;
		}

		@Override
		public void save(Object world) throws ReflectiveOperationException {
			assertSame(this.world, world);
			if (failOnSave) {
				throw new ReflectiveOperationException("save failure");
			}
			saveCount++;
		}
	}
}
