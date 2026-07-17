package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStore;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.ChunkPos;

public final class ClientMapTileCache {
	private final Map<String, Long> appliedRevisions = new LinkedHashMap<>();
	private final Map<String, Long> cachedHashes = new LinkedHashMap<>();
	private final Map<String, Long> cachedRevisions = new LinkedHashMap<>();
	private final Map<String, PendingCacheWrite> pendingWrites = new LinkedHashMap<>();
	private final MapTileDataStore tileBodies = new MapTileDataStore();
	private ExecutorService readers;
	private ScheduledExecutorService retries;

	public synchronized void start(Path root) {
		if (readers != null) return;
		tileBodies.start(root);
		readers = Executors.newFixedThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable, "xaero-mapsync-client-cache-reader");
			thread.setDaemon(true);
			return thread;
		});
		retries = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "xaero-mapsync-client-cache-retry");
			thread.setDaemon(true);
			return thread;
		});
	}

	public synchronized boolean hasRevision(String dimension, int chunkX, int chunkZ, long revision) {
		return appliedRevisions.getOrDefault(key(dimension, chunkX, chunkZ), -1L) >= revision;
	}

	public synchronized long appliedRevision(String dimension, int chunkX, int chunkZ) {
		return appliedRevisions.getOrDefault(key(dimension, chunkX, chunkZ), -1L);
	}

	public synchronized void markApplied(MapTile tile, long revision) {
		String key = key(tile.dimension(), tile.chunkX(), tile.chunkZ());
		long current = appliedRevisions.getOrDefault(key, -1L);
		if (revision >= current) appliedRevisions.put(key, revision);
	}

	public void cache(MapTile tile, long revision) {
		if (!tile.hasRenderableSurface()) {
			XaeroMapsync_r.LOGGER.warn("Rejected unrenderable client map tile cache body {} {} {} revision={}",
					tile.dimension(), tile.chunkX(), tile.chunkZ(), revision);
			return;
		}
		String key = key(tile.dimension(), tile.chunkX(), tile.chunkZ());
		synchronized (this) {
			long cachedRevision = cachedRevisions.getOrDefault(key, -1L);
			if (revision < cachedRevision) return;
			Long cachedHash = cachedHashes.get(key);
			if (revision == cachedRevision && cachedHash != null && cachedHash == tile.contentHash()) return;
			cachedRevisions.put(key, revision);
			cachedHashes.put(key, tile.contentHash());
		}
		PendingCacheWrite pending = new PendingCacheWrite(tile, revision, 0);
		synchronized (this) { pendingWrites.put(key, pending); }
		attemptCacheWrite(key, pending);
	}

	private void attemptCacheWrite(String key, PendingCacheWrite pending) {
		synchronized (this) {
			if (pendingWrites.get(key) != pending || retries == null) return;
		}
		boolean accepted = tileBodies.putAsynchronously(pending.tile, successful -> {
			if (successful) {
				synchronized (ClientMapTileCache.this) {
					if (pendingWrites.get(key) == pending) pendingWrites.remove(key);
				}
				return;
			}
			scheduleCacheRetry(key, pending);
		});
		if (!accepted) scheduleCacheRetry(key, pending);
	}

	private void scheduleCacheRetry(String key, PendingCacheWrite failed) {
		ScheduledExecutorService activeRetries;
		PendingCacheWrite retry = new PendingCacheWrite(failed.tile, failed.revision, failed.attempts + 1);
		synchronized (this) {
			if (pendingWrites.get(key) != failed) return;
			pendingWrites.put(key, retry);
			activeRetries = retries;
		}
		if (activeRetries == null) return;
		long delayMillis = Math.min(5_000L, 100L << Math.min(retry.attempts, 5));
		activeRetries.schedule(() -> attemptCacheWrite(key, retry), delayMillis, TimeUnit.MILLISECONDS);
	}

	public CompletableFuture<Optional<MapTile>> findCached(MapTileIndexEntry entry) {
		ExecutorService activeReaders;
		synchronized (this) {
			activeReaders = readers;
		}
		if (activeReaders == null) return CompletableFuture.completedFuture(Optional.empty());
		return CompletableFuture.supplyAsync(() -> {
			Optional<MapTile> cached = tileBodies.find(entry.dimension(), entry.chunkX(), entry.chunkZ())
					.filter(tile -> tile.contentHash() == entry.contentHash())
					.filter(MapTile::hasRenderableSurface);
			cached.ifPresent(tile -> {
				synchronized (ClientMapTileCache.this) {
					cachedHashes.put(key(tile.dimension(), tile.chunkX(), tile.chunkZ()), tile.contentHash());
				}
			});
			return cached;
		}, activeReaders).exceptionally(exception -> {
			XaeroMapsync_r.LOGGER.warn("Failed to read cached client map tile {} {} {}", entry.dimension(),
					entry.chunkX(), entry.chunkZ(), exception);
			return Optional.empty();
		});
	}

	/** Applied revisions only prove injection into the current Xaero session. */
	public synchronized void clearSession() {
		appliedRevisions.clear();
	}

	public synchronized int totalCount() {
		return appliedRevisions.size();
	}

	public synchronized int pendingWriteCount() {
		return pendingWrites.size();
	}

	public void stop() {
		ExecutorService activeReaders;
		ScheduledExecutorService activeRetries;
		synchronized (this) {
			activeReaders = readers;
			readers = null;
			activeRetries = retries;
			retries = null;
			pendingWrites.clear();
		}
		if (activeReaders != null) activeReaders.shutdownNow();
		if (activeRetries != null) activeRetries.shutdownNow();
		tileBodies.stop();
	}

	private record PendingCacheWrite(MapTile tile, long revision, int attempts) {}

	private static String key(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + ChunkPos.asLong(chunkX, chunkZ);
	}
}
