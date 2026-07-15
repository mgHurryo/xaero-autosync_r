package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Reflection-only bridge for Xaero's World Map 1.25.1. */
public final class ReflectiveXaeroMapAdapter implements XaeroMapAdapter {
	static final String XAERO_MOD_ID = "xaeroworldmap";
	static final String SUPPORTED_VERSION = "1.25.1";
	private static final int TILE_SIDE = 16;
	private static final int TILE_AREA = TILE_SIDE * TILE_SIDE;

	private final XaeroRuntime runtime;
	private boolean available;
	private long nextNotReadyLogMillis;

	public ReflectiveXaeroMapAdapter() {
		XaeroRuntime resolvedRuntime = null;
		try {
			ModContainer xaero = FabricLoader.getInstance().getModContainer(XAERO_MOD_ID).orElse(null);
			if (xaero == null) {
				XaeroMapsync_r.LOGGER.info("Xaero World Map is not installed; reflective map injection is disabled");
			} else {
				String version = xaero.getMetadata().getVersion().getFriendlyString();
				if (!supportsVersion(version)) {
					XaeroMapsync_r.LOGGER.warn("Unsupported Xaero World Map version {}; expected {}. Map injection is disabled", version, SUPPORTED_VERSION);
				} else {
					resolvedRuntime = new Xaero1251Runtime();
				}
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
			XaeroMapsync_r.LOGGER.error("Xaero World Map 1.25.1 signature validation failed; map injection is disabled", exception);
		}
		runtime = resolvedRuntime;
		available = resolvedRuntime != null;
	}

	ReflectiveXaeroMapAdapter(XaeroRuntime runtime) {
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		this.available = true;
	}

	@Override
	public boolean isAvailable() {
		return available;
	}

	@Override
	public boolean apply(MapTile tile) {
		if (!available) {
			return false;
		}
		try {
			validateTile(tile);
			runtime.apply(tile);
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
			if (isNotReady(exception)) {
				long now = System.currentTimeMillis();
				if (now >= nextNotReadyLogMillis) {
					nextNotReadyLogMillis = now + 5_000L;
					XaeroMapsync_r.LOGGER.info("Xaero map injection deferred: {}", exception.getMessage());
				}
				return false;
			}
			available = false;
			XaeroMapsync_r.LOGGER.error("Xaero map tile injection failed; adapter disabled for this session", exception);
			return false;
		}
	}

	private static boolean isNotReady(Throwable exception) {
		return exception instanceof IllegalStateException && exception.getMessage() != null
				&& (exception.getMessage().contains("not initialized")
						|| exception.getMessage().contains("not loaded yet")
						|| exception.getMessage().contains("not fully loaded")
						|| exception.getMessage().contains("save is pending")
						|| exception.getMessage().contains("cache is pending")
						|| exception.getMessage().contains("writing is paused"));
	}

	static boolean supportsVersion(String version) {
		return SUPPORTED_VERSION.equals(version);
	}

	static int regionCoordinate(int chunkCoordinate) {
		return Math.floorDiv(chunkCoordinate, 32);
	}

	static int tileChunkCoordinate(int chunkCoordinate) {
		return Math.floorDiv(chunkCoordinate, 4);
	}

	static boolean isCurrentDimension(String tileDimension, String currentDimension) {
		return Objects.equals(tileDimension, currentDimension);
	}

	private static void validateTile(MapTile tile) {
		Objects.requireNonNull(tile, "tile");
		if (tile.dimension() == null || tile.dimension().isBlank()) {
			throw new IllegalArgumentException("Map tile dimension is missing");
		}
		if (tile.baseStateIds().length != TILE_AREA || tile.baseHeights().length != TILE_AREA
				|| tile.topHeights().length != TILE_AREA || tile.biomeIds().length != TILE_AREA
				|| tile.lightAbove().length != TILE_AREA || tile.glowing().length != TILE_AREA
				|| tile.cave().length != TILE_AREA || tile.overlays().size() != TILE_AREA) {
			throw new IllegalArgumentException("Xaero map injection requires 16x16 surface arrays");
		}
	}

	@FunctionalInterface
	interface XaeroRuntime {
		void apply(MapTile tile) throws ReflectiveOperationException;
	}

	private static final class Xaero1251Runtime implements XaeroRuntime {
		private final Class<?> mapProcessorClass;
		private final Class<?> mapRegionClass;
		private final Class<?> mapTileChunkClass;
		private final Class<?> xaeroMapTileClass;
		private final Class<?> mapBlockClass;
		private final Class<?> biomeKeyClass;

		private final Method currentSession;
		private final Method getMapProcessor;
		private final Method getMapRegion;
		private final Method getTilePool;
		private final Method getBlockStateShortShapeCache;
		private final Method getMapSaveLoad;
		private final Method getCurrentDimension;
		private final Method waitForLoadingToFinish;
		private final Method getChunk;
		private final Method setChunk;
		private final Method getRegionLoadState;
		private final Method isBeingWritten;
		private final Method setBeingWritten;
		private final Method isWritingPaused;
		private final Field lastSaveTimeField;
		private final Method isAllCachePrepared;
		private final Method setAllCachePrepared;
		private final Method isRefreshing;
		private final Method requestRefresh;
		private final Method cancelRefresh;
		private final Constructor<?> mapTileChunkConstructor;
		private final Method getChunkLoadState;
		private final Method setChunkLoadState;
		private final Method getTile;
		private final Method setTile;
		private final Method wasChanged;
		private final Method setChanged;
		private final Method setHasHadTerrain;
		private final Method tilePoolGet;
		private final Method setBlock;
		private final Method setLoaded;
		private final Method setWrittenOnce;
		private final Method setWorldInterpretationVersion;
		private final Constructor<?> mapBlockConstructor;
		private final Method writeBlock;
		private final Constructor<?> overlayConstructor;
		private final Method addOverlay;
		private final Field biomeKeyManagerField;
		private final Method biomeKeyGet;
		private final Method requestLoad;
		private final Method reloadHasBeenRequested;
		private final Method toCacheContains;
		private final Method removeToCache;
		private final Method getToSave;
		private final Field writerThreadPauseSyncField;
		private final Field chunkIncludeInSaveField;
		private final Field chunkHasHadTerrainField;
		private final Field regionHasHadTerrainField;

		Xaero1251Runtime() throws ReflectiveOperationException {
			ClassLoader loader = ReflectiveXaeroMapAdapter.class.getClassLoader();
			Class<?> sessionClass = Class.forName("xaero.map.WorldMapSession", false, loader);
			mapProcessorClass = Class.forName("xaero.map.MapProcessor", false, loader);
			mapRegionClass = Class.forName("xaero.map.region.MapRegion", false, loader);
			Class<?> leveledRegionClass = Class.forName("xaero.map.region.LeveledRegion", false, loader);
			mapTileChunkClass = Class.forName("xaero.map.region.MapTileChunk", false, loader);
			xaeroMapTileClass = Class.forName("xaero.map.region.MapTile", false, loader);
			mapBlockClass = Class.forName("xaero.map.region.MapBlock", false, loader);
			Class<?> overlayClass = Class.forName("xaero.map.region.Overlay", false, loader);
			biomeKeyClass = Class.forName("xaero.map.biome.BiomeKey", false, loader);
			Class<?> biomeKeyManagerClass = Class.forName("xaero.map.biome.BiomeKeyManager", false, loader);
			Class<?> mapTilePoolClass = Class.forName("xaero.map.pool.MapTilePool", false, loader);
			Class<?> blockStateShortShapeCacheClass = Class.forName("xaero.map.cache.BlockStateShortShapeCache", false, loader);
			Class<?> mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad", false, loader);

			currentSession = method(sessionClass, sessionClass, "getCurrentSession");
			getMapProcessor = method(sessionClass, mapProcessorClass, "getMapProcessor");
			getMapRegion = method(mapProcessorClass, mapRegionClass, "getMapRegion", int.class, int.class, boolean.class);
			getTilePool = method(mapProcessorClass, mapTilePoolClass, "getTilePool");
			getBlockStateShortShapeCache = method(mapProcessorClass, blockStateShortShapeCacheClass, "getBlockStateShortShapeCache");
			getMapSaveLoad = method(mapProcessorClass, mapSaveLoadClass, "getMapSaveLoad");
			getCurrentDimension = method(mapProcessorClass, String.class, "getCurrentDimension");
			waitForLoadingToFinish = method(mapProcessorClass, "waitForLoadingToFinish", Runnable.class);
			getChunk = method(mapRegionClass, mapTileChunkClass, "getChunk", int.class, int.class);
			setChunk = method(mapRegionClass, "setChunk", int.class, int.class, mapTileChunkClass);
			getRegionLoadState = method(mapRegionClass, byte.class, "getLoadState");
			isBeingWritten = method(mapRegionClass, "isBeingWritten");
			setBeingWritten = method(mapRegionClass, "setBeingWritten", boolean.class);
			isWritingPaused = method(mapRegionClass, "isWritingPaused");
			lastSaveTimeField = field(leveledRegionClass, "lastSaveTime", long.class);
			isAllCachePrepared = method(leveledRegionClass, "isAllCachePrepared");
			setAllCachePrepared = method(leveledRegionClass, "setAllCachePrepared", boolean.class);
			isRefreshing = method(mapRegionClass, "isRefreshing");
			requestRefresh = method(mapRegionClass, "requestRefresh", mapProcessorClass);
			cancelRefresh = method(mapRegionClass, "cancelRefresh", mapProcessorClass);
			mapTileChunkConstructor = constructor(mapTileChunkClass, mapRegionClass, int.class, int.class);
			getChunkLoadState = method(mapTileChunkClass, int.class, "getLoadState");
			setChunkLoadState = method(mapTileChunkClass, "setLoadState", byte.class);
			getTile = method(mapTileChunkClass, xaeroMapTileClass, "getTile", int.class, int.class);
			setTile = method(mapTileChunkClass, "setTile", int.class, int.class, xaeroMapTileClass, blockStateShortShapeCacheClass);
			wasChanged = method(mapTileChunkClass, "wasChanged");
			setChanged = method(mapTileChunkClass, "setChanged", boolean.class);
			setHasHadTerrain = method(mapTileChunkClass, "setHasHadTerrain");
			tilePoolGet = method(mapTilePoolClass, xaeroMapTileClass, "get", String.class, int.class, int.class);
			setBlock = method(xaeroMapTileClass, "setBlock", int.class, int.class, mapBlockClass);
			setLoaded = method(xaeroMapTileClass, "setLoaded", boolean.class);
			setWrittenOnce = method(xaeroMapTileClass, "setWrittenOnce", boolean.class);
			setWorldInterpretationVersion = method(xaeroMapTileClass, "setWorldInterpretationVersion", int.class);
			mapBlockConstructor = constructor(mapBlockClass);
			writeBlock = method(mapBlockClass, "write", BlockState.class, int.class, int.class, biomeKeyClass, byte.class, boolean.class, boolean.class);
			overlayConstructor = constructor(overlayClass, BlockState.class, float.class, byte.class, boolean.class);
			addOverlay = method(mapBlockClass, "addOverlay", overlayClass);
			biomeKeyManagerField = field(mapSaveLoadClass, "biomeKeyManager", biomeKeyManagerClass);
			biomeKeyGet = method(biomeKeyManagerClass, biomeKeyClass, "get", String.class);
			requestLoad = method(mapSaveLoadClass, "requestLoad", mapRegionClass, String.class);
			reloadHasBeenRequested = method(leveledRegionClass, "reloadHasBeenRequested");
			toCacheContains = method(mapSaveLoadClass, "toCacheContains", leveledRegionClass);
			removeToCache = method(mapSaveLoadClass, "removeToCache", leveledRegionClass);
			getToSave = method(mapSaveLoadClass, "getToSave");
			writerThreadPauseSyncField = field(mapRegionClass, "writerThreadPauseSync", Object.class);
			chunkIncludeInSaveField = field(mapTileChunkClass, "includeInSave", boolean.class);
			chunkHasHadTerrainField = field(mapTileChunkClass, "hasHadTerrain", boolean.class);
			regionHasHadTerrainField = field(mapRegionClass, "hasHadTerrain", boolean.class);
		}

		@Override
		public void apply(MapTile source) throws ReflectiveOperationException {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.level == null) {
				throw new IllegalStateException("Minecraft client world is not initialized");
			}
			String currentDimensionId = minecraft.level.dimension().location().toString();
			if (!isCurrentDimension(source.dimension(), currentDimensionId)) {
				XaeroMapsync_r.LOGGER.debug("Skipping Xaero map tile for inactive dimension {} (current: {})", source.dimension(), currentDimensionId);
				return;
			}

			Object session = invoke(currentSession, null);
			if (session == null) {
				throw new IllegalStateException("Xaero WorldMapSession is not initialized");
			}
			Object processor = invoke(getMapProcessor, session);
			if (processor == null) {
				throw new IllegalStateException("Xaero MapProcessor is not initialized");
			}
			Object saveLoad = invoke(getMapSaveLoad, processor);
			Object biomeKeyManager = biomeKeyManagerField.get(saveLoad);
			Object xaeroTile = createTile(source, processor, biomeKeyManager, minecraft);
			Throwable[] failure = new Throwable[1];
			invoke(waitForLoadingToFinish, processor, (Runnable) () -> {
				try {
					applyAfterLoading(source, xaeroTile, processor, saveLoad);
				} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
					failure[0] = exception;
				}
			});
			if (failure[0] != null) {
				throwCause(failure[0]);
			}
		}

		private void applyAfterLoading(MapTile source, Object xaeroTile, Object processor, Object saveLoad)
				throws ReflectiveOperationException {
			int regionX = regionCoordinate(source.chunkX());
			int regionZ = regionCoordinate(source.chunkZ());
			Object region = invoke(getMapRegion, processor, regionX, regionZ, false);
			if (region == null) {
				region = invoke(getMapRegion, processor, regionX, regionZ, true);
				if (region == null) {
					throw new IllegalStateException("Xaero region detection is not initialized");
				}
				Object writerSync = writerThreadPauseSyncField.get(region);
				synchronized (writerSync) {
					synchronized (region) {
						invoke(setBeingWritten, region, true);
						invoke(requestLoad, saveLoad, region, "shared map sync");
					}
				}
				throw new IllegalStateException("Xaero region is not fully loaded: state=0");
			}

			Object writerSync = writerThreadPauseSyncField.get(region);
			synchronized (writerSync) {
				synchronized (region) {
					byte loadState = ((Number) invoke(getRegionLoadState, region)).byteValue();
					if (loadState != 2 && !(Boolean) invoke(reloadHasBeenRequested, region)) {
						invoke(setBeingWritten, region, true);
						invoke(requestLoad, saveLoad, region, "shared map sync retry");
					}
					applyLocked(source, xaeroTile, processor, saveLoad, region);
				}
			}
		}

		private Object createTile(MapTile source, Object processor, Object biomeKeyManager, Minecraft minecraft)
				throws ReflectiveOperationException {
			Object tilePool = invoke(getTilePool, processor);
			String xaeroDimension = (String) invoke(getCurrentDimension, processor);
			Object xaeroTile = invoke(tilePoolGet, tilePool, xaeroDimension, source.chunkX(), source.chunkZ());
			int[] baseHeights = source.baseHeights();
			int[] topHeights = source.topHeights();
			int[] stateIds = source.baseStateIds();
			int[] biomeIds = source.biomeIds();
			byte[] lights = source.lightAbove();
			boolean[] glowing = source.glowing();
			boolean[] cave = source.cave();
			Registry<Biome> biomeRegistry = minecraft.level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
			for (int localZ = 0; localZ < TILE_SIDE; localZ++) {
				for (int localX = 0; localX < TILE_SIDE; localX++) {
					int index = localZ * TILE_SIDE + localX;
					BlockState state = Block.stateById(stateIds[index]);
					Biome biome = biomeRegistry.byId(biomeIds[index]);
					ResourceLocation biomeId = biome == null ? null : biomeRegistry.getKey(biome);
					if (state == null || biomeId == null) {
						throw new IllegalArgumentException("Invalid surface registry ID at " + localX + "," + localZ);
					}
					int light = Byte.toUnsignedInt(lights[index]);
					if (light > 15) {
						throw new IllegalArgumentException("Invalid light level " + light + " at " + localX + "," + localZ);
					}
					Object biomeKey = invoke(biomeKeyGet, biomeKeyManager, biomeId.toString());
					if (biomeKey == null) {
						throw new IllegalStateException("Xaero did not create a biome key for " + biomeId);
					}
					Object block = construct(mapBlockConstructor);
					for (MapTile.Overlay sourceOverlay : source.overlaysAt(index)) {
						BlockState overlayState = Block.stateById(sourceOverlay.blockStateId());
						if (overlayState == null) {
							throw new IllegalArgumentException("Invalid overlay registry ID at " + localX + "," + localZ);
						}
						Object overlay = construct(overlayConstructor, overlayState, sourceOverlay.transparency(),
								sourceOverlay.lightAbove(), sourceOverlay.glowing());
						invoke(addOverlay, block, overlay);
					}
					invoke(writeBlock, block, state, baseHeights[index], topHeights[index], biomeKey, lights[index],
							glowing[index], cave[index]);
					invoke(setBlock, xaeroTile, localX, localZ, block);
				}
			}
			invoke(setWorldInterpretationVersion, xaeroTile, 1);
			invoke(setWrittenOnce, xaeroTile, true);
			invoke(setLoaded, xaeroTile, true);
			return xaeroTile;
		}

		@SuppressWarnings("unchecked")
		private void applyLocked(MapTile source, Object xaeroTile, Object processor, Object saveLoad, Object region)
				throws ReflectiveOperationException {
			byte originalRegionLoadState = ((Number) invoke(getRegionLoadState, region)).byteValue();
			boolean originalBeingWritten = (Boolean) invoke(isBeingWritten, region);
			boolean originalAllCachePrepared = (Boolean) invoke(isAllCachePrepared, region);
			boolean originalRefreshing = (Boolean) invoke(isRefreshing, region);
			boolean originalRegionTerrain = regionHasHadTerrainField.getBoolean(region);
			List<Object> toSave = (List<Object>) invoke(getToSave, saveLoad);
			if (toSave.contains(region)) {
				throw new IllegalStateException("Xaero region save is pending");
			}
			if ((Boolean) invoke(isWritingPaused, region)) {
				throw new IllegalStateException("Xaero region writing is paused");
			}
			if ((Boolean) invoke(toCacheContains, saveLoad, region)) {
				// This queued cache predates the incoming tile. Cancel it before preCache()
				// acquires the writer pause; refresh queues a replacement after rebuilding.
				invoke(removeToCache, saveLoad, region);
			}
			if (originalRegionLoadState != 2) {
				throw new IllegalStateException("Xaero region is not fully loaded: state=" + originalRegionLoadState);
			}

			int tileChunkX = tileChunkCoordinate(source.chunkX());
			int tileChunkZ = tileChunkCoordinate(source.chunkZ());
			int chunkInRegionX = Math.floorMod(tileChunkX, 8);
			int chunkInRegionZ = Math.floorMod(tileChunkZ, 8);
			Object chunk = invoke(getChunk, region, chunkInRegionX, chunkInRegionZ);
			boolean createdChunk = chunk == null;
			if (createdChunk) {
				chunk = construct(mapTileChunkConstructor, region, tileChunkX, tileChunkZ);
				invoke(setChunkLoadState, chunk, (byte) 2);
			}

			int tileInChunkX = Math.floorMod(source.chunkX(), 4);
			int tileInChunkZ = Math.floorMod(source.chunkZ(), 4);
			Object oldTile = invoke(getTile, chunk, tileInChunkX, tileInChunkZ);
			byte originalChunkLoadState = ((Number) invoke(getChunkLoadState, chunk)).byteValue();
			boolean originalChanged = (Boolean) invoke(wasChanged, chunk);
			boolean originalIncludeInSave = chunkIncludeInSaveField.getBoolean(chunk);
			boolean originalChunkTerrain = chunkHasHadTerrainField.getBoolean(chunk);
			Object shapeCache = invoke(getBlockStateShortShapeCache, processor);

			try {
				if (createdChunk) {
					invoke(setChunk, region, chunkInRegionX, chunkInRegionZ, chunk);
				}
				invoke(setTile, chunk, tileInChunkX, tileInChunkZ, xaeroTile, shapeCache);
				invoke(setChanged, chunk, true);
				invoke(setHasHadTerrain, chunk);
				// Xaero invalidates and rebuilds the region cache inside its refresh handler.
				// Invalidating it here races the cache writer and can crash MapSaveLoad.
				invoke(requestRefresh, region, processor);
				deferNativeSave(region, originalBeingWritten);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
				try {
					if (!originalRefreshing && (Boolean) invoke(isRefreshing, region)) {
						invoke(cancelRefresh, region, processor);
					}
					invoke(setTile, chunk, tileInChunkX, tileInChunkZ, oldTile, shapeCache);
					if (createdChunk) {
						invoke(setChunk, region, chunkInRegionX, chunkInRegionZ, null);
					} else {
						invoke(setChunkLoadState, chunk, originalChunkLoadState);
						invoke(setChanged, chunk, originalChanged);
						chunkIncludeInSaveField.setBoolean(chunk, originalIncludeInSave);
						chunkHasHadTerrainField.setBoolean(chunk, originalChunkTerrain);
					}
					regionHasHadTerrainField.setBoolean(region, originalRegionTerrain);
					invoke(setAllCachePrepared, region, originalAllCachePrepared);
					invoke(setBeingWritten, region, originalBeingWritten);
				} catch (ReflectiveOperationException | RuntimeException | LinkageError rollbackException) {
					exception.addSuppressed(rollbackException);
				}
				throw exception;
			}
		}

		private void deferNativeSave(Object region, boolean originalBeingWritten)
				throws ReflectiveOperationException {
			long originalLastSaveTime = lastSaveTimeField.getLong(region);
			long now = System.currentTimeMillis();
			try {
				// Xaero prepares the texture cache during later render passes. Its own 60-second
				// save delay must start after this mutation or MapSaveLoad can persist an
				// unprepared cache and crash the client.
				lastSaveTimeField.setLong(region, now);
				invoke(setBeingWritten, region, true);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
				lastSaveTimeField.setLong(region, originalLastSaveTime);
				invoke(setBeingWritten, region, originalBeingWritten);
				throw exception;
			}
		}

		private static Method method(Class<?> owner, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
			Method method = owner.getDeclaredMethod(name, parameterTypes);
			method.setAccessible(true);
			return method;
		}

		private static Method method(Class<?> owner, Class<?> returnType, String name, Class<?>... parameterTypes)
				throws NoSuchMethodException {
			Method method = method(owner, name, parameterTypes);
			if (method.getReturnType() != returnType) {
				throw new NoSuchMethodException(owner.getName() + "." + name + " returns " + method.getReturnType().getName());
			}
			return method;
		}

		private static Constructor<?> constructor(Class<?> owner, Class<?>... parameterTypes) throws NoSuchMethodException {
			Constructor<?> constructor = owner.getDeclaredConstructor(parameterTypes);
			constructor.setAccessible(true);
			return constructor;
		}

		private static Field field(Class<?> owner, String name, Class<?> expectedType) throws NoSuchFieldException {
			Field field = owner.getDeclaredField(name);
			if (field.getType() != expectedType) {
				throw new NoSuchFieldException(owner.getName() + "." + name + " has type " + field.getType().getName());
			}
			field.setAccessible(true);
			return field;
		}

		private static Object invoke(Method method, Object target, Object... arguments) throws ReflectiveOperationException {
			try {
				return method.invoke(target, arguments);
			} catch (InvocationTargetException exception) {
				throwCause(exception.getCause());
				throw new AssertionError("unreachable");
			}
		}

		private static Object construct(Constructor<?> constructor, Object... arguments) throws ReflectiveOperationException {
			try {
				return constructor.newInstance(arguments);
			} catch (InvocationTargetException exception) {
				throwCause(exception.getCause());
				throw new AssertionError("unreachable");
			}
		}

		private static void throwCause(Throwable cause) throws ReflectiveOperationException {
			if (cause instanceof ReflectiveOperationException) {
				throw (ReflectiveOperationException) cause;
			}
			if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			}
			if (cause instanceof LinkageError) {
				throw (LinkageError) cause;
			}
			throw new ReflectiveOperationException("Xaero invocation failed", cause);
		}
	}
}
