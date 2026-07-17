package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import cn.net.rms.xaeromapsync_r.waypoint.XaeroWaypointPalette;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class ReflectiveXaeroWaypointAdapter implements XaeroWaypointAdapter {
	private final XaeroWaypointBridge bridge;
	private final Supplier<String> currentDimension;
	private final Supplier<UUID> localPlayerId;
	private final BooleanSupplier clientThread;
	private final Map<UUID, Object> boundSources = new HashMap<>();
	private final IdentityHashMap<Object, UUID> sourceIds = new IdentityHashMap<>();
	private final Map<UUID, String> sourceCategories = new HashMap<>();
	private final Map<UUID, Object> managedSources = new HashMap<>();
	private final IdentityHashMap<Object, UUID> managedSourceIds = new IdentityHashMap<>();
	private final Map<UUID, PublicWaypoint> pendingCreates = new HashMap<>();
	private boolean available = true;

	ReflectiveXaeroWaypointAdapter(XaeroWaypointBridge bridge, Supplier<String> currentDimension,
			Supplier<UUID> localPlayerId, BooleanSupplier clientThread) {
		this.bridge = bridge;
		this.currentDimension = currentDimension;
		this.localPlayerId = localPlayerId;
		this.clientThread = clientThread;
	}

	@Override
	public boolean isAvailable() {
		return available;
	}

	@Override
	public XaeroWaypointReconcileResult reconcile(Collection<PublicWaypoint> waypoints) {
		if (!available) {
			return XaeroWaypointReconcileResult.unavailable("Xaero waypoint adapter is disabled");
		}
		if (!clientThread.getAsBoolean()) {
			String message = "Xaero waypoint reconciliation must run on the client main thread";
			XaeroMapsync_r.LOGGER.error(message);
			return XaeroWaypointReconcileResult.failed(0, message);
		}
		if (waypoints == null) {
			String message = "Xaero waypoint reconciliation received a null collection";
			XaeroMapsync_r.LOGGER.error(message);
			return XaeroWaypointReconcileResult.failed(0, message);
		}

		int ignored = 0;
		XaeroWaypointBridge.Target target = null;
		Map<String, List<Object>> originalOrders = null;
		Map<Object, WaypointValues> originalValues = new IdentityHashMap<>();
		try {
			String dimension = currentDimension.get();
			if (dimension == null) {
				throw new IllegalStateException("Minecraft client world is not initialized");
			}
			Map<UUID, PublicWaypoint> desired = new HashMap<>();
			Map<UUID, PublicWaypoint> known = new HashMap<>();
			Set<UUID> knownIds = new HashSet<>();
			for (PublicWaypoint waypoint : waypoints) {
				if (waypoint != null && waypoint.id() != null) {
					knownIds.add(waypoint.id());
					known.put(waypoint.id(), waypoint);
					if (waypoint.deleted()) {
						pendingCreates.remove(waypoint.id());
					}
				}
				if (!isEligible(waypoint, dimension)) {
					ignored++;
					continue;
				}
				waypoint.validate();
				desired.put(waypoint.id(), waypoint.withColor(XaeroWaypointPalette.normalize(waypoint.color())));
				pendingCreates.remove(waypoint.id());
			}

			target = bridge.currentTarget();
			Map<String, List<Object>> waypointSets = target.waypointSets();
			List<Object> publicWaypoints = target.waypoints();
			originalOrders = snapshotOrders(waypointSets);
			Map<UUID, Object> managed = new HashMap<>();
			Set<Object> remove = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
			Set<Object> scanned = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
			for (List<Object> setWaypoints : waypointSets.values()) {
				for (Object xaeroWaypoint : new ArrayList<>(setWaypoints)) {
					if (!scanned.add(xaeroWaypoint)) {
						continue;
					}
					WaypointValues currentValues = bridge.read(xaeroWaypoint);
					UUID id = XaeroWaypointIdentity.parse(currentValues.name).orElse(null);
					if (id == null) {
						continue;
					}
					originalValues.put(xaeroWaypoint, currentValues);
					Object existing = managed.putIfAbsent(id, xaeroWaypoint);
					if (existing != null && existing != xaeroWaypoint) {
						Object boundSource = boundSources.get(id);
						if (boundSource == xaeroWaypoint) {
							managed.put(id, xaeroWaypoint);
							remove.add(existing);
						} else {
							remove.add(xaeroWaypoint);
						}
					}
				}
			}
			for (Map.Entry<UUID, Object> entry : new ArrayList<>(managedSources.entrySet())) {
				if (!desired.containsKey(entry.getKey()) || !containsIdentity(waypointSets, entry.getValue())) {
					unbindManaged(entry.getKey());
					continue;
				}
				remove.remove(entry.getValue());
				Object existing = managed.put(entry.getKey(), entry.getValue());
				if (existing != null && existing != entry.getValue()) {
					remove.add(existing);
				}
			}

			int created = 0;
			int updated = 0;
			int deleted = 0;
			boolean changed = target.publicSetCreated();
			UUID playerId = localPlayerId.get();
			for (Map.Entry<UUID, PublicWaypoint> entry : desired.entrySet()) {
				UUID id = entry.getKey();
				PublicWaypoint waypoint = entry.getValue();
				Object managedPoint = managed.get(id);
				if (Objects.equals(playerId, waypoint.creatorId())) {
					Object source = boundSources.get(id);
					if (source != null && !containsIdentity(waypointSets, source)) {
						source = null;
					}
					if (source == null && managedPoint != null) {
						source = managedPoint;
					}
					if (source == null) {
						source = findMatchingSource(waypointSets, waypoint);
					}
					if (source != null) {
						remove.remove(source);
						bind(id, source);
						sourceCategories.put(id, waypoint.category());
						if (managedPoint != null && managedPoint != source) {
							remove.add(managedPoint);
						}
						managedPoint = source;
						bindManaged(id, source);
					}
				}

				WaypointValues desiredValues = toManagedValues(waypoint);
				if (managedPoint == null) {
					Object createdPoint = bridge.create(desiredValues);
					publicWaypoints.add(createdPoint);
					managed.put(id, createdPoint);
					bindManaged(id, createdPoint);
					created++;
					changed = true;
				} else {
					boolean pointUpdated = false;
					WaypointValues currentValues = bridge.read(managedPoint);
					originalValues.putIfAbsent(managedPoint, currentValues);
					if (!desiredValues.equals(currentValues)) {
						bridge.update(managedPoint, desiredValues);
						pointUpdated = true;
						changed = true;
					}
					if (moveToSet(waypointSets, managedPoint, XaeroWaypointBridge.PUBLIC_WAYPOINT_SET)) {
						changed = true;
						pointUpdated = true;
					}
					bindManaged(id, managedPoint);
					if (pointUpdated) {
						updated++;
					}
				}
			}

			for (Map.Entry<UUID, Object> entry : managed.entrySet()) {
				if (desired.containsKey(entry.getKey())) {
					continue;
				}
				UUID id = entry.getKey();
				Object managedPoint = entry.getValue();
				PublicWaypoint record = known.get(id);
				Object source = boundSources.get(id);
				boolean creatorSource = source == managedPoint
						|| record != null && Objects.equals(playerId, record.creatorId());
				if (creatorSource) {
					String category = record == null ? sourceCategories.get(id) : record.category();
					List<Object> originalCategory = waypointSets.get(category);
					if (originalCategory == null) {
						throw new IllegalStateException("Xaero source waypoint set is not initialized: " + category);
					}
					WaypointValues values = bridge.read(managedPoint);
					originalValues.putIfAbsent(managedPoint, values);
					String restoredName = XaeroWaypointIdentity.displayName(values.name);
					if (!restoredName.equals(values.name)) {
						bridge.update(managedPoint, new WaypointValues(values.x, values.y, values.z, restoredName,
								values.symbol, values.color));
					}
					moveToSet(waypointSets, managedPoint, category);
				} else {
					removeFromAllSets(waypointSets, managedPoint);
				}
				unbind(id);
				deleted++;
				changed = true;
			}
			for (Object duplicate : remove) {
				if (removeFromAllSets(waypointSets, duplicate)) {
					deleted++;
					changed = true;
				}
			}
			for (UUID id : new ArrayList<>(boundSources.keySet())) {
				if (!knownIds.contains(id) && !pendingCreates.containsKey(id)) {
					unbind(id);
				}
			}
			for (UUID id : new ArrayList<>(managedSources.keySet())) {
				if (!desired.containsKey(id)) {
					unbindManaged(id);
				}
			}
			if (changed) {
				bridge.clearWaypointScreenSelection();
				bridge.save(target.world());
			}
			XaeroMapsync_r.LOGGER.info("Xaero waypoint reconcile completed: created={}, updated={}, deleted={}, ignored={}, saved={}", created, updated, deleted, ignored, changed);
			return XaeroWaypointReconcileResult.completed(created, updated, deleted, ignored, changed);
		} catch (RuntimeException | ReflectiveOperationException | LinkageError exception) {
			rollback(target, originalOrders, originalValues);
			if (isNotReady(exception)) {
				String message = "Xaero waypoint runtime is not ready; reconciliation will be retried";
				XaeroMapsync_r.LOGGER.debug(message);
				return XaeroWaypointReconcileResult.failed(ignored, message);
			}
			available = false;
			String message = "Xaero waypoint reconciliation failed; adapter disabled for this session";
			XaeroMapsync_r.LOGGER.error(message, exception);
			return XaeroWaypointReconcileResult.failed(ignored, message + ": " + exception.getMessage());
		}
	}

	@Override
	public XaeroWaypointMutation prepareShare(Object screen, WaypointVisibility visibility,
			Collection<PublicWaypoint> knownWaypoints, UUID playerId, String playerName) {
		requireMutationContext(knownWaypoints, playerId);
		if (visibility != WaypointVisibility.PUBLIC && visibility != WaypointVisibility.TEAM) {
			throw new IllegalArgumentException("Only public or team sharing is supported");
		}
		XaeroWaypointBridge.SelectedWaypoint selected = selectedWaypoint(screen);
		Map<UUID, PublicWaypoint> known = index(knownWaypoints);
		PublicWaypoint existing = findSelectedRecord(selected, known, playerId);
		if (existing != null && !playerId.equals(existing.creatorId())) {
			throw new IllegalArgumentException("Only the creator can update this shared waypoint");
		}
		if (existing != null) {
			throw new IllegalArgumentException("Shared waypoints are locked; unshare before editing");
		}

		WaypointValues values = selected.values();
		String name = XaeroWaypointIdentity.displayName(values.name);
		String symbol = normalizeSymbol(values.symbol, name);
		int color = XaeroWaypointPalette.normalize(values.color);
		PublicWaypoint candidate;
		UUID boundId = sourceIds.get(selected.nativeWaypoint());
		PublicWaypoint pending = boundId == null ? null : pendingCreates.get(boundId);
		UUID markerId = XaeroWaypointIdentity.parse(values.name).orElse(null);
		UUID id = pending != null ? pending.id() : markerId != null ? markerId : UUID.randomUUID();
		candidate = new PublicWaypoint(id, playerId, playerName, name, selected.dimension(), values.x, values.y,
				values.z, symbol, color, selected.category(), visibility, 0L, false, 0L, 0L);
		pendingCreates.put(id, candidate);
		candidate.validate();
		bind(candidate.id(), selected.nativeWaypoint());
		sourceCategories.put(candidate.id(), selected.category());
		persistIdentity(selected, candidate.id());
		return new XaeroWaypointMutation(candidate, false);
	}

	@Override
	public PublicWaypoint prepareUnshare(Object screen, Collection<PublicWaypoint> knownWaypoints, UUID playerId) {
		requireMutationContext(knownWaypoints, playerId);
		XaeroWaypointBridge.SelectedWaypoint selected = selectedWaypoint(screen);
		PublicWaypoint existing = findSelectedRecord(selected, index(knownWaypoints), playerId);
		if (existing == null || existing.deleted()) {
			throw new IllegalArgumentException("The selected Xaero waypoint is not shared");
		}
		return existing;
	}

	@Override
	public Optional<WaypointVisibility> selectedVisibility(Object screen,
			Collection<PublicWaypoint> knownWaypoints, UUID playerId) {
		if (!available || !clientThread.getAsBoolean() || playerId == null || knownWaypoints == null) {
			return Optional.empty();
		}
		try {
			XaeroWaypointBridge.SelectedWaypoint selected = bridge.selectedWaypoint(screen);
			UUID sourceId = sourceIds.get(selected.nativeWaypoint());
			PublicWaypoint pending = sourceId == null ? null : pendingCreates.get(sourceId);
			if (pending != null) {
				return Optional.of(pending.visibility());
			}
			Map<UUID, PublicWaypoint> known = index(knownWaypoints);
			PublicWaypoint existing = findSelectedRecord(selected, known, playerId);
			return existing == null || existing.deleted()
					? Optional.of(WaypointVisibility.PRIVATE)
					: Optional.of(existing.visibility());
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		} catch (RuntimeException | ReflectiveOperationException | LinkageError exception) {
			XaeroMapsync_r.LOGGER.debug("Failed to inspect selected Xaero waypoint sharing state", exception);
			return Optional.empty();
		}
	}

	@Override
	public void clearPendingMutations() {
		Set<UUID> pendingIds = new HashSet<>(pendingCreates.keySet());
		pendingCreates.clear();
		if (pendingIds.isEmpty()) {
			return;
		}
		try {
			XaeroWaypointBridge.Target target = bridge.currentTarget();
			boolean changed = false;
			for (UUID id : pendingIds) {
				Object source = boundSources.get(id);
				if (source == null) {
					continue;
				}
				WaypointValues values = bridge.read(source);
				if (XaeroWaypointIdentity.parse(values.name).filter(id::equals).isPresent()) {
					bridge.update(source, new WaypointValues(values.x, values.y, values.z,
							XaeroWaypointIdentity.displayName(values.name),
							values.symbol, values.color));
					changed = true;
				}
				unbind(id);
			}
			if (changed) {
				bridge.save(target.world());
			}
		} catch (RuntimeException | ReflectiveOperationException | LinkageError exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to clear pending Xaero waypoint lock state", exception);
			for (UUID id : pendingIds) {
				unbind(id);
			}
		}
	}

	private XaeroWaypointBridge.SelectedWaypoint selectedWaypoint(Object screen) {
		try {
			return bridge.selectedWaypoint(screen);
		} catch (RuntimeException | ReflectiveOperationException | LinkageError exception) {
			if (exception instanceof IllegalArgumentException invalidSelection) {
				throw invalidSelection;
			}
			String message = "Failed to read the selected Xaero waypoint";
			XaeroMapsync_r.LOGGER.error(message, exception);
			throw new IllegalStateException(message + ": " + exception.getMessage(), exception);
		}
	}

	private PublicWaypoint findSelectedRecord(XaeroWaypointBridge.SelectedWaypoint selected,
			Map<UUID, PublicWaypoint> known, UUID playerId) {
		UUID boundId = sourceIds.get(selected.nativeWaypoint());
		if (boundId == null) {
			boundId = managedSourceIds.get(selected.nativeWaypoint());
		}
		if (boundId != null) {
			PublicWaypoint bound = known.get(boundId);
			if (bound != null && !bound.deleted()) {
				return bound;
			}
			if (pendingCreates.containsKey(boundId)) {
				return null;
			}
			unbind(boundId);
		}
		UUID markerId = XaeroWaypointIdentity.parse(selected.values().name).orElse(null);
		if (markerId != null) {
			PublicWaypoint marked = known.get(markerId);
			if (marked != null && !marked.deleted()) {
				return marked;
			}
		}
		PublicWaypoint match = null;
		for (PublicWaypoint waypoint : known.values()) {
			if (!waypoint.deleted() && playerId.equals(waypoint.creatorId()) && matches(selected, waypoint)) {
				if (match != null) {
					throw new IllegalArgumentException("Multiple shared waypoints match the selected Xaero waypoint");
				}
				match = waypoint;
			}
		}
		return match;
	}

	private static Map<UUID, PublicWaypoint> index(Collection<PublicWaypoint> waypoints) {
		Map<UUID, PublicWaypoint> result = new HashMap<>();
		for (PublicWaypoint waypoint : waypoints) {
			if (waypoint != null && waypoint.id() != null) {
				result.put(waypoint.id(), waypoint);
			}
		}
		return result;
	}

	private static boolean matches(XaeroWaypointBridge.SelectedWaypoint selected, PublicWaypoint waypoint) {
		WaypointValues values = selected.values();
		return Objects.equals(selected.dimension(), waypoint.dimension())
				&& Objects.equals(selected.category(), waypoint.category())
				&& values.x == coordinate(waypoint.x())
				&& values.y == coordinate(waypoint.y())
				&& values.z == coordinate(waypoint.z())
				&& Objects.equals(XaeroWaypointIdentity.displayName(values.name), waypoint.name())
				&& Objects.equals(normalizeSymbol(values.symbol, waypoint.name()), normalizeSymbol(waypoint.symbol(), waypoint.name()))
				&& XaeroWaypointPalette.normalize(values.color) == XaeroWaypointPalette.normalize(waypoint.color());
	}

	private Object findMatchingSource(Map<String, List<Object>> waypointSets, PublicWaypoint waypoint)
			throws ReflectiveOperationException {
		List<Object> points = waypointSets.get(waypoint.category());
		if (points == null) {
			return null;
		}
		for (Object point : points) {
			WaypointValues values = bridge.read(point);
			if (XaeroWaypointIdentity.parse(values.name).isEmpty()
					&& values.equals(toSourceValues(waypoint))) {
				return point;
			}
		}
		return null;
	}

	private void requireMutationContext(Collection<PublicWaypoint> knownWaypoints, UUID playerId) {
		if (!available) {
			throw new IllegalStateException("Xaero waypoint adapter is disabled");
		}
		if (!clientThread.getAsBoolean()) {
			throw new IllegalStateException("Xaero waypoint changes must run on the client main thread");
		}
		Objects.requireNonNull(knownWaypoints, "Known waypoints are required");
		Objects.requireNonNull(playerId, "Player id is required");
	}

	private void bind(UUID id, Object source) {
		unbindManaged(id);
		UUID managedId = managedSourceIds.remove(source);
		if (managedId != null) {
			managedSources.remove(managedId);
		}
		Object previousSource = boundSources.put(id, source);
		if (previousSource != null && previousSource != source) {
			sourceIds.remove(previousSource);
		}
		UUID previousId = sourceIds.put(source, id);
		if (previousId != null && !previousId.equals(id)) {
			boundSources.remove(previousId);
		}
	}

	private void unbind(UUID id) {
		Object source = boundSources.remove(id);
		if (source != null) {
			sourceIds.remove(source);
		}
		sourceCategories.remove(id);
		unbindManaged(id);
	}

	private void bindManaged(UUID id, Object source) {
		if (boundSources.get(id) == source) {
			return;
		}
		Object previousSource = managedSources.put(id, source);
		if (previousSource != null && previousSource != source) {
			managedSourceIds.remove(previousSource);
		}
		UUID previousId = managedSourceIds.put(source, id);
		if (previousId != null && !previousId.equals(id)) {
			managedSources.remove(previousId);
		}
	}

	private void unbindManaged(UUID id) {
		Object source = managedSources.remove(id);
		if (source != null) {
			managedSourceIds.remove(source);
		}
	}

	private void persistIdentity(XaeroWaypointBridge.SelectedWaypoint selected, UUID id) {
		WaypointValues values = selected.values();
		String managedName = XaeroWaypointIdentity.managedName(XaeroWaypointIdentity.displayName(values.name), id);
		if (managedName.equals(values.name)) {
			return;
		}
		try {
			bridge.update(selected.nativeWaypoint(), new WaypointValues(values.x, values.y, values.z, managedName,
					values.symbol, XaeroWaypointPalette.normalize(values.color)));
			bridge.save(selected.world());
		} catch (RuntimeException | ReflectiveOperationException | LinkageError exception) {
			throw new IllegalStateException("Failed to persist the shared waypoint identity", exception);
		}
	}

	private static boolean isNotReady(Throwable exception) {
		for (Throwable current = exception; current != null; current = current.getCause()) {
			if (current instanceof IllegalStateException && current.getMessage() != null
					&& current.getMessage().contains("not initialized")) {
				return true;
			}
		}
		return false;
	}

	private void rollback(XaeroWaypointBridge.Target target, Map<String, List<Object>> originalOrders,
			Map<Object, WaypointValues> originalValues) {
		if (target == null || originalOrders == null) {
			return;
		}
		try {
			for (Map.Entry<Object, WaypointValues> entry : originalValues.entrySet()) {
				bridge.update(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, List<Object>> entry : originalOrders.entrySet()) {
				List<Object> waypoints = target.waypointSets().get(entry.getKey());
				if (waypoints != null) {
					waypoints.clear();
					waypoints.addAll(entry.getValue());
				}
			}
			if (target.publicSetCreated()) {
				bridge.removeSet(target.world(), XaeroWaypointBridge.PUBLIC_WAYPOINT_SET);
			}
		} catch (RuntimeException | ReflectiveOperationException | LinkageError rollbackException) {
			XaeroMapsync_r.LOGGER.error("Failed to roll back Xaero waypoint batch after reconciliation error", rollbackException);
		}
	}

	private static Map<String, List<Object>> snapshotOrders(Map<String, List<Object>> waypointSets) {
		Map<String, List<Object>> snapshot = new HashMap<>();
		for (Map.Entry<String, List<Object>> entry : waypointSets.entrySet()) {
			snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}
		return snapshot;
	}

	private static boolean containsIdentity(Map<String, List<Object>> waypointSets, Object waypoint) {
		for (List<Object> setWaypoints : waypointSets.values()) {
			for (Object candidate : setWaypoints) {
				if (candidate == waypoint) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean moveToSet(Map<String, List<Object>> waypointSets, Object waypoint, String targetSet) {
		List<Object> destination = waypointSets.get(targetSet);
		if (destination == null) {
			throw new IllegalStateException("Xaero waypoint set is not initialized: " + targetSet);
		}
		int totalOccurrences = 0;
		int destinationOccurrences = 0;
		for (Map.Entry<String, List<Object>> entry : waypointSets.entrySet()) {
			for (Object candidate : entry.getValue()) {
				if (candidate == waypoint) {
					totalOccurrences++;
					if (entry.getValue() == destination) {
						destinationOccurrences++;
					}
				}
			}
		}
		if (totalOccurrences == 1 && destinationOccurrences == 1) {
			return false;
		}
		removeFromAllSets(waypointSets, waypoint);
		destination.add(waypoint);
		return true;
	}

	private static boolean removeFromAllSets(Map<String, List<Object>> waypointSets, Object waypoint) {
		boolean removed = false;
		for (List<Object> setWaypoints : waypointSets.values()) {
			for (int index = setWaypoints.size() - 1; index >= 0; index--) {
				if (setWaypoints.get(index) == waypoint) {
					setWaypoints.remove(index);
					removed = true;
				}
			}
		}
		return removed;
	}

	private static boolean isEligible(PublicWaypoint waypoint, String dimension) {
		return waypoint != null
				&& !waypoint.deleted()
				&& (waypoint.visibility() == WaypointVisibility.PUBLIC || waypoint.visibility() == WaypointVisibility.TEAM)
				&& Objects.equals(dimension, waypoint.dimension());
	}

	private static WaypointValues toManagedValues(PublicWaypoint waypoint) {
		return new WaypointValues(coordinate(waypoint.x()), coordinate(waypoint.y()), coordinate(waypoint.z()),
				XaeroWaypointIdentity.managedName(waypoint.name(), waypoint.id()),
				normalizeSymbol(waypoint.symbol(), waypoint.name()), XaeroWaypointPalette.normalize(waypoint.color()));
	}

	private static WaypointValues toSourceValues(PublicWaypoint waypoint) {
		return new WaypointValues(coordinate(waypoint.x()), coordinate(waypoint.y()), coordinate(waypoint.z()),
				waypoint.name(), normalizeSymbol(waypoint.symbol(), waypoint.name()),
				XaeroWaypointPalette.normalize(waypoint.color()));
	}

	private static int coordinate(double value) {
		double floored = Math.floor(value);
		if (floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Waypoint coordinate is outside Xaero's integer range: " + value);
		}
		return (int) floored;
	}

	private static String normalizeSymbol(String symbol, String name) {
		if (symbol != null && !symbol.isBlank()) {
			return symbol;
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Waypoint name is required to derive a symbol");
		}
		return name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
	}
}
