package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileHasher;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Reflection-only bridge for Xaero's World Map 1.25.1. */
public final class ReflectiveXaeroMapAdapter implements XaeroMapAdapter {
	static final String XAERO_MOD_ID = "xaeroworldmap";
	static final String SUPPORTED_VERSION = "1.25.1";
	private static final int TILE_SIDE = 16;
	private static final int TILE_AREA = TILE_SIDE * TILE_SIDE;
	private static final Pattern XAERO_REGION_FILE = Pattern.compile("(-?\\d+)_(-?\\d+)\\.zip");

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
				XaeroMapsync_r.LOGGER.info("Detected Xaero World Map version {}; supportedVersion={}",
						version, SUPPORTED_VERSION);
				if (!supportsVersion(version)) {
					XaeroMapsync_r.LOGGER.warn("Unsupported Xaero World Map version {}; expected {}. Map injection is disabled", version, SUPPORTED_VERSION);
				} else {
					resolvedRuntime = new Xaero1251Runtime();
					XaeroMapsync_r.LOGGER.info("Xaero World Map reflective runtime initialized for version {}", version);
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
		return applyBatchResult(List.of(tile)) == ApplyResult.APPLIED;
	}

	@Override
	public ApplyResult applyBatchResult(List<MapTile> tiles) {
		if (!available) {
			XaeroMapsync_r.LOGGER.debug("Skipped Xaero map apply because adapter is unavailable tiles={}",
					tiles == null ? 0 : tiles.size());
			return ApplyResult.UNAVAILABLE;
		}
		try {
			validateBatch(tiles);
			MapTile first = tiles.get(0);
			XaeroMapsync_r.LOGGER.debug(
					"Applying Xaero map tile batch size={} dimension={} region={} {} firstChunk={} {} firstHash={}",
					tiles.size(), first.dimension(), regionCoordinate(first.chunkX()),
					regionCoordinate(first.chunkZ()), first.chunkX(), first.chunkZ(),
					Long.toUnsignedString(first.contentHash()));
			runtime.applyBatch(tiles);
			XaeroMapsync_r.LOGGER.debug(
					"Applied Xaero map tile batch size={} dimension={} region={} {}",
					tiles.size(), first.dimension(), regionCoordinate(first.chunkX()),
					regionCoordinate(first.chunkZ()));
			return ApplyResult.APPLIED;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
			if (isNotReady(exception)) {
				long now = System.currentTimeMillis();
				if (now >= nextNotReadyLogMillis) {
					nextNotReadyLogMillis = now + 5_000L;
					XaeroMapsync_r.LOGGER.info("Xaero map injection deferred: {}", exception.getMessage());
				}
				return ApplyResult.RETRY_LATER;
			}
			available = false;
			XaeroMapsync_r.LOGGER.error("Xaero map tile injection failed; adapter disabled for this session", exception);
			return ApplyResult.UNAVAILABLE;
		}
	}

	@Override
	public LocalTileState localTileState(MapTile tile) {
		return localTileState(tile.dimension(), tile.chunkX(), tile.chunkZ());
	}

	@Override
	public LocalTileState localTileState(String dimension, int chunkX, int chunkZ) {
		if (!available) return LocalTileState.REMOTE;
		try {
			LocalTileState state = runtime.localTileState(dimension, chunkX, chunkZ);
			if (state != LocalTileState.REMOTE) {
				XaeroMapsync_r.LOGGER.debug("Xaero local tile state dimension={} chunk={} {} state={}",
						dimension, chunkX, chunkZ, state);
			}
			return state;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
			if (isNotReady(exception)) {
				XaeroMapsync_r.LOGGER.debug("Xaero local tile state deferred dimension={} chunk={} {} reason={}",
						dimension, chunkX, chunkZ, exception.getMessage());
				return LocalTileState.GENERATING;
			}
			available = false;
			XaeroMapsync_r.LOGGER.error("Xaero local map ownership detection failed; adapter disabled for this session", exception);
			return LocalTileState.REMOTE;
		}
	}

	@Override
	public Optional<MapTile> localTile(String dimension, int chunkX, int chunkZ) {
		if (!available) return Optional.empty();
		try {
			Optional<MapTile> local = runtime.localTile(dimension, chunkX, chunkZ)
					.filter(ReflectiveXaeroMapAdapter::isUsableLocalSnapshot);
			local.ifPresent(tile -> XaeroMapsync_r.LOGGER.debug(
					"Extracted Xaero local tile snapshot dimension={} chunk={} {} hash={}",
					tile.dimension(), tile.chunkX(), tile.chunkZ(), Long.toUnsignedString(tile.contentHash())));
			return local;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
			if (isNotReady(exception)) {
				XaeroMapsync_r.LOGGER.debug("Xaero local tile extraction deferred dimension={} chunk={} {} reason={}",
						dimension, chunkX, chunkZ, exception.getMessage());
				return Optional.empty();
			}
			available = false;
			XaeroMapsync_r.LOGGER.error("Xaero local map tile extraction failed; adapter disabled for this session", exception);
			return Optional.empty();
		}
	}

	@Override
	public List<LocalRegion> knownLocalRegions(String dimension) {
		if (!available) return List.of();
		try {
			return runtime.knownLocalRegions(dimension);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
			if (!isNotReady(exception)) XaeroMapsync_r.LOGGER.warn(
					"map_sync event=client_archive_discovery_deferred dimension={} reason=runtime_not_ready",
					dimension, exception);
			return List.of();
		}
	}

	@Override
	public boolean prepareLocalRegion(String dimension, LocalRegion region) {
		if (!available) return false;
		try {
			return runtime.prepareLocalRegion(dimension, region);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
			if (!isNotReady(exception)) XaeroMapsync_r.LOGGER.warn(
					"map_sync event=client_archive_region_deferred dimension={} region_x={} region_z={} reason=runtime_not_ready",
					dimension, region.regionX(), region.regionZ(), exception);
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
						|| exception.getMessage().contains("refresh is pending")
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

	static Optional<XaeroMapAdapter.LocalRegion> regionFromFileName(String fileName) {
		if (fileName == null) return Optional.empty();
		Matcher matcher = XAERO_REGION_FILE.matcher(fileName);
		if (!matcher.matches()) return Optional.empty();
		try {
			return Optional.of(new XaeroMapAdapter.LocalRegion(Integer.parseInt(matcher.group(1)),
					Integer.parseInt(matcher.group(2))));
		} catch (NumberFormatException exception) {
			return Optional.empty();
		}
	}

	static List<Path> xaeroRegionFolderCandidates(Path mainFolder, String multiworld) {
		if (mainFolder == null || multiworld == null || multiworld.isBlank()) return List.of();
		Path nativeFolder = mainFolder.resolve(multiworld);
		if (multiworld.startsWith("mw$")) return List.of(nativeFolder);
		return List.of(nativeFolder, mainFolder.resolve("mw$" + multiworld));
	}

	static Optional<Path> uniqueXaeroMultiworldFolder(Path mainFolder) {
		if (mainFolder == null || !Files.isDirectory(mainFolder)) return Optional.empty();
		try (java.util.stream.Stream<Path> children = Files.list(mainFolder)) {
			List<Path> matches = children.filter(Files::isDirectory)
					.filter(path -> path.getFileName().toString().startsWith("mw$"))
					.limit(2).toList();
			return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
		} catch (java.io.IOException exception) {
			return Optional.empty();
		}
	}

	static boolean shouldRequestRegionLoad(byte loadState, boolean reloadRequested, boolean queuedForLoad,
			boolean recacheRequested) {
		return (loadState == 0 || loadState == 4) && !recacheRequested
				&& (!reloadRequested || !queuedForLoad);
	}

	static boolean isCurrentDimension(String tileDimension, String currentDimension) {
		return Objects.equals(tileDimension, currentDimension);
	}

	static boolean isUsableLocalSnapshot(MapTile tile) {
		return tile.hasRenderableSurface();
	}

	static LocalTileState localOwnership(boolean chunkLoaded, boolean xaeroReady) {
		if (!chunkLoaded) return LocalTileState.REMOTE;
		return xaeroReady ? LocalTileState.READY : LocalTileState.GENERATING;
	}

	private static void validateTile(MapTile tile) {
		Objects.requireNonNull(tile, "tile");
		if (tile.dimension() == null || tile.dimension().isBlank()) {
			throw new IllegalArgumentException("Map tile dimension is missing");
		}
		if (tile.baseStateIds().length != TILE_AREA || tile.baseHeights().length != TILE_AREA
				|| tile.topHeights().length != TILE_AREA || tile.biomeKeys().length != TILE_AREA
				|| tile.lightAbove().length != TILE_AREA || tile.glowing().length != TILE_AREA
				|| tile.cave().length != TILE_AREA || tile.overlays().size() != TILE_AREA) {
			throw new IllegalArgumentException("Xaero map injection requires 16x16 surface arrays");
		}
	}

	private static void validateBatch(List<MapTile> tiles) {
		Objects.requireNonNull(tiles, "tiles");
		if (tiles.isEmpty()) throw new IllegalArgumentException("Xaero map injection batch is empty");
		MapTile first = tiles.get(0);
		validateTile(first);
		int regionX = regionCoordinate(first.chunkX());
		int regionZ = regionCoordinate(first.chunkZ());
		for (int index = 1; index < tiles.size(); index++) {
			MapTile tile = tiles.get(index);
			validateTile(tile);
			if (!first.dimension().equals(tile.dimension()) || regionCoordinate(tile.chunkX()) != regionX
					|| regionCoordinate(tile.chunkZ()) != regionZ) {
				throw new IllegalArgumentException("Xaero map injection batch crosses a region boundary");
			}
		}
	}

	@FunctionalInterface
	interface XaeroRuntime {
		void apply(MapTile tile) throws ReflectiveOperationException;

		default void applyBatch(List<MapTile> tiles) throws ReflectiveOperationException {
			for (MapTile tile : tiles) apply(tile);
		}

		default LocalTileState localTileState(MapTile tile) throws ReflectiveOperationException {
			return localTileState(tile.dimension(), tile.chunkX(), tile.chunkZ());
		}

		default LocalTileState localTileState(String dimension, int chunkX, int chunkZ)
				throws ReflectiveOperationException {
			return LocalTileState.REMOTE;
		}

		default Optional<MapTile> localTile(String dimension, int chunkX, int chunkZ)
				throws ReflectiveOperationException {
			return Optional.empty();
		}

		default List<LocalRegion> knownLocalRegions(String dimension) throws ReflectiveOperationException {
			return List.of();
		}

		default boolean prepareLocalRegion(String dimension, LocalRegion region) throws ReflectiveOperationException {
			return false;
		}
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
		private final Method getMapTile;
		private final Method getCurrentDimension;
		private final Method getMapWorld;
		private final Method getCurrentMapDimension;
		private final Method getCurrentMapDimensionId;
		private final Method getMapDimension;
		private final Method getMapWorldCurrentMultiworld;
		private final Method getMapWorldMainId;
		private final Method getDimensionName;
		private final Method getMainFolder;
		private final Method getCurrentWorldId;
		private final Method getDetectedRegions;
		private final Method getMainFolderPath;
		private final Method getCurrentMultiworld;
		private final Method getDetectedRegionX;
		private final Method getDetectedRegionZ;
		private final Method chunkUpdateCallback;
		private final Method waitForLoadingToFinish;
		private final Method getChunk;
		private final Method setChunk;
		private final Method getRegionLoadState;
		private final Method isBeingWritten;
		private final Method setBeingWritten;
		private final Method isWritingPaused;
		private final Field lastSaveTimeField;
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
		private final Method isTileLoaded;
		private final Method wasTileWrittenOnce;
		private final Method getMapBlock;
		private final Method getPixelState;
		private final Field pixelLightField;
		private final Field pixelGlowingField;
		private final Method getBlockHeight;
		private final Method getBlockTopHeight;
		private final Method getBlockBiome;
		private final Method getBlockOverlays;
		private final Method isCaveBlock;
		private final Method getBiomeIdentifier;
		private final Method getOverlayTransparency;
		private final Method getOverlayOpacity;
		private final Constructor<?> mapBlockConstructor;
		private final Method writeBlock;
		private final Method setSlopeUnknown;
		private final Constructor<?> overlayConstructor;
		private final Method addOverlay;
		private final Method increaseOverlayOpacity;
		private final Field biomeKeyManagerField;
		private final Method biomeKeyGet;
		private final Method requestLoad;
		private final Method shouldCache;
		private final Method reloadHasBeenRequested;
		private final Method recacheHasBeenRequested;
		private final Method setRecacheHasBeenRequested;
		private final Method setShouldCache;
		private final Field toLoadField;
		private final Method toCacheContains;
		private final Method removeToCache;
		private final Method requestCache;
		private final Method getToSave;
		private final Field writerThreadPauseSyncField;
		private final Field chunkIncludeInSaveField;
		private final Field chunkHasHadTerrainField;
		private final Field regionHasHadTerrainField;
		private final Map<Long, Long> localRegenerationRequests = new LinkedHashMap<>();

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
			Class<?> mapPixelClass = Class.forName("xaero.map.region.MapPixel", false, loader);
			biomeKeyClass = Class.forName("xaero.map.biome.BiomeKey", false, loader);
			Class<?> biomeKeyManagerClass = Class.forName("xaero.map.biome.BiomeKeyManager", false, loader);
			Class<?> mapTilePoolClass = Class.forName("xaero.map.pool.MapTilePool", false, loader);
			Class<?> blockStateShortShapeCacheClass = Class.forName("xaero.map.cache.BlockStateShortShapeCache", false, loader);
			Class<?> mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad", false, loader);
			Class<?> mapWorldClass = Class.forName("xaero.map.world.MapWorld", false, loader);
			Class<?> mapDimensionClass = Class.forName("xaero.map.world.MapDimension", false, loader);
			Class<?> resourceKeyClass = Class.forName("net.minecraft.resources.ResourceKey", false, loader);
			Class<?> regionDetectionClass = Class.forName("xaero.map.file.RegionDetection", false, loader);
			Class<?> xaeroCoreClass = Class.forName("xaero.map.core.XaeroWorldMapCore", false, loader);

			currentSession = method(sessionClass, sessionClass, "getCurrentSession");
			getMapProcessor = method(sessionClass, mapProcessorClass, "getMapProcessor");
			getMapRegion = method(mapProcessorClass, mapRegionClass, "getMapRegion", int.class, int.class, boolean.class);
			getTilePool = method(mapProcessorClass, mapTilePoolClass, "getTilePool");
			getBlockStateShortShapeCache = method(mapProcessorClass, blockStateShortShapeCacheClass, "getBlockStateShortShapeCache");
			getMapSaveLoad = method(mapProcessorClass, mapSaveLoadClass, "getMapSaveLoad");
			getMapTile = method(mapProcessorClass, xaeroMapTileClass, "getMapTile", int.class, int.class);
			getCurrentDimension = method(mapProcessorClass, String.class, "getCurrentDimension");
			getMapWorld = method(mapProcessorClass, mapWorldClass, "getMapWorld");
			getCurrentMapDimension = method(mapWorldClass, mapDimensionClass, "getCurrentDimension");
			getCurrentMapDimensionId = method(mapWorldClass, resourceKeyClass, "getCurrentDimensionId");
			getMapDimension = method(mapWorldClass, mapDimensionClass, "getDimension", resourceKeyClass);
			getMapWorldCurrentMultiworld = method(mapWorldClass, String.class, "getCurrentMultiworld");
			getMapWorldMainId = method(mapWorldClass, String.class, "getMainId");
			getDimensionName = method(mapProcessorClass, String.class, "getDimensionName", resourceKeyClass);
			getMainFolder = method(mapSaveLoadClass, Path.class, "getMainFolder", String.class, String.class);
			getCurrentWorldId = method(mapProcessorClass, String.class, "getCurrentWorldId");
			getDetectedRegions = method(mapDimensionClass, java.util.Hashtable.class, "getDetectedRegions");
			getMainFolderPath = method(mapDimensionClass, Path.class, "getMainFolderPath");
			getCurrentMultiworld = method(mapDimensionClass, String.class, "getCurrentMultiworld");
			getDetectedRegionX = method(regionDetectionClass, int.class, "getRegionX");
			getDetectedRegionZ = method(regionDetectionClass, int.class, "getRegionZ");
			chunkUpdateCallback = method(xaeroCoreClass, "chunkUpdateCallback", int.class, int.class);
			waitForLoadingToFinish = method(mapProcessorClass, "waitForLoadingToFinish", Runnable.class);
			getChunk = method(mapRegionClass, mapTileChunkClass, "getChunk", int.class, int.class);
			setChunk = method(mapRegionClass, "setChunk", int.class, int.class, mapTileChunkClass);
			getRegionLoadState = method(mapRegionClass, byte.class, "getLoadState");
			isBeingWritten = method(mapRegionClass, "isBeingWritten");
			setBeingWritten = method(mapRegionClass, "setBeingWritten", boolean.class);
			isWritingPaused = method(mapRegionClass, "isWritingPaused");
			lastSaveTimeField = field(leveledRegionClass, "lastSaveTime", long.class);
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
			isTileLoaded = method(xaeroMapTileClass, "isLoaded");
			wasTileWrittenOnce = method(xaeroMapTileClass, "wasWrittenOnce");
			getMapBlock = method(xaeroMapTileClass, mapBlockClass, "getBlock", int.class, int.class);
			getPixelState = method(mapPixelClass, BlockState.class, "getState");
			pixelLightField = field(mapPixelClass, "light", byte.class);
			pixelGlowingField = field(mapPixelClass, "glowing", boolean.class);
			getBlockHeight = method(mapBlockClass, int.class, "getHeight");
			getBlockTopHeight = method(mapBlockClass, int.class, "getTopHeight");
			getBlockBiome = method(mapBlockClass, biomeKeyClass, "getBiome");
			getBlockOverlays = method(mapBlockClass, java.util.ArrayList.class, "getOverlays");
			isCaveBlock = method(mapBlockClass, boolean.class, "isCaveBlock");
			getBiomeIdentifier = method(biomeKeyClass, ResourceLocation.class, "getIdentifier", Registry.class);
			getOverlayTransparency = method(overlayClass, float.class, "getTransparency");
			getOverlayOpacity = method(overlayClass, int.class, "getOpacity");
			mapBlockConstructor = constructor(mapBlockClass);
			writeBlock = method(mapBlockClass, "write", BlockState.class, int.class, int.class, biomeKeyClass, byte.class, boolean.class, boolean.class);
			setSlopeUnknown = method(mapBlockClass, "setSlopeUnknown", boolean.class);
			overlayConstructor = constructor(overlayClass, BlockState.class, float.class, byte.class, boolean.class);
			addOverlay = method(mapBlockClass, "addOverlay", overlayClass);
			increaseOverlayOpacity = method(overlayClass, "increaseOpacity", int.class);
			biomeKeyManagerField = field(mapSaveLoadClass, "biomeKeyManager", biomeKeyManagerClass);
			biomeKeyGet = method(biomeKeyManagerClass, biomeKeyClass, "get", String.class);
			requestLoad = method(mapSaveLoadClass, "requestLoad", mapRegionClass, String.class);
			shouldCache = method(leveledRegionClass, "shouldCache");
			reloadHasBeenRequested = method(leveledRegionClass, "reloadHasBeenRequested");
			recacheHasBeenRequested = method(leveledRegionClass, "recacheHasBeenRequested");
			setRecacheHasBeenRequested = method(leveledRegionClass, "setRecacheHasBeenRequested", boolean.class, String.class);
			setShouldCache = method(leveledRegionClass, "setShouldCache", boolean.class, String.class);
			toLoadField = field(mapSaveLoadClass, "toLoad", java.util.ArrayList.class);
			toCacheContains = method(mapSaveLoadClass, "toCacheContains", leveledRegionClass);
			removeToCache = method(mapSaveLoadClass, "removeToCache", leveledRegionClass);
			requestCache = method(mapSaveLoadClass, "requestCache", leveledRegionClass);
			getToSave = method(mapSaveLoadClass, "getToSave");
			writerThreadPauseSyncField = field(mapRegionClass, "writerThreadPauseSync", Object.class);
			chunkIncludeInSaveField = field(mapTileChunkClass, "includeInSave", boolean.class);
			chunkHasHadTerrainField = field(mapTileChunkClass, "hasHadTerrain", boolean.class);
			regionHasHadTerrainField = field(mapRegionClass, "hasHadTerrain", boolean.class);
			XaeroMapsync_r.LOGGER.debug(
					"Validated Xaero 1.25.1 reflective signatures: session={} processor={} region={} tileChunk={} tile={} block={} biomeKey={}",
					sessionClass.getName(), mapProcessorClass.getName(), mapRegionClass.getName(),
					mapTileChunkClass.getName(), xaeroMapTileClass.getName(), mapBlockClass.getName(),
					biomeKeyClass.getName());
		}

		@Override
		public LocalTileState localTileState(String dimension, int chunkX, int chunkZ) throws ReflectiveOperationException {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.level == null || !isCurrentDimension(dimension,
					minecraft.level.dimension().location().toString())) {
				return LocalTileState.REMOTE;
			}
			LevelChunk chunk = minecraft.level.getChunkSource().getChunkNow(chunkX, chunkZ);
			if (chunk == null) return LocalTileState.REMOTE;
			Object session = invoke(currentSession, null);
			if (session == null) throw new IllegalStateException("Xaero WorldMapSession is not initialized");
			Object processor = invoke(getMapProcessor, session);
			if (processor == null) throw new IllegalStateException("Xaero MapProcessor is not initialized");
			Object xaeroTile = invoke(getMapTile, processor, chunkX, chunkZ);
			boolean xaeroReady = xaeroTile != null && (Boolean) invoke(isTileLoaded, xaeroTile)
					&& (Boolean) invoke(wasTileWrittenOnce, xaeroTile)
					&& hasRenderableTileData(xaeroTile);
			if (xaeroReady) {
				XaeroMapsync_r.LOGGER.debug("Xaero reports local tile ready dimension={} chunk={} {}",
						dimension, chunkX, chunkZ);
			}
			if (!xaeroReady) requestLocalRegeneration(chunkX, chunkZ);
			LocalTileState state = localOwnership(true, xaeroReady);
			if (state == LocalTileState.GENERATING) {
				XaeroMapsync_r.LOGGER.debug("Xaero local chunk is dirty/generating dimension={} chunk={} {}",
						dimension, chunkX, chunkZ);
			}
			return state;
		}

		private void requestLocalRegeneration(int chunkX, int chunkZ) throws ReflectiveOperationException {
			long key = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);
			long now = System.currentTimeMillis();
			long previous = localRegenerationRequests.getOrDefault(key, 0L);
			if (now - previous < 1_000L) return;
			localRegenerationRequests.put(key, now);
			if (localRegenerationRequests.size() > 1_024)
				localRegenerationRequests.remove(localRegenerationRequests.keySet().iterator().next());
			invoke(chunkUpdateCallback, null, chunkX, chunkZ);
			XaeroMapsync_r.LOGGER.debug(
					"map_sync event=local_xaero_regeneration_requested chunk_x={} chunk_z={} reason=loaded_placeholder",
					chunkX, chunkZ);
		}

		@Override
		public Optional<MapTile> localTile(String dimension, int chunkX, int chunkZ) throws ReflectiveOperationException {
			Minecraft minecraft = Minecraft.getInstance();
			if (!minecraft.isSameThread() || !isSnapshotDimensionCurrent(dimension, null, minecraft)) {
				XaeroMapsync_r.LOGGER.debug("Skipped Xaero local snapshot dimension={} chunk={} {} sameThread={} currentDimension={}",
						dimension, chunkX, chunkZ, minecraft.isSameThread(),
						minecraft.level == null ? null : minecraft.level.dimension().location().toString());
				return Optional.empty();
			}
			Object session = invoke(currentSession, null);
			if (session == null) throw new IllegalStateException("Xaero WorldMapSession is not initialized");
			Object processor = invoke(getMapProcessor, session);
			if (processor == null) throw new IllegalStateException("Xaero MapProcessor is not initialized");
			if (!isSnapshotDimensionCurrent(dimension, processor, minecraft)) return Optional.empty();

			MapTile[] snapshot = new MapTile[1];
			Throwable[] failure = new Throwable[1];
			invoke(waitForLoadingToFinish, processor, (Runnable) () -> {
				try {
					snapshot[0] = localTileAfterLoading(dimension, chunkX, chunkZ, processor, minecraft);
				} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
					failure[0] = exception;
				}
			});
			if (failure[0] != null) throwCause(failure[0]);
			XaeroMapsync_r.LOGGER.debug("Xaero local snapshot result dimension={} chunk={} {} present={}",
					dimension, chunkX, chunkZ, snapshot[0] != null);
			return Optional.ofNullable(snapshot[0]);
		}

		@Override
		public List<LocalRegion> knownLocalRegions(String dimension) throws ReflectiveOperationException {
			Minecraft minecraft = Minecraft.getInstance();
			if (!minecraft.isSameThread() || !isSnapshotDimensionCurrent(dimension, null, minecraft)) return List.of();
			Object session = invoke(currentSession, null);
			if (session == null) throw new IllegalStateException("Xaero WorldMapSession is not initialized");
			Object processor = invoke(getMapProcessor, session);
			if (processor == null || !isSnapshotDimensionCurrent(dimension, processor, minecraft)) return List.of();
			Object mapWorld = invoke(getMapWorld, processor);
			Object currentDimensionId = mapWorld == null ? null : invoke(getCurrentMapDimensionId, mapWorld);
			// Xaero 1.25.1 getCurrentDimension() dereferences a null dimension id
			// during the short join transition. Resolve through the id only when ready.
			Object mapDimension = currentDimensionId == null ? null
					: invoke(getMapDimension, mapWorld, currentDimensionId);
			Object detected = mapDimension == null ? null : invoke(getDetectedRegions, mapDimension);
			java.util.Set<LocalRegion> regions = new java.util.HashSet<>();
			if (detected instanceof Map<?, ?> outer) {
				for (Object row : outer.values()) {
					if (!(row instanceof Map<?, ?> inner)) continue;
					for (Object detection : inner.values()) {
						if (detection == null) continue;
						regions.add(new LocalRegion(((Number) invoke(getDetectedRegionX, detection)).intValue(),
								((Number) invoke(getDetectedRegionZ, detection)).intValue()));
					}
				}
			}
			if (regions.isEmpty()) {
				Path mainFolder;
				String multiworld;
				if (mapDimension != null) {
					mainFolder = (Path) invoke(getMainFolderPath, mapDimension);
					multiworld = (String) invoke(getCurrentMultiworld, mapDimension);
				} else {
					Object saveLoad = invoke(getMapSaveLoad, processor);
					String mainId = mapWorld == null ? (String) invoke(getCurrentWorldId, processor)
							: (String) invoke(getMapWorldMainId, mapWorld);
					String dimensionFolder = (String) invoke(getDimensionName, processor, minecraft.level.dimension());
					mainFolder = (Path) invoke(getMainFolder, saveLoad, mainId, dimensionFolder);
					multiworld = mapWorld == null ? null : (String) invoke(getMapWorldCurrentMultiworld, mapWorld);
				}
				if ((multiworld == null || multiworld.isBlank()) && mapWorld != null)
					multiworld = (String) invoke(getMapWorldCurrentMultiworld, mapWorld);
				Path regionFolder = xaeroRegionFolderCandidates(mainFolder, multiworld).stream()
						.filter(Files::isDirectory).findFirst().orElse(null);
				if (regionFolder == null && (multiworld == null || multiworld.isBlank()))
					regionFolder = uniqueXaeroMultiworldFolder(mainFolder).orElse(null);
				if (regionFolder != null) {
					try (java.util.stream.Stream<Path> files = Files.list(regionFolder)) {
						files.filter(Files::isRegularFile)
								.map(path -> regionFromFileName(path.getFileName().toString()))
								.flatMap(Optional::stream).forEach(regions::add);
					} catch (java.io.IOException exception) {
						XaeroMapsync_r.LOGGER.warn(
								"map_sync event=client_archive_discovery_failed dimension={} folder={}",
								dimension, regionFolder, exception);
					}
					if (!regions.isEmpty()) XaeroMapsync_r.LOGGER.info(
							"map_sync event=client_archive_discovered dimension={} regions={} source=current_xaero_folder folder={}",
							dimension, regions.size(), regionFolder);
				} else {
					XaeroMapsync_r.LOGGER.debug(
							"map_sync event=client_archive_folder_missing dimension={} main_folder={} multiworld={} candidates={}",
							dimension, mainFolder, multiworld, xaeroRegionFolderCandidates(mainFolder, multiworld));
				}
			}
			return regions.stream().sorted(java.util.Comparator.comparingInt(LocalRegion::regionX)
					.thenComparingInt(LocalRegion::regionZ)).toList();
		}

		@Override
		public boolean prepareLocalRegion(String dimension, LocalRegion localRegion) throws ReflectiveOperationException {
			Minecraft minecraft = Minecraft.getInstance();
			if (!minecraft.isSameThread() || !isSnapshotDimensionCurrent(dimension, null, minecraft)) return false;
			Object session = invoke(currentSession, null);
			if (session == null) throw new IllegalStateException("Xaero WorldMapSession is not initialized");
			Object processor = invoke(getMapProcessor, session);
			if (processor == null || !isSnapshotDimensionCurrent(dimension, processor, minecraft)) return false;
			Object saveLoad = invoke(getMapSaveLoad, processor);
			Object region = invoke(getMapRegion, processor, localRegion.regionX(), localRegion.regionZ(), false);
			if (region == null) region = invoke(getMapRegion, processor, localRegion.regionX(), localRegion.regionZ(), true);
			if (region == null) return false;
			Object writerSync = writerThreadPauseSyncField.get(region);
			synchronized (writerSync) {
				synchronized (region) {
					byte loadState = ((Number) invoke(getRegionLoadState, region)).byteValue();
					if (loadState == 2) return true;
					boolean reloadRequested = (Boolean) invoke(reloadHasBeenRequested, region);
					boolean recacheRequested = (Boolean) invoke(recacheHasBeenRequested, region);
					Object toLoad = toLoadField.get(saveLoad);
					boolean queuedForLoad;
					synchronized (toLoad) {
						queuedForLoad = ((List<?>) toLoad).contains(region);
					}
					if (shouldRequestRegionLoad(loadState, reloadRequested, queuedForLoad, recacheRequested)) {
						invoke(setBeingWritten, region, true);
						invoke(requestLoad, saveLoad, region, "shared map archive scan");
						XaeroMapsync_r.LOGGER.debug("Requested Xaero archive region load dimension={} region={} {} state={}",
								dimension, localRegion.regionX(), localRegion.regionZ(), loadState);
					}
					return false;
				}
			}
		}

		private MapTile localTileAfterLoading(String dimension, int chunkX, int chunkZ, Object processor,
				Minecraft minecraft) throws ReflectiveOperationException {
			if (!isSnapshotDimensionCurrent(dimension, processor, minecraft)) return null;
			int regionX = regionCoordinate(chunkX);
			int regionZ = regionCoordinate(chunkZ);
			Object region = invoke(getMapRegion, processor, regionX, regionZ, false);
			if (region == null) {
				XaeroMapsync_r.LOGGER.debug("Xaero local snapshot missing region dimension={} region={} {} chunk={} {}",
						dimension, regionX, regionZ, chunkX, chunkZ);
				return null;
			}

			Object writerSync = writerThreadPauseSyncField.get(region);
			synchronized (writerSync) {
				synchronized (region) {
					if (!isSnapshotDimensionCurrent(dimension, processor, minecraft)
							|| invoke(getMapRegion, processor, regionX, regionZ, false) != region
							|| ((Number) invoke(getRegionLoadState, region)).byteValue() != 2) {
						XaeroMapsync_r.LOGGER.debug("Xaero local snapshot region not ready dimension={} region={} {} chunk={} {}",
								dimension, regionX, regionZ, chunkX, chunkZ);
						return null;
					}
					int tileChunkX = tileChunkCoordinate(chunkX);
					int tileChunkZ = tileChunkCoordinate(chunkZ);
					Object chunk = invoke(getChunk, region, Math.floorMod(tileChunkX, 8), Math.floorMod(tileChunkZ, 8));
					if (chunk == null || ((Number) invoke(getChunkLoadState, chunk)).intValue() != 2) {
						XaeroMapsync_r.LOGGER.debug("Xaero local snapshot chunk not ready dimension={} chunk={} {} tileChunk={} {}",
								dimension, chunkX, chunkZ, tileChunkX, tileChunkZ);
						return null;
					}
					Object xaeroTile = invoke(getTile, chunk, Math.floorMod(chunkX, 4), Math.floorMod(chunkZ, 4));
					if (xaeroTile == null || !(Boolean) invoke(isTileLoaded, xaeroTile)
							|| !(Boolean) invoke(wasTileWrittenOnce, xaeroTile)) {
						XaeroMapsync_r.LOGGER.debug("Xaero local snapshot tile not loaded/written dimension={} chunk={} {}",
								dimension, chunkX, chunkZ);
						return null;
					}
					if (!hasRenderableTileData(xaeroTile)) {
						XaeroMapsync_r.LOGGER.debug("Xaero local snapshot tile has no renderable data dimension={} chunk={} {}",
								dimension, chunkX, chunkZ);
						return null;
					}

					return snapshotTile(dimension, chunkX, chunkZ, xaeroTile, minecraft);
				}
			}
		}

		private boolean isSnapshotDimensionCurrent(String dimension, Object processor, Minecraft minecraft)
				throws ReflectiveOperationException {
			if (minecraft.level == null || !isCurrentDimension(dimension,
					minecraft.level.dimension().location().toString())) {
				return false;
			}
			if (processor == null) return true;
			Object mapWorld = invoke(getMapWorld, processor);
			if (mapWorld == null) return true;
			Object xaeroDimensionId = invoke(getCurrentMapDimensionId, mapWorld);
			// Xaero 1.25.1's MapProcessor#getCurrentDimension() is a literal
			// "placeholder" stub. ResourceKey is the actual session identity.
			return xaeroDimensionId == null || xaeroDimensionId.equals(minecraft.level.dimension());
		}

		private boolean hasRenderableTileData(Object xaeroTile) throws ReflectiveOperationException {
			boolean hasSurfaceBlock = false;
			boolean hasOverlayBlock = false;
			for (int localZ = 0; localZ < TILE_SIDE; localZ++) {
				for (int localX = 0; localX < TILE_SIDE; localX++) {
					Object block = invoke(getMapBlock, xaeroTile, localX, localZ);
					Object state = block == null ? null : invoke(getPixelState, block);
					if (state == null || invoke(getBlockBiome, block) == null) {
						return false;
					}
					hasSurfaceBlock |= !((BlockState) state).isAir();
					List<?> overlays = (List<?>) invoke(getBlockOverlays, block);
					hasOverlayBlock |= overlays != null && !overlays.isEmpty();
				}
			}
			return hasSurfaceBlock || hasOverlayBlock;
		}

		private MapTile snapshotTile(String dimension, int chunkX, int chunkZ, Object xaeroTile,
				Minecraft minecraft) throws ReflectiveOperationException {

			int[] baseStateIds = new int[TILE_AREA];
			int[] baseHeights = new int[TILE_AREA];
			int[] topHeights = new int[TILE_AREA];
			String[] biomeKeys = new String[TILE_AREA];
			byte[] lightAbove = new byte[TILE_AREA];
			boolean[] glowing = new boolean[TILE_AREA];
			boolean[] cave = new boolean[TILE_AREA];
			List<List<MapTile.Overlay>> overlays = new ArrayList<>(TILE_AREA);
			Registry<Biome> biomeRegistry = minecraft.level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
			for (int localZ = 0; localZ < TILE_SIDE; localZ++) {
				for (int localX = 0; localX < TILE_SIDE; localX++) {
					int index = localZ * TILE_SIDE + localX;
					Object block = invoke(getMapBlock, xaeroTile, localX, localZ);
					if (block == null) throw new IllegalStateException("Xaero map block is not loaded yet");
					BlockState state = (BlockState) invoke(getPixelState, block);
					Object biome = invoke(getBlockBiome, block);
					ResourceLocation biomeId = biome == null ? null
							: (ResourceLocation) invoke(getBiomeIdentifier, biome, biomeRegistry);
					if (state == null || biome == null) {
						throw new IllegalStateException("Xaero map block registry data is not loaded yet");
					}
					baseStateIds[index] = Block.getId(state);
					baseHeights[index] = ((Number) invoke(getBlockHeight, block)).intValue();
					topHeights[index] = ((Number) invoke(getBlockTopHeight, block)).intValue();
					biomeKeys[index] = biomeId.toString();
					lightAbove[index] = pixelLightField.getByte(block);
					glowing[index] = pixelGlowingField.getBoolean(block);
					cave[index] = (Boolean) invoke(isCaveBlock, block);
					List<?> xaeroOverlays = (List<?>) invoke(getBlockOverlays, block);
					List<MapTile.Overlay> column = new ArrayList<>(xaeroOverlays == null ? 0 : xaeroOverlays.size());
					if (xaeroOverlays != null) {
						for (Object overlay : xaeroOverlays) {
							BlockState overlayState = (BlockState) invoke(getPixelState, overlay);
							if (overlayState == null) throw new IllegalStateException("Xaero overlay is not loaded yet");
							column.add(new MapTile.Overlay(Block.getId(overlayState),
									((Number) invoke(getOverlayTransparency, overlay)).floatValue(),
									pixelLightField.getByte(overlay), pixelGlowingField.getBoolean(overlay),
									((Number) invoke(getOverlayOpacity, overlay)).intValue()));
						}
					}
					overlays.add(List.copyOf(column));
				}
			}
			MapTile unhashed = new MapTile(dimension, chunkX, chunkZ, baseStateIds, baseHeights, topHeights,
					biomeKeys, lightAbove, glowing, cave, overlays, 0L);
			long contentHash = MapTileHasher.hashSurface(unhashed);
			XaeroMapsync_r.LOGGER.debug("Snapshotted Xaero tile dimension={} chunk={} {} hash={}",
					dimension, chunkX, chunkZ, Long.toUnsignedString(contentHash));
			return new MapTile(dimension, chunkX, chunkZ, baseStateIds, baseHeights, topHeights,
					biomeKeys, lightAbove, glowing, cave, overlays, contentHash);
		}

		@Override
		public void apply(MapTile source) throws ReflectiveOperationException {
			applyBatch(List.of(source));
		}

		@Override
		public void applyBatch(List<MapTile> sources) throws ReflectiveOperationException {
			MapTile first = sources.get(0);
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.level == null) {
				throw new IllegalStateException("Minecraft client world is not initialized");
			}
			String currentDimensionId = minecraft.level.dimension().location().toString();
			if (!isCurrentDimension(first.dimension(), currentDimensionId)) {
				XaeroMapsync_r.LOGGER.debug("Deferred Xaero apply because dimension is not current target={} current={} batchSize={}",
						first.dimension(), currentDimensionId, sources.size());
				throw new IllegalStateException("Xaero map tile dimension is not loaded yet: " + first.dimension());
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
			Throwable[] failure = new Throwable[1];
			XaeroMapsync_r.LOGGER.debug("Waiting for Xaero loading before applying batch dimension={} size={} region={} {}",
					first.dimension(), sources.size(), regionCoordinate(first.chunkX()), regionCoordinate(first.chunkZ()));
			invoke(waitForLoadingToFinish, processor, (Runnable) () -> {
				try {
					applyAfterLoading(sources, processor, saveLoad, biomeKeyManager, minecraft);
				} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
					failure[0] = exception;
				}
			});
			if (failure[0] != null) {
				XaeroMapsync_r.LOGGER.debug("Xaero apply failed after loading callback dimension={} size={} reason={}",
						first.dimension(), sources.size(), failure[0].getMessage());
				throwCause(failure[0]);
			}
		}

		private void applyAfterLoading(List<MapTile> sources, Object processor, Object saveLoad,
				Object biomeKeyManager, Minecraft minecraft)
				throws ReflectiveOperationException {
			MapTile source = sources.get(0);
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
				XaeroMapsync_r.LOGGER.debug("Requested Xaero region load for apply dimension={} region={} {} size={}",
						source.dimension(), regionX, regionZ, sources.size());
				throw new IllegalStateException("Xaero region is not fully loaded: state=0");
			}

			Object writerSync = writerThreadPauseSyncField.get(region);
			synchronized (writerSync) {
				synchronized (region) {
					byte loadState = ((Number) invoke(getRegionLoadState, region)).byteValue();
					if (loadState < 0 || loadState > 4) {
						throw new IllegalStateException("Unsupported Xaero region load state " + loadState);
					}
					if (loadState == 2) {
						XaeroMapsync_r.LOGGER.debug("Xaero region ready for apply dimension={} region={} {} size={}",
								source.dimension(), regionX, regionZ, sources.size());
						applyLocked(sources, processor, saveLoad, region, biomeKeyManager, minecraft);
						return;
					}
					boolean reloadRequested = (Boolean) invoke(reloadHasBeenRequested, region);
					boolean recacheRequested = (Boolean) invoke(recacheHasBeenRequested, region);
					boolean queuedForLoad;
					Object toLoad = toLoadField.get(saveLoad);
					synchronized (toLoad) {
						queuedForLoad = ((List<?>) toLoad).contains(region);
					}
					if (shouldRequestRegionLoad(loadState, reloadRequested, queuedForLoad, recacheRequested)) {
						invoke(setBeingWritten, region, true);
						invoke(requestLoad, saveLoad, region, "shared map sync retry");
						XaeroMapsync_r.LOGGER.debug(
								"Requested Xaero region reload for apply dimension={} region={} {} state={} reloadRequested={} queuedForLoad={} recacheRequested={}",
								source.dimension(), regionX, regionZ, loadState, reloadRequested, queuedForLoad,
								recacheRequested);
					}
					XaeroMapsync_r.LOGGER.debug(
							"Deferred Xaero apply dimension={} region={} {} state={} reloadRequested={} queuedForLoad={} recacheRequested={}",
							source.dimension(), regionX, regionZ, loadState, reloadRequested, queuedForLoad,
							recacheRequested);
					throw new IllegalStateException("Xaero region " + regionX + "," + regionZ
							+ " is not fully loaded: state=" + loadState);
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
			String[] biomeKeys = source.biomeKeys();
			byte[] lights = source.lightAbove();
			boolean[] glowing = source.glowing();
			boolean[] cave = source.cave();
			Registry<Biome> biomeRegistry = minecraft.level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
			for (int localZ = 0; localZ < TILE_SIDE; localZ++) {
				for (int localX = 0; localX < TILE_SIDE; localX++) {
					int index = localZ * TILE_SIDE + localX;
					BlockState state = Block.stateById(stateIds[index]);
					ResourceLocation biomeId = new ResourceLocation(biomeKeys[index]);
					Biome biome = biomeRegistry.get(biomeId);
					if (state == null || biome == null) {
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
						if (sourceOverlay.opacity() > 0) {
							invoke(increaseOverlayOpacity, overlay, sourceOverlay.opacity());
						}
						invoke(addOverlay, block, overlay);
					}
					invoke(writeBlock, block, state, baseHeights[index], topHeights[index], biomeKey, lights[index],
							glowing[index], cave[index]);
					invoke(setSlopeUnknown, block, true);
					invoke(setBlock, xaeroTile, localX, localZ, block);
				}
			}
			invoke(setWorldInterpretationVersion, xaeroTile, 1);
			invoke(setWrittenOnce, xaeroTile, true);
			invoke(setLoaded, xaeroTile, true);
			return xaeroTile;
		}

		@SuppressWarnings("unchecked")
		private void applyLocked(List<MapTile> sources, Object processor, Object saveLoad, Object region,
				Object biomeKeyManager, Minecraft minecraft)
				throws ReflectiveOperationException {
			MapTile source = sources.get(0);
			byte originalRegionLoadState = ((Number) invoke(getRegionLoadState, region)).byteValue();
			boolean originalBeingWritten = (Boolean) invoke(isBeingWritten, region);
			boolean originalRefreshing = (Boolean) invoke(isRefreshing, region);
			boolean originalRegionTerrain = regionHasHadTerrainField.getBoolean(region);
			boolean originalShouldCache = (Boolean) invoke(shouldCache, region);
			boolean originalRecacheRequested = (Boolean) invoke(recacheHasBeenRequested, region);
			boolean removedNativeCacheRequest = false;
			List<Object> toSave = (List<Object>) invoke(getToSave, saveLoad);
			if (toSave.contains(region)) {
				throw new IllegalStateException("Xaero region save is pending");
			}
			if ((Boolean) invoke(isWritingPaused, region)) {
				throw new IllegalStateException("Xaero region writing is paused");
			}
			if (originalRefreshing) {
				throw new IllegalStateException("Xaero region refresh is pending");
			}
			if (originalRegionLoadState != 2) {
				throw new IllegalStateException("Xaero region " + regionCoordinate(source.chunkX()) + ","
						+ regionCoordinate(source.chunkZ()) + " is not fully loaded: state=" + originalRegionLoadState);
			}
			Object shapeCache = invoke(getBlockStateShortShapeCache, processor);
			Map<Long, ChunkSnapshot> chunks = new LinkedHashMap<>();
			List<TileMutation> mutations = new ArrayList<>(sources.size());

			try {
				if ((Boolean) invoke(toCacheContains, saveLoad, region)) {
					// This queued cache predates the incoming tile. Cancel both the queue
					// entry and the region's cache intent before preCache() can save stale
					// prepared flags; refresh queues a replacement after rebuilding.
					invoke(removeToCache, saveLoad, region);
					invoke(setShouldCache, region, false, "shared map sync mutation");
					invoke(setRecacheHasBeenRequested, region, false, "shared map sync mutation");
					removedNativeCacheRequest = true;
					XaeroMapsync_r.LOGGER.debug("Removed stale Xaero native cache request before apply dimension={} region={} {}",
							source.dimension(), regionCoordinate(source.chunkX()), regionCoordinate(source.chunkZ()));
				}
				List<Object> xaeroTiles = new ArrayList<>(sources.size());
				for (MapTile tile : sources) {
					xaeroTiles.add(createTile(tile, processor, biomeKeyManager, minecraft));
				}
				XaeroMapsync_r.LOGGER.debug(
						"Created Xaero tile objects for apply dimension={} batchSize={} originalRegionLoadState={} originalBeingWritten={} originalRefreshing={} originalShouldCache={} originalRecacheRequested={}",
						source.dimension(), sources.size(), originalRegionLoadState, originalBeingWritten,
						originalRefreshing, originalShouldCache, originalRecacheRequested);
				for (int index = 0; index < sources.size(); index++) {
					MapTile tile = sources.get(index);
					int tileChunkX = tileChunkCoordinate(tile.chunkX());
					int tileChunkZ = tileChunkCoordinate(tile.chunkZ());
					long chunkKey = ((long) tileChunkX << 32) ^ (tileChunkZ & 0xffffffffL);
					ChunkSnapshot snapshot = chunks.get(chunkKey);
					if (snapshot == null) {
						int chunkInRegionX = Math.floorMod(tileChunkX, 8);
						int chunkInRegionZ = Math.floorMod(tileChunkZ, 8);
						Object chunk = invoke(getChunk, region, chunkInRegionX, chunkInRegionZ);
						boolean created = chunk == null;
						if (created) {
							chunk = construct(mapTileChunkConstructor, region, tileChunkX, tileChunkZ);
							invoke(setChunkLoadState, chunk, (byte) 2);
							XaeroMapsync_r.LOGGER.debug("Created missing Xaero tile chunk dimension={} tileChunk={} {} regionSlot={} {}",
									tile.dimension(), tileChunkX, tileChunkZ, chunkInRegionX, chunkInRegionZ);
						}
						snapshot = new ChunkSnapshot(chunk, chunkInRegionX, chunkInRegionZ, created,
								((Number) invoke(getChunkLoadState, chunk)).byteValue(),
								(Boolean) invoke(wasChanged, chunk), chunkIncludeInSaveField.getBoolean(chunk),
								chunkHasHadTerrainField.getBoolean(chunk));
						chunks.put(chunkKey, snapshot);
						if (created) invoke(setChunk, region, chunkInRegionX, chunkInRegionZ, chunk);
					}
					int tileInChunkX = Math.floorMod(tile.chunkX(), 4);
					int tileInChunkZ = Math.floorMod(tile.chunkZ(), 4);
					Object oldTile = invoke(getTile, snapshot.chunk, tileInChunkX, tileInChunkZ);
					mutations.add(new TileMutation(snapshot.chunk, tileInChunkX, tileInChunkZ, oldTile));
					invoke(setTile, snapshot.chunk, tileInChunkX, tileInChunkZ, xaeroTiles.get(index), shapeCache);
					invoke(setChanged, snapshot.chunk, true);
					invoke(setHasHadTerrain, snapshot.chunk);
				}
				// Xaero owns allCachePrepared and only invalidates it when refresh processing
				// actually schedules cache work. Pre-clearing it can queue an impossible save.
				invoke(requestRefresh, region, processor);
				deferNativeSave(region, originalBeingWritten);
				XaeroMapsync_r.LOGGER.debug("Finished Xaero apply mutation dimension={} region={} {} batchSize={} chunksTouched={} mutations={}",
						source.dimension(), regionCoordinate(source.chunkX()), regionCoordinate(source.chunkZ()),
						sources.size(), chunks.size(), mutations.size());
			} catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
				XaeroMapsync_r.LOGGER.warn("Rolling back Xaero apply mutation dimension={} region={} {} batchSize={} chunksTouched={} mutations={} reason={}",
						source.dimension(), regionCoordinate(source.chunkX()), regionCoordinate(source.chunkZ()),
						sources.size(), chunks.size(), mutations.size(), exception.getMessage());
				try {
					if ((Boolean) invoke(isRefreshing, region)) {
						invoke(cancelRefresh, region, processor);
					}
					for (int index = mutations.size() - 1; index >= 0; index--) {
						TileMutation mutation = mutations.get(index);
						invoke(setTile, mutation.chunk, mutation.tileX, mutation.tileZ, mutation.oldTile, shapeCache);
					}
					for (ChunkSnapshot snapshot : chunks.values()) {
						if (snapshot.created) {
							invoke(setChunk, region, snapshot.regionX, snapshot.regionZ, null);
						} else {
							invoke(setChunkLoadState, snapshot.chunk, snapshot.loadState);
							invoke(setChanged, snapshot.chunk, snapshot.changed);
							chunkIncludeInSaveField.setBoolean(snapshot.chunk, snapshot.includeInSave);
							chunkHasHadTerrainField.setBoolean(snapshot.chunk, snapshot.hadTerrain);
						}
					}
					regionHasHadTerrainField.setBoolean(region, originalRegionTerrain);
					invoke(setBeingWritten, region, originalBeingWritten);
					if (removedNativeCacheRequest && !(Boolean) invoke(toCacheContains, saveLoad, region)) {
						invoke(setShouldCache, region, originalShouldCache, "shared map sync rollback");
						invoke(setRecacheHasBeenRequested, region, originalRecacheRequested, "shared map sync rollback");
						invoke(requestCache, saveLoad, region);
						XaeroMapsync_r.LOGGER.debug("Restored Xaero native cache request during rollback dimension={} region={} {}",
								source.dimension(), regionCoordinate(source.chunkX()), regionCoordinate(source.chunkZ()));
					}
				} catch (ReflectiveOperationException | RuntimeException | LinkageError rollbackException) {
					exception.addSuppressed(rollbackException);
				}
				throw exception;
			}
		}

		private record ChunkSnapshot(Object chunk, int regionX, int regionZ, boolean created, byte loadState,
				boolean changed, boolean includeInSave, boolean hadTerrain) {}

		private record TileMutation(Object chunk, int tileX, int tileZ, Object oldTile) {}

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
				XaeroMapsync_r.LOGGER.debug("Deferred Xaero native save originalLastSaveTime={} newLastSaveTime={} originalBeingWritten={}",
						originalLastSaveTime, now, originalBeingWritten);
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
