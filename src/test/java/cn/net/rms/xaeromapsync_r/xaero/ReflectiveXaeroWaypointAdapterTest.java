package cn.net.rms.xaeromapsync_r.xaero;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import cn.net.rms.xaeromapsync_r.waypoint.XaeroWaypointPalette;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ReflectiveXaeroWaypointAdapterTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

	@Test
	void reconcileFiltersInputNormalizesColorsAndOnlyDeletesManagedPoints() {
		UUID updateId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID staleId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		UUID createId = UUID.fromString("00000000-0000-0000-0000-000000000003");
		FakePoint privatePoint = point("Private home", 1, 2, 3, "H", 1);
		FakePoint updatePoint = managed(updateId, "Old", 1, 2, 3, "O", 2);
		FakePoint duplicatePoint = managed(updateId, "Duplicate", 4, 5, 6, "D", 3);
		FakePoint stalePoint = managed(staleId, "Stale", 7, 8, 9, "S", 4);
		FakeBridge bridge = new FakeBridge(privatePoint, updatePoint, duplicatePoint, stalePoint);
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);

		Collection<PublicWaypoint> input = Arrays.asList(
				waypoint(updateId, UUID.randomUUID(), "Updated", OVERWORLD, 10.9, 64.0, -2.1, "U", 0x55FFFF, WaypointVisibility.PUBLIC, false, 1L),
				waypoint(createId, UUID.randomUUID(), "Created", OVERWORLD, -0.1, 70.8, 5.0, "", 0x445566, WaypointVisibility.TEAM, false, 1L),
				waypoint(UUID.randomUUID(), UUID.randomUUID(), "Deleted", OVERWORLD, 0, 0, 0, "D", 0, WaypointVisibility.PUBLIC, true, 1L),
				waypoint(UUID.randomUUID(), UUID.randomUUID(), "Private", OVERWORLD, 0, 0, 0, "P", 0, WaypointVisibility.PRIVATE, false, 1L),
				waypoint(UUID.randomUUID(), UUID.randomUUID(), "Nether", "minecraft:the_nether", 0, 0, 0, "N", 0, WaypointVisibility.PUBLIC, false, 1L),
				null);

		XaeroWaypointReconcileResult result = adapter.reconcile(input);

		assertEquals(XaeroWaypointReconcileResult.Outcome.APPLIED, result.outcome());
		assertEquals(1, result.created());
		assertEquals(1, result.updated());
		assertEquals(2, result.deleted());
		assertEquals(4, result.ignored());
		assertTrue(result.saved());
		assertEquals(1, bridge.saveCount);
		assertEquals(1, bridge.clearSelectionCount);
		assertEquals(3, bridge.publicPoints().size());
		assertSame(privatePoint, bridge.publicPoints().get(0));
		assertSame(updatePoint, bridge.publicPoints().get(1));
		assertEquals(new WaypointValues(10, 64, -3, XaeroWaypointIdentity.managedName("Updated", updateId), "U", 11), updatePoint.values);
		assertTrue(bridge.publicPoints().stream().anyMatch(point -> XaeroWaypointIdentity.parse(point.values.name).filter(createId::equals).isPresent()));
		assertFalse(bridge.publicPoints().contains(duplicatePoint));
		assertFalse(bridge.publicPoints().contains(stalePoint));
	}

	@Test
	void reconcileDoesNotSaveWhenNoValuesChanged() {
		UUID id = UUID.randomUUID();
		PublicWaypoint waypoint = waypoint(id, UUID.randomUUID(), "Spawn", OVERWORLD, 1, 64, 2, "S", 15, WaypointVisibility.PUBLIC, false, 1L);
		FakeBridge bridge = new FakeBridge(managed(id, "Spawn", 1, 64, 2, "S", 15));
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);

		XaeroWaypointReconcileResult result = adapter.reconcile(List.of(waypoint));

		assertEquals(XaeroWaypointReconcileResult.Outcome.NO_CHANGES, result.outcome());
		assertEquals(0, bridge.saveCount);
		assertEquals(0, bridge.clearSelectionCount);
	}

	@Test
	void selectedNativeWaypointIsSharedWithoutCreatingASecondCreatorCopy() {
		FakePoint source = point("Factory", 12, 70, -8, "F", 11);
		FakeBridge bridge = new FakeBridge(source);
		bridge.select(source, "machines", OVERWORLD);
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);

		XaeroWaypointMutation create = adapter.prepareShare(new Object(), WaypointVisibility.PUBLIC, List.of(), PLAYER_ID, "Builder");
		PublicWaypoint accepted = withServerState(create.waypoint(), 5L);
		XaeroWaypointReconcileResult result = adapter.reconcile(List.of(accepted));

		assertFalse(create.update());
		assertEquals("Factory", create.waypoint().name());
		assertEquals("machines", create.waypoint().category());
		assertEquals(11, create.waypoint().color());
		assertEquals(XaeroWaypointReconcileResult.Outcome.NO_CHANGES, result.outcome());
		assertEquals(List.of(source), bridge.publicPoints());
		assertEquals("Factory", XaeroWaypointIdentity.displayName(source.values.name));
		assertEquals(create.waypoint().id(), XaeroWaypointIdentity.parse(source.values.name).orElseThrow());
		assertEquals(1, bridge.saveCount);
	}

	@Test
	void rejectedCreateClearsPendingIdentityAndUsesANewIdentityOnRetry() {
		FakePoint source = point("Factory", 12, 70, -8, "F", 11);
		FakeBridge bridge = new FakeBridge(source);
		bridge.select(source, "machines", OVERWORLD);
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);

		XaeroWaypointMutation first = adapter.prepareShare(new Object(), WaypointVisibility.PUBLIC, List.of(), PLAYER_ID,
				"Builder");
		assertEquals(WaypointVisibility.PUBLIC,
				adapter.selectedVisibility(new Object(), List.of(), PLAYER_ID).orElseThrow());

		adapter.clearPendingMutations();
		assertEquals(WaypointVisibility.PRIVATE,
				adapter.selectedVisibility(new Object(), List.of(), PLAYER_ID).orElseThrow());
		assertEquals("Factory", XaeroWaypointIdentity.displayName(source.values.name));
		assertFalse(source.values.name.contains("\u26BF"));
		assertTrue(XaeroWaypointIdentity.parse(source.values.name).isEmpty());
		XaeroWaypointMutation retry = adapter.prepareShare(new Object(), WaypointVisibility.TEAM, List.of(), PLAYER_ID,
				"Builder");

		assertNotEquals(first.waypoint().id(), retry.waypoint().id());
		assertEquals(WaypointVisibility.TEAM, retry.waypoint().visibility());
	}

	@Test
	void creatorCannotReshareLockedWaypointButCanUnshareIt() {
		FakePoint source = point("Depot", 1, 64, 2, "D", 10);
		FakeBridge bridge = new FakeBridge(source);
		bridge.select(source, "default", OVERWORLD);
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);
		XaeroWaypointMutation create = adapter.prepareShare(new Object(), WaypointVisibility.PUBLIC, List.of(), PLAYER_ID, "Builder");
		PublicWaypoint accepted = withServerState(create.waypoint(), 3L);
		adapter.reconcile(List.of(accepted));
		source.values = new WaypointValues(5, 65, 9, "Depot 2", "X", 12);

		assertThrows(IllegalArgumentException.class,
				() -> adapter.prepareShare(new Object(), WaypointVisibility.TEAM, List.of(accepted), PLAYER_ID, "Builder"));
		PublicWaypoint deleted = adapter.prepareUnshare(new Object(), List.of(accepted), PLAYER_ID);

		assertSame(accepted, deleted);
	}

	@Test
	void creatorMarkerSurvivesRestartAndRestoresTheNativePointInPlace() {
		UUID id = UUID.randomUUID();
		FakePoint source = managed(id, "Edited offline", 9, 70, 11, "E", 12);
		FakeBridge bridge = new FakeBridge(source);
		ReflectiveXaeroWaypointAdapter restarted = adapter(bridge, true);
		PublicWaypoint serverCopy = waypoint(id, PLAYER_ID, "Before edit", OVERWORLD, 1, 64, 2, "B", 10,
				WaypointVisibility.PUBLIC, false, 4L);

		XaeroWaypointReconcileResult result = restarted.reconcile(List.of(serverCopy));

		assertEquals(XaeroWaypointReconcileResult.Outcome.APPLIED, result.outcome());
		assertEquals(1, result.updated());
		assertEquals(List.of(source), bridge.publicPoints());
		assertEquals(1, source.values.x);
		assertEquals("Before edit", XaeroWaypointIdentity.displayName(source.values.name));
	}

	@Test
	void editedRemoteCopyIsRestoredInPlaceInsteadOfDuplicated() {
		UUID id = UUID.randomUUID();
		FakePoint remote = managed(id, "Remote", 1, 64, 2, "R", 4);
		FakeBridge bridge = new FakeBridge(remote);
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);
		PublicWaypoint serverCopy = waypoint(id, UUID.randomUUID(), "Remote", OVERWORLD, 1, 64, 2, "R", 4,
				WaypointVisibility.PUBLIC, false, 2L);
		adapter.reconcile(List.of(serverCopy));
		remote.values = new WaypointValues(7, 80, 8, "Changed locally", "C", 3);

		XaeroWaypointReconcileResult result = adapter.reconcile(List.of(serverCopy));

		assertEquals(1, result.updated());
		assertEquals(List.of(remote), bridge.publicPoints());
		assertEquals(id, XaeroWaypointIdentity.parse(remote.values.name).orElseThrow());
		assertEquals("Remote", XaeroWaypointIdentity.displayName(remote.values.name));
	}

	@Test
	void selectedVisibilityReflectsTheSharedRecord() {
		UUID id = UUID.randomUUID();
		FakePoint source = managed(id, "Team point", 1, 64, 2, "T", 4);
		FakeBridge bridge = new FakeBridge(source);
		bridge.select(source, "default", OVERWORLD);
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);
		PublicWaypoint shared = waypoint(id, PLAYER_ID, "Team point", OVERWORLD, 1, 64, 2, "T", 4,
				WaypointVisibility.TEAM, false, 2L);

		assertEquals(WaypointVisibility.TEAM,
				adapter.selectedVisibility(new Object(), List.of(shared), PLAYER_ID).orElseThrow());
	}

	@Test
	void nonCreatorCannotUpdateButCanDeleteManagedWaypoint() {
		UUID id = UUID.randomUUID();
		FakePoint remote = managed(id, "Remote", 1, 2, 3, "R", 4);
		FakeBridge bridge = new FakeBridge(remote);
		bridge.select(remote, "default", OVERWORLD);
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);
		PublicWaypoint waypoint = waypoint(id, UUID.randomUUID(), "Remote", OVERWORLD, 1, 2, 3, "R", 4, WaypointVisibility.PUBLIC, false, 2L);

		assertThrows(IllegalArgumentException.class,
				() -> adapter.prepareShare(new Object(), WaypointVisibility.PUBLIC, List.of(waypoint), PLAYER_ID, "Builder"));
		assertSame(waypoint, adapter.prepareUnshare(new Object(), List.of(waypoint), PLAYER_ID));
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
		FakePoint privatePoint = point("Private", 0, 0, 0, "P", 1);
		FakePoint updatePoint = managed(updateId, "Before", 1, 2, 3, "B", 2);
		FakePoint stalePoint = managed(staleId, "Stale", 4, 5, 6, "S", 3);
		WaypointValues originalUpdate = updatePoint.values;
		FakeBridge bridge = new FakeBridge(privatePoint, updatePoint, stalePoint);
		bridge.failOnSave = true;
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);

		XaeroWaypointReconcileResult result = adapter.reconcile(List.of(
				waypoint(updateId, UUID.randomUUID(), "After", OVERWORLD, 10, 20, 30, "A", 4, WaypointVisibility.PUBLIC, false, 1L),
				waypoint(UUID.randomUUID(), UUID.randomUUID(), "New", OVERWORLD, 40, 50, 60, "N", 5, WaypointVisibility.PUBLIC, false, 1L)));

		assertEquals(XaeroWaypointReconcileResult.Outcome.FAILED, result.outcome());
		assertEquals(List.of(privatePoint, updatePoint, stalePoint), bridge.publicPoints());
		assertEquals(originalUpdate, updatePoint.values);
		assertFalse(adapter.isAvailable());
	}

	@Test
	void reconcileCreatesAndReusesIndependentSetWithoutChangingCurrentSet() {
		UUID id = UUID.randomUUID();
		FakePoint privatePoint = point("Private", 1, 64, 2, "P", 3);
		FakeBridge bridge = new FakeBridge();
		bridge.addToSet("gui.xaero_default", privatePoint);
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);
		PublicWaypoint remote = waypoint(id, UUID.randomUUID(), "Remote", OVERWORLD, 5, 70, 6, "R", 4,
				WaypointVisibility.PUBLIC, false, 1L);

		XaeroWaypointReconcileResult first = adapter.reconcile(List.of(remote));
		XaeroWaypointReconcileResult second = adapter.reconcile(List.of(remote));

		assertEquals(XaeroWaypointReconcileResult.Outcome.APPLIED, first.outcome());
		assertEquals(XaeroWaypointReconcileResult.Outcome.NO_CHANGES, second.outcome());
		assertEquals(1, bridge.addSetCount);
		assertEquals("gui.xaero_default", bridge.currentSet);
		assertEquals(List.of(privatePoint), bridge.points("gui.xaero_default"));
		assertEquals(1, bridge.publicPoints().size());
		assertEquals(id, XaeroWaypointIdentity.parse(bridge.publicPoints().get(0).values.name).orElseThrow());
		assertEquals(1, bridge.saveCount);
	}

	@Test
	void reconcileMigratesLegacyPointAndPreservesLocalHiddenState() {
		UUID id = UUID.randomUUID();
		FakePoint legacy = managed(id, "Hidden remote", 3, 65, 4, "H", 7);
		legacy.disabled = true;
		FakeBridge bridge = new FakeBridge();
		bridge.addToSet("legacy", legacy);
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);
		PublicWaypoint remote = waypoint(id, UUID.randomUUID(), "Hidden remote", OVERWORLD, 3, 65, 4, "H", 7,
				WaypointVisibility.PUBLIC, false, 1L);

		XaeroWaypointReconcileResult result = adapter.reconcile(List.of(remote));

		assertEquals(XaeroWaypointReconcileResult.Outcome.APPLIED, result.outcome());
		assertEquals(1, result.updated());
		assertTrue(bridge.points("legacy").isEmpty());
		assertEquals(List.of(legacy), bridge.publicPoints());
		assertTrue(legacy.disabled);
	}

	@Test
	void creatorSourceMovesToPublicSetAndReturnsToOriginalCategoryAfterDeletion() {
		FakePoint source = point("Factory", 12, 70, -8, "F", 11);
		source.disabled = true;
		FakeBridge bridge = new FakeBridge();
		bridge.addToSet("machines", source);
		bridge.select(source, "machines", OVERWORLD);
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);
		XaeroWaypointMutation create = adapter.prepareShare(new Object(), WaypointVisibility.PUBLIC, List.of(),
				PLAYER_ID, "Builder");
		PublicWaypoint accepted = withServerState(create.waypoint(), 5L);

		XaeroWaypointReconcileResult shared = adapter.reconcile(List.of(accepted));
		XaeroWaypointReconcileResult removed = adapter.reconcile(List.of(asDeleted(accepted)));

		assertEquals(XaeroWaypointReconcileResult.Outcome.APPLIED, shared.outcome());
		assertEquals(XaeroWaypointReconcileResult.Outcome.APPLIED, removed.outcome());
		assertEquals(1, removed.deleted());
		assertTrue(bridge.publicPoints().isEmpty());
		assertEquals(List.of(source), bridge.points("machines"));
		assertEquals("Factory", source.values.name);
		assertTrue(source.disabled);
	}

	@Test
	void saveFailureRollsBackEveryWaypointSetAndMovedObjectValue() {
		UUID updateId = UUID.randomUUID();
		UUID staleId = UUID.randomUUID();
		FakePoint privatePoint = point("Private", 0, 64, 0, "P", 1);
		FakePoint updatePoint = managed(updateId, "Before", 1, 2, 3, "B", 2);
		FakePoint stalePoint = managed(staleId, "Stale", 4, 5, 6, "S", 3);
		WaypointValues originalUpdate = updatePoint.values;
		FakeBridge bridge = new FakeBridge();
		bridge.addToSet("gui.xaero_default", privatePoint);
		bridge.addToSet("legacy", updatePoint);
		bridge.addToSet("other", stalePoint);
		bridge.failOnSave = true;
		ReflectiveXaeroWaypointAdapter adapter = adapter(bridge, true);

		XaeroWaypointReconcileResult result = adapter.reconcile(List.of(
				waypoint(updateId, UUID.randomUUID(), "After", OVERWORLD, 10, 20, 30, "A", 4,
						WaypointVisibility.PUBLIC, false, 1L),
				waypoint(UUID.randomUUID(), UUID.randomUUID(), "New", OVERWORLD, 40, 50, 60, "N", 5,
						WaypointVisibility.PUBLIC, false, 1L)));

		assertEquals(XaeroWaypointReconcileResult.Outcome.FAILED, result.outcome());
		assertEquals(List.of(privatePoint), bridge.points("gui.xaero_default"));
		assertEquals(List.of(updatePoint), bridge.points("legacy"));
		assertEquals(List.of(stalePoint), bridge.points("other"));
		assertFalse(bridge.waypointSets.containsKey(XaeroWaypointBridge.PUBLIC_WAYPOINT_SET));
		assertEquals(originalUpdate, updatePoint.values);
		assertFalse(adapter.isAvailable());
	}

	@Test
	void paletteConvertsLegacyRgbAndArgbToXaeroIndices() {
		assertEquals(11, XaeroWaypointPalette.normalize(0x55FFFF));
		assertEquals(11, XaeroWaypointPalette.normalize(0xFF55FFFF));
		assertEquals(15, XaeroWaypointPalette.normalize(15));
		assertTrue(XaeroWaypointPalette.normalize(0x123456) >= 0);
		assertTrue(XaeroWaypointPalette.normalize(0x123456) <= 15);
	}

	private static ReflectiveXaeroWaypointAdapter adapter(FakeBridge bridge, boolean clientThread) {
		return new ReflectiveXaeroWaypointAdapter(bridge, () -> OVERWORLD, () -> PLAYER_ID, () -> clientThread);
	}

	private static FakePoint point(String name, int x, int y, int z, String symbol, int color) {
		return new FakePoint(new WaypointValues(x, y, z, name, symbol, color));
	}

	private static FakePoint managed(UUID id, String name, int x, int y, int z, String symbol, int color) {
		return point(XaeroWaypointIdentity.managedName(name, id), x, y, z, symbol, color);
	}

	private static PublicWaypoint waypoint(UUID id, UUID creatorId, String name, String dimension, double x, double y,
			double z, String symbol, int color, WaypointVisibility visibility, boolean deleted, long revision) {
		return new PublicWaypoint(id, creatorId, "Creator", name, dimension, x, y, z, symbol, color, "default",
				visibility, revision, deleted, 1L, 1L);
	}

	private static PublicWaypoint withServerState(PublicWaypoint waypoint, long revision) {
		return new PublicWaypoint(waypoint.id(), waypoint.creatorId(), waypoint.creatorName(), waypoint.name(),
				waypoint.dimension(), waypoint.x(), waypoint.y(), waypoint.z(), waypoint.symbol(), waypoint.color(),
				waypoint.category(), waypoint.visibility(), revision, false, 1L, 2L);
	}

	private static PublicWaypoint asDeleted(PublicWaypoint waypoint) {
		return waypoint.tombstone(waypoint.revision() + 1L, waypoint.updatedAtMillis() + 1L);
	}

	private static final class FakePoint {
		private WaypointValues values;
		private boolean disabled;

		private FakePoint(WaypointValues values) {
			this.values = values;
		}
	}

	private static final class FakeBridge implements XaeroWaypointBridge {
		private final Object world = new Object();
		private final Map<String, List<FakePoint>> waypointSets = new LinkedHashMap<>();
		private String currentSet = "gui.xaero_default";
		private int addSetCount;
		private int saveCount;
		private int clearSelectionCount;
		private boolean failOnTarget;
		private boolean failOnSave;
		private SelectedWaypoint selected;

		private FakeBridge(FakePoint... points) {
			if (points.length != 0) {
				waypointSets.put(PUBLIC_WAYPOINT_SET, new ArrayList<>(Arrays.asList(points)));
			}
		}

		private void select(FakePoint point, String category, String dimension) {
			selected = new SelectedWaypoint(point, world, point.values, category, dimension);
		}

		private void addToSet(String category, FakePoint... points) {
			waypointSets.computeIfAbsent(category, ignored -> new ArrayList<>()).addAll(Arrays.asList(points));
		}

		private List<FakePoint> points(String category) {
			return waypointSets.getOrDefault(category, List.of());
		}

		private List<FakePoint> publicPoints() {
			return points(PUBLIC_WAYPOINT_SET);
		}

		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		public Target currentTarget() throws ReflectiveOperationException {
			if (failOnTarget) {
				throw new ReflectiveOperationException("test failure");
			}
			boolean created = !waypointSets.containsKey(PUBLIC_WAYPOINT_SET);
			if (created) {
				waypointSets.put(PUBLIC_WAYPOINT_SET, new ArrayList<>());
				addSetCount++;
			}
			return new Target(world, (Map) waypointSets, created);
		}

		@Override
		public SelectedWaypoint selectedWaypoint(Object screen) {
			if (selected == null) {
				throw new IllegalArgumentException("Select exactly one Xaero waypoint");
			}
			return new SelectedWaypoint(selected.nativeWaypoint(), world,
					((FakePoint) selected.nativeWaypoint()).values, selected.category(), selected.dimension());
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

		@Override
		public void clearWaypointScreenSelection() {
			clearSelectionCount++;
		}

		@Override
		public void removeSet(Object world, String setKey) {
			assertSame(this.world, world);
			waypointSets.remove(setKey);
		}
	}
}
