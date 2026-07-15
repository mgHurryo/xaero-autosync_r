package cn.net.rms.xaeromapsync_r.server;

import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileDebugRenderer;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexStore;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStore;
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
	private final DirtyChunkStore dirtyChunks;
	private final MapTileIndexStore mapTiles;
	private final MapTileDataStore tileData;
	private final DirtyChunkProcessor processor;
	private final RegionActivityStore activity;
	private MinecraftServer activeServer;
	private long tickStartedNanos;
	private double averageMspt;
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
		double elapsedMillis = (System.nanoTime() - tickStartedNanos) / 1_000_000.0D;
		averageMspt = averageMspt == 0.0D ? elapsedMillis : averageMspt + EWMA_ALPHA * (elapsedMillis - averageMspt);
		if (paused || SharedMapConfig.highLoadPause() && averageMspt >= SharedMapConfig.highLoadMsptThreshold()) {
			return;
		}
		activeServer = server;
		try {
			int budget = drainRequested ? SharedMapConfig.dirtyDrainBudgetPerTick() : SharedMapConfig.dirtyChunksPerTick();
			processor.processTick(budget);
			drainRequested = false;
		} finally {
			activeServer = null;
		}
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
		ServerLevel level = level(chunk.dimension());
		if (level == null) {
			return false;
		}
		MapTile tile = MapTileDebugRenderer.renderIfLoaded(level, chunk.chunkX(), chunk.chunkZ());
		if (tile == null) {
			return false;
		}
		mapTiles.upsert(tile);
		tileData.put(tile);
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
	public synchronized void requestDrain() { drainRequested = true; }
	public DirtyChunkProcessor.Statistics statistics() { return processor.statistics(); }
}
