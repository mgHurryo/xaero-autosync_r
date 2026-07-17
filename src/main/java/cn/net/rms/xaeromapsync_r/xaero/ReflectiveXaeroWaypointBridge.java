package cn.net.rms.xaeromapsync_r.xaero;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;

final class ReflectiveXaeroWaypointBridge implements XaeroWaypointBridge {
	private final Method getCurrentSession;
	private final Method getWaypointsManager;
	private final Method getModMain;
	private final Method getCurrentWorld;
	private final Method getCurrentSet;
	private final Method getSets;
	private final Method addSet;
	private final Method getList;
	private final Method getSetName;
	private final Method getWorldId;
	private final Method getDimensionKey;
	private final Class<?> waypointScreenClass;
	private final Method getSelectedWaypoints;
	private final Field displayedWorld;
	private final Field selectedListSet;
	private final Method getSettings;
	private final Method saveWaypoints;
	private final Constructor<?> waypointConstructor;
	private final Method getX;
	private final Method getY;
	private final Method getZ;
	private final Method getName;
	private final Method getSymbol;
	private final Method getColor;
	private final Method isServerWaypoint;
	private final Method setX;
	private final Method setY;
	private final Method setZ;
	private final Method setName;
	private final Method setSymbol;
	private final Method setColor;

	ReflectiveXaeroWaypointBridge(ClassLoader classLoader) throws ReflectiveOperationException {
		Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession", false, classLoader);
		Class<?> managerClass = Class.forName("xaero.common.minimap.waypoints.WaypointsManager", false, classLoader);
		Class<?> worldClass = Class.forName("xaero.common.minimap.waypoints.WaypointWorld", false, classLoader);
		Class<?> setClass = Class.forName("xaero.common.minimap.waypoints.WaypointSet", false, classLoader);
		Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint", false, classLoader);
		Class<?> modMainClass = Class.forName("xaero.common.AXaeroMinimap", false, classLoader);
		Class<?> settingsClass = Class.forName("xaero.common.settings.ModSettings", false, classLoader);

		getCurrentSession = requireMethod(sessionClass, "getCurrentSession", sessionClass);
		if (!Modifier.isStatic(getCurrentSession.getModifiers())) {
			throw new NoSuchMethodException("XaeroMinimapSession.getCurrentSession must be static");
		}
		getWaypointsManager = requireMethod(sessionClass, "getWaypointsManager", managerClass);
		getModMain = requireMethod(sessionClass, "getModMain", modMainClass);
		getCurrentWorld = requireMethod(managerClass, "getCurrentWorld", worldClass);
		getCurrentSet = requireMethod(worldClass, "getCurrentSet", setClass);
		getSets = requireMethod(worldClass, "getSets", HashMap.class);
		addSet = requireMethod(worldClass, "addSet", void.class, String.class);
		getList = requireMethod(setClass, "getList", java.util.ArrayList.class);
		getSetName = requireMethod(setClass, "getName", String.class);
		getWorldId = requireMethod(worldClass, "getId", String.class);
		getDimensionKey = requireMethod(managerClass, "getDimensionKeyForDirectoryName", ResourceKey.class, String.class);
		waypointScreenClass = Class.forName("xaero.common.gui.GuiWaypoints", false, classLoader);
		getSelectedWaypoints = waypointScreenClass.getDeclaredMethod("getSelectedWaypointsList");
		getSelectedWaypoints.setAccessible(true);
		displayedWorld = waypointScreenClass.getDeclaredField("displayedWorld");
		displayedWorld.setAccessible(true);
		selectedListSet = waypointScreenClass.getDeclaredField("selectedListSet");
		selectedListSet.setAccessible(true);
		getSettings = requireMethod(modMainClass, "getSettings", settingsClass);
		saveWaypoints = requireMethod(settingsClass, "saveWaypoints", void.class, worldClass);
		waypointConstructor = waypointClass.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);
		getX = requireMethod(waypointClass, "getX", int.class);
		getY = requireMethod(waypointClass, "getY", int.class);
		getZ = requireMethod(waypointClass, "getZ", int.class);
		getName = requireMethod(waypointClass, "getName", String.class);
		getSymbol = requireMethod(waypointClass, "getSymbol", String.class);
		getColor = requireMethod(waypointClass, "getColor", int.class);
		isServerWaypoint = requireMethod(waypointClass, "isServerWaypoint", boolean.class);
		setX = requireMethod(waypointClass, "setX", void.class, int.class);
		setY = requireMethod(waypointClass, "setY", void.class, int.class);
		setZ = requireMethod(waypointClass, "setZ", void.class, int.class);
		setName = requireMethod(waypointClass, "setName", void.class, String.class);
		setSymbol = requireMethod(waypointClass, "setSymbol", void.class, String.class);
		setColor = requireMethod(waypointClass, "setColor", void.class, int.class);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Target currentTarget() throws ReflectiveOperationException {
		Object world = currentWorld();
		Map<String, Object> sets = (Map<String, Object>) invoke(getSets, world);
		if (sets == null) {
			throw new IllegalStateException("Xaero waypoint sets are not initialized");
		}
		boolean publicSetCreated = false;
		if (!sets.containsKey(PUBLIC_WAYPOINT_SET)) {
			invoke(addSet, world, PUBLIC_WAYPOINT_SET);
			publicSetCreated = true;
		}
		Object publicSet = sets.get(PUBLIC_WAYPOINT_SET);
		if (publicSet == null) {
			throw new IllegalStateException("Xaero public waypoint set was not created");
		}
		Map<String, List<Object>> waypointSets = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : sets.entrySet()) {
			waypointSets.put(entry.getKey(), (List<Object>) invoke(getList, entry.getValue()));
		}
		return new Target(world, waypointSets, publicSetCreated);
	}

	@Override
	@SuppressWarnings("unchecked")
	public SelectedWaypoint selectedWaypoint(Object screen) throws ReflectiveOperationException {
		if (!waypointScreenClass.isInstance(screen)) {
			throw new IllegalArgumentException("The active screen is not Xaero's waypoint manager");
		}
		List<Object> selected = (List<Object>) invoke(getSelectedWaypoints, screen);
		if (selected.size() != 1) {
			throw new IllegalArgumentException("Select exactly one Xaero waypoint");
		}
		Object world = displayedWorld.get(screen);
		if (world == null) {
			throw new IllegalStateException("Xaero displayed waypoint world is not initialized");
		}
		Object set = invoke(getCurrentSet, world);
		if (set == null) {
			throw new IllegalStateException("Xaero displayed waypoint set is not initialized");
		}
		String category = (String) invoke(getSetName, set);
		Object waypoint = selected.get(0);
		if ((boolean) invoke(isServerWaypoint, waypoint)) {
			throw new IllegalArgumentException("Xaero server waypoints cannot be re-shared");
		}
		Object manager = currentManager();
		ResourceKey<?> dimension = (ResourceKey<?>) invoke(getDimensionKey, manager, invoke(getWorldId, world));
		if (dimension == null && world == currentWorld()) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.level != null) {
				dimension = minecraft.level.dimension();
			}
		}
		if (dimension == null) {
			throw new IllegalArgumentException("The selected Xaero waypoint world is not a Minecraft dimension");
		}
		return new SelectedWaypoint(waypoint, world, readValues(waypoint), category, dimension.location().toString());
	}

	@Override
	public Object create(WaypointValues values) throws ReflectiveOperationException {
		try {
			return waypointConstructor.newInstance(values.x, values.y, values.z, values.name, values.symbol, values.color);
		} catch (InvocationTargetException exception) {
			throw unwrap(exception);
		}
	}

	@Override
	public WaypointValues read(Object waypoint) throws ReflectiveOperationException {
		return readValues(waypoint);
	}

	private WaypointValues readValues(Object waypoint) throws ReflectiveOperationException {
		return new WaypointValues(
			(int) invoke(getX, waypoint),
			(int) invoke(getY, waypoint),
			(int) invoke(getZ, waypoint),
			(String) invoke(getName, waypoint),
			(String) invoke(getSymbol, waypoint),
			(int) invoke(getColor, waypoint)
		);
	}

	private Object currentWorld() throws ReflectiveOperationException {
		Object manager = currentManager();
		Object world = invoke(getCurrentWorld, manager);
		if (world == null) {
			throw new IllegalStateException("Xaero current waypoint world is not initialized");
		}
		return world;
	}

	private Object currentManager() throws ReflectiveOperationException {
		Object session = invoke(getCurrentSession, null);
		if (session == null) {
			throw new IllegalStateException("Xaero minimap session is not initialized");
		}
		Object manager = invoke(getWaypointsManager, session);
		if (manager == null) {
			throw new IllegalStateException("Xaero waypoints manager is not initialized");
		}
		return manager;
	}

	@Override
	public void update(Object waypoint, WaypointValues values) throws ReflectiveOperationException {
		invoke(setX, waypoint, values.x);
		invoke(setY, waypoint, values.y);
		invoke(setZ, waypoint, values.z);
		invoke(setName, waypoint, values.name);
		invoke(setSymbol, waypoint, values.symbol);
		invoke(setColor, waypoint, values.color);
	}

	@Override
	public void save(Object world) throws ReflectiveOperationException {
		Object session = invoke(getCurrentSession, null);
		Object modMain = invoke(getModMain, session);
		Object settings = invoke(getSettings, modMain);
		invoke(saveWaypoints, settings, world);
	}

	@Override
	public void clearWaypointScreenSelection() throws ReflectiveOperationException {
		Object screen = Minecraft.getInstance().screen;
		if (!waypointScreenClass.isInstance(screen)) {
			return;
		}
		Object selection = selectedListSet.get(screen);
		if (selection instanceof ConcurrentSkipListSet<?>) {
			((ConcurrentSkipListSet<?>) selection).clear();
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void removeSet(Object world, String setKey) throws ReflectiveOperationException {
		Map<String, Object> sets = (Map<String, Object>) invoke(getSets, world);
		if (sets != null) {
			sets.remove(setKey);
		}
	}

	private static Method requireMethod(Class<?> owner, String name, Class<?> returnType, Class<?>... parameterTypes) throws NoSuchMethodException {
		Method method = owner.getMethod(name, parameterTypes);
		if (method.getReturnType() != returnType) {
			throw new NoSuchMethodException(owner.getName() + "." + name + " has return type " + method.getReturnType().getName() + ", expected " + returnType.getName());
		}
		return method;
	}

	private static Object invoke(Method method, Object target, Object... arguments) throws ReflectiveOperationException {
		try {
			return method.invoke(target, arguments);
		} catch (InvocationTargetException exception) {
			throw unwrap(exception);
		}
	}

	private static ReflectiveOperationException unwrap(InvocationTargetException exception) throws ReflectiveOperationException {
		Throwable cause = exception.getCause();
		if (cause instanceof ReflectiveOperationException) {
			return (ReflectiveOperationException) cause;
		}
		if (cause instanceof RuntimeException) {
			throw (RuntimeException) cause;
		}
		if (cause instanceof Error) {
			throw (Error) cause;
		}
		return new ReflectiveOperationException("Xaero invocation failed", cause);
	}
}
