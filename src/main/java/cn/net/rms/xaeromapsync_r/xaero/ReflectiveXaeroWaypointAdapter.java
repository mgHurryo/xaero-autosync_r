package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class ReflectiveXaeroWaypointAdapter implements XaeroWaypointAdapter {
	private final XaeroWaypointBridge bridge;
	private final Supplier<String> currentDimension;
	private final BooleanSupplier clientThread;
	private boolean available = true;

	ReflectiveXaeroWaypointAdapter(XaeroWaypointBridge bridge, Supplier<String> currentDimension, BooleanSupplier clientThread) {
		this.bridge = bridge;
		this.currentDimension = currentDimension;
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
		List<Object> originalOrder = null;
		Map<Object, WaypointValues> originalValues = new IdentityHashMap<>();
		try {
			String dimension = currentDimension.get();
			if (dimension == null) {
				throw new IllegalStateException("Minecraft client world is not initialized");
			}
			Map<UUID, WaypointValues> desired = new HashMap<>();
			for (PublicWaypoint waypoint : waypoints) {
				if (!isEligible(waypoint, dimension)) {
					ignored++;
					continue;
				}
				waypoint.validate();
				desired.put(waypoint.id(), toValues(waypoint));
			}

			target = bridge.currentTarget();
			List<Object> list = target.waypoints();
			originalOrder = new ArrayList<>(list);
			Map<UUID, Object> managed = new HashMap<>();
			List<Object> remove = new ArrayList<>();
			for (Object xaeroWaypoint : new ArrayList<>(list)) {
				WaypointValues currentValues = bridge.read(xaeroWaypoint);
				UUID id = XaeroWaypointIdentity.parse(currentValues.name).orElse(null);
				if (id == null) {
					continue;
				}
				originalValues.put(xaeroWaypoint, currentValues);
				if (managed.putIfAbsent(id, xaeroWaypoint) != null || !desired.containsKey(id)) {
					remove.add(xaeroWaypoint);
				}
			}

			int created = 0;
			int updated = 0;
			for (Map.Entry<UUID, WaypointValues> entry : desired.entrySet()) {
				Object existing = managed.get(entry.getKey());
				if (existing == null) {
					list.add(bridge.create(entry.getValue()));
					created++;
				} else if (!entry.getValue().equals(bridge.read(existing))) {
					bridge.update(existing, entry.getValue());
					updated++;
				}
			}
			list.removeAll(remove);
			int deleted = remove.size();
			boolean changed = created != 0 || updated != 0 || deleted != 0;
			if (changed) {
				bridge.save(target.world());
			}
			XaeroMapsync_r.LOGGER.info("Xaero waypoint reconcile completed: created={}, updated={}, deleted={}, ignored={}, saved={}", created, updated, deleted, ignored, changed);
			return XaeroWaypointReconcileResult.completed(created, updated, deleted, ignored, changed);
		} catch (RuntimeException | ReflectiveOperationException | LinkageError exception) {
			rollback(target, originalOrder, originalValues);
			available = false;
			String message = "Xaero waypoint reconciliation failed; adapter disabled for this session";
			XaeroMapsync_r.LOGGER.error(message, exception);
			return XaeroWaypointReconcileResult.failed(ignored, message + ": " + exception.getMessage());
		}
	}

	private void rollback(XaeroWaypointBridge.Target target, List<Object> originalOrder, Map<Object, WaypointValues> originalValues) {
		if (target == null || originalOrder == null) {
			return;
		}
		try {
			for (Map.Entry<Object, WaypointValues> entry : originalValues.entrySet()) {
				bridge.update(entry.getKey(), entry.getValue());
			}
			target.waypoints().clear();
			target.waypoints().addAll(originalOrder);
		} catch (RuntimeException | ReflectiveOperationException | LinkageError rollbackException) {
			XaeroMapsync_r.LOGGER.error("Failed to roll back Xaero waypoint batch after reconciliation error", rollbackException);
		}
	}

	private static boolean isEligible(PublicWaypoint waypoint, String dimension) {
		return waypoint != null
			&& !waypoint.deleted()
			&& waypoint.visibility() == WaypointVisibility.PUBLIC
			&& Objects.equals(dimension, waypoint.dimension());
	}

	private static WaypointValues toValues(PublicWaypoint waypoint) {
		return new WaypointValues(
			coordinate(waypoint.x()),
			coordinate(waypoint.y()),
			coordinate(waypoint.z()),
			XaeroWaypointIdentity.managedName(waypoint.name(), waypoint.id()),
			normalizeSymbol(waypoint.symbol(), waypoint.name()),
			waypoint.color()
		);
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
		return name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
	}
}
