package cn.net.rms.xaeromapsync_r.server;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileDebugRenderer;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexStore;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStore;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.network.CompressionCodec;
import cn.net.rms.xaeromapsync_r.network.TileDataPayload;
import cn.net.rms.xaeromapsync_r.server.dirty.DirtyChunkProcessor;
import cn.net.rms.xaeromapsync_r.server.dirty.DirtyChunkStore;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import cn.net.rms.xaeromapsync_r.server.activity.RegionActivityState;
import cn.net.rms.xaeromapsync_r.server.activity.RegionActivityStore;
import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;

public final class MapTaskScheduler {
	private static final double EWMA_ALPHA = 0.1D;
	private static final int SAMPLE_WINDOW = 1200;
	private static final long INITIAL_MAP_WORK_BUDGET_NANOS = 1_000_000L;
	private final DirtyChunkStore dirtyChunks;
	private final MapTileIndexStore mapTiles;
	private final MapTileDataStore tileData;
	private final DirtyChunkProcessor processor;
	private final RegionActivityStore activity;
	private MinecraftServer activeServer;
	private long tickStartedNanos;
	private long lastTickNanos;
	private long lastMapWorkNanos;
	private double averageMspt;
	private final double[] msptSamples = new double[SAMPLE_WINDOW];
	private int sampleCursor;
	private int sampleCount;
	private double lastMspt;
	private double lastTaskMillis;
	private boolean paused;
	private boolean drainRequested;

	public MapTaskScheduler(DirtyChunkStore dirtyChunks, MapTileIndexStore mapTiles, MapTileDataStore tileData, RegionActivityStore activity) {
		this.dirtyChunks = dirtyChunks;
		this.mapTiles = mapTiles;
		this.tileData = tileData;
		this.activity = activity;
		this.processor = new DirtyChunkProcessor(dirtyChunks, this::isLoaded, this::recalculate);
	}

	public void register() {
		ServerTickEvents.START_SERVER_TICK.register(server -> tickStartedNanos = System.nanoTime());
		ServerTickEvents.END_SERVER_TICK.register(this::endTick);
	}

	private void endTick(MinecraftServer server) {
		long elapsedBeforeMapWork = System.nanoTime() - tickStartedNanos;
		if (!shouldRunAutomaticRendering(SharedMapConfig.serverMapRenderingEnabled(), paused,
				SharedMapConfig.highLoadPause(), averageMspt, SharedMapConfig.highLoadMsptThreshold())) {
			lastMapWorkNanos = 0L;
			lastTaskMillis = 0.0D;
			recordCompletedTick(elapsedBeforeMapWork);
			return;
		}
		activeServer = server;
		long taskStartedNanos = System.nanoTime();
		try {
			int configuredBudget = drainRequested
					? SharedMapConfig.dirtyDrainBudgetPerTick()
					: SharedMapConfig.dirtyChunksPerTick();
			int renderLimit = renderLimit(configuredBudget, SharedMapConfig.maxTileRendersPerTick());
			int scanBudget = Math.max(renderLimit, SharedMapConfig.dirtyChunkScanPerTick());
			long targetTickNanos = Math.max(0L, SharedMapConfig.highLoadMsptThreshold() - 2L) * 1_000_000L;
			long workBudgetNanos = adaptiveMapWorkBudgetNanos(
					elapsedBeforeMapWork,
					lastTickNanos,
					lastMapWorkNanos,
					targetTickNanos,
					SharedMapConfig.mapRenderBudgetMillis() * 1_000_000L);
			if (renderLimit > 0 && workBudgetNanos > 0L) {
				long deadlineNanos = taskStartedNanos + workBudgetNanos;
				processor.processTick(renderLimit, scanBudget, () -> System.nanoTime() < deadlineNanos);
			}
			drainRequested = false;
		} finally {
			lastMapWorkNanos = System.nanoTime() - taskStartedNanos;
			lastTaskMillis = lastMapWorkNanos / 1_000_000.0D;
			activeServer = null;
			recordCompletedTick(System.nanoTime() - tickStartedNanos);
		}
	}

	private void recordCompletedTick(long elapsedNanos) {
		lastTickNanos = Math.max(0L, elapsedNanos);
		double elapsedMillis = lastTickNanos / 1_000_000.0D;
		lastMspt = elapsedMillis;
		msptSamples[sampleCursor] = elapsedMillis;
		sampleCursor = (sampleCursor + 1) % SAMPLE_WINDOW;
		sampleCount = Math.min(SAMPLE_WINDOW, sampleCount + 1);
		averageMspt = averageMspt == 0.0D ? elapsedMillis : averageMspt + EWMA_ALPHA * (elapsedMillis - averageMspt);
	}

	static int renderLimit(int configuredLimit, int safetyLimit) {
		return Math.max(0, Math.min(configuredLimit, safetyLimit));
	}

	static boolean shouldRunAutomaticRendering(boolean enabled, boolean paused,
			boolean pauseUnderHighLoad, double averageMspt, int highLoadMsptThreshold) {
		return enabled && !paused && (!pauseUnderHighLoad || averageMspt < highLoadMsptThreshold);
	}

	static long adaptiveMapWorkBudgetNanos(long currentTickElapsedNanos, long previousTickNanos,
			long previousMapWorkNanos, long targetTickNanos, long configuredBudgetNanos) {
		long currentRemaining = remainingNanos(targetTickNanos, currentTickElapsedNanos);
		long configured = Math.max(0L, configuredBudgetNanos);
		if (previousTickNanos <= 0L)
			return Math.min(INITIAL_MAP_WORK_BUDGET_NANOS, Math.min(configured, currentRemaining));
		long previousBaseWork = Math.max(0L, previousTickNanos - Math.max(0L, previousMapWorkNanos));
		long previousRemaining = remainingNanos(targetTickNanos, previousBaseWork);
		return Math.min(configured, Math.min(currentRemaining, previousRemaining));
	}

	private static long remainingNanos(long targetNanos, long elapsedNanos) {
		return Math.max(0L, targetNanos - Math.max(0L, elapsedNanos));
	}

	private boolean isLoaded(DirtyChunkStore.StableDirtyChunk chunk) {
		RegionKey key = RegionKey.fromChunk(chunk.dimension(), chunk.chunkX(), chunk.chunkZ());
		if (activity.isPaused(key) || activity.get(key).map(record -> record.state() == RegionActivityState.STORM
				|| record.state() == RegionActivityState.COOLDOWN).orElse(false)) {
			return false;
		}
		ServerLevel level = level(chunk.dimension());
		return level != null && level.getChunkSource().hasChunk(chunk.chunkX(), chunk.chunkZ());
	}

	private boolean recalculate(DirtyChunkStore.StableDirtyChunk chunk) {
		if (!tileData.hasWriteCapacity(chunk.dimension(), chunk.chunkX(), chunk.chunkZ())) return false;
		ServerLevel level = level(chunk.dimension());
		if (level == null) {
			return false;
		}
		MapTile tile = MapTileDebugRenderer.renderIfLoaded(level, chunk.chunkX(), chunk.chunkZ());
		if (tile == null) {
			return false;
		}
		if (!tile.hasRenderableSurface()) {
			return false;
		}
		byte[] surfacePayload;
		try {
			surfacePayload = CompressionCodec.encodeSurface(CompressionCodec.MapTileSurfaceData.fromTile(tile),
					SharedMapConfig.compression());
			// Validate the final wire payload limit before durable publication. A tile
			// that clients can never receive must remain dirty and retryable.
			new TileDataPayload(tile, 0L, SharedMapConfig.compression(), surfacePayload);
		} catch (RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Rejected untransmittable map tile {} {} {}",
					tile.dimension(), tile.chunkX(), tile.chunkZ(), exception);
			return false;
		}
		MinecraftServer server = activeServer;
		return tileData.putAsynchronously(tile, successful -> {
			if (!successful) {
				server.execute(() -> processor.completeRecalculation(chunk, false));
				return;
			}
			server.execute(() -> publishPersistedTile(chunk, tile));
		});
	}

	private void publishPersistedTile(DirtyChunkStore.StableDirtyChunk chunk, MapTile tile) {
		if (!dirtyChunks.isCurrentClaim(chunk)) {
			processor.completeRecalculation(chunk, true);
			return;
		}
		MapTile latestBody = tileData.find(tile.dimension(), tile.chunkX(), tile.chunkZ()).orElse(null);
		if (latestBody == null || latestBody.contentHash() != tile.contentHash()) {
			processor.completeRecalculation(chunk, false);
			return;
		}
		MapTileIndexEntry previous;
		MapTileIndexEntry entry;
		try {
			previous = mapTiles.find(tile.dimension(), tile.chunkX(), tile.chunkZ()).orElse(null);
			entry = mapTiles.upsert(tile);
		} catch (RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to publish persisted tile index for {} {} {}",
					tile.dimension(), tile.chunkX(), tile.chunkZ(), exception);
			processor.completeRecalculation(chunk, false);
			return;
		}
		if (processor.completeRecalculation(chunk, true) != DirtyChunkProcessor.CompletionResult.COMPLETED) {
			return;
		}
		if (previous == null || previous.revision() != entry.revision()) {
			XaeroMapsync_r.LOGGER.debug(
					"map_sync event=tile_coalesce_queued dimension={} chunk_x={} chunk_z={} revision={} window_ms={}",
					tile.dimension(), tile.chunkX(), tile.chunkZ(), entry.revision(), 2_000);
		}
	}

	static boolean persistThenIndex(MapTile tile, MapTileDataStore tileData, MapTileIndexStore mapTiles) {
		if (!tileData.putSynchronously(tile)) return false;
		mapTiles.upsert(tile);
		return true;
	}

	private ServerLevel level(String dimension) {
		if (activeServer == null) {
			return null;
		}
		return activeServer.getLevel(ResourceKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(dimension)));
	}

	public synchronized void setPaused(boolean paused) {
		this.paused = paused;
		dirtyChunks.setPaused(paused);
	}

	public synchronized boolean paused() { return paused; }
	public synchronized double averageMspt() { return averageMspt; }
	public synchronized double lastMspt() { return lastMspt; }
	public synchronized double lastTaskMillis() { return lastTaskMillis; }
	public long completedTiles() { return processor.statistics().completed(); }
	public synchronized double p95Mspt() {
		if (sampleCount == 0) return 0.0D;
		double[] copy = java.util.Arrays.copyOf(msptSamples, sampleCount);
		java.util.Arrays.sort(copy);
		return copy[(int) Math.ceil(copy.length * 0.95D) - 1];
	}
	public synchronized void requestDrain() { drainRequested = true; }
	public DirtyChunkProcessor.Statistics statistics() { return processor.statistics(); }
}
