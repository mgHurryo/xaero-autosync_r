package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStore;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.ChunkPos;

public final class ClientMapTileCache {
	private static final int MAX_TRACKED_TILE_METADATA = 65_536;
	private final Map<String, Long> appliedRevisions = boundedAccessMap(MAX_TRACKED_TILE_METADATA);
	private final Map<String, Long> cachedHashes = boundedAccessMap(MAX_TRACKED_TILE_METADATA);
	private final Map<String, Long> cachedRevisions = boundedAccessMap(MAX_TRACKED_TILE_METADATA);
	private final Map<String, PendingCacheWrite> pendingWrites = new LinkedHashMap<>();
	private final Set<String> writesInFlight = new HashSet<>();
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
		retries.scheduleWithFixedDelay(this::drainPendingWritesSafely, 25L, 25L, TimeUnit.MILLISECONDS);
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
			if (sameCachedTile(revision, cachedRevision, cachedHash, tile.contentHash())) return;
			cachedRevisions.put(key, revision);
			cachedHashes.put(key, tile.contentHash());
		}
		PendingCacheWrite pending = new PendingCacheWrite(tile, revision, 0, 0L);
		synchronized (this) { pendingWrites.put(key, pending); }
		// The 25 ms background pump batches scheduling. Draining here once per
		// tile turns a large applied wave into repeated scans of the same pending
		// map on the render thread.
	}

	static boolean sameCachedTile(long revision, long cachedRevision, Long cachedHash, long contentHash) {
		if (revision != cachedRevision || cachedHash == null) return false;
		return cachedHash.longValue() == contentHash;
	}

	/** Returns true only when every tile in a possibly reshaped patch is already applied. */
	public synchronized boolean hasApplied(MapPatchManifest manifest) {
		for (MapPatchManifest.TileReference tile : manifest.tiles()) {
			if (appliedRevisions.getOrDefault(key(manifest.key().dimension(), tile.chunkX(), tile.chunkZ()), -1L)
					< tile.revision()) return false;
		}
		return !manifest.tiles().isEmpty();
	}

	private void drainPendingWritesSafely() {
		try {
			drainPendingWrites();
		} catch (RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("map_sync event=client_cache_drain_failed pending={}", pendingWriteCount(), exception);
		}
	}

	private void drainPendingWrites() {
		long now = System.currentTimeMillis();
		java.util.List<Map.Entry<String, PendingCacheWrite>> candidates;
		synchronized (this) {
			if (retries == null) return;
			candidates = pendingWrites.entrySet().stream()
					.filter(entry -> !writesInFlight.contains(entry.getKey()))
					.filter(entry -> entry.getValue().retryNotBeforeMillis <= now)
					.limit(64).map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
		}
		for (Map.Entry<String, PendingCacheWrite> candidate : candidates) {
			String key = candidate.getKey();
			PendingCacheWrite pending = candidate.getValue();
			if (!tileBodies.hasWriteCapacity(pending.tile.dimension(), pending.tile.chunkX(), pending.tile.chunkZ()))
				continue;
			synchronized (this) {
				if (pendingWrites.get(key) != pending || !writesInFlight.add(key)) continue;
			}
			boolean accepted = tileBodies.putAsynchronously(pending.tile,
					successful -> completeCacheWrite(key, pending, successful));
			if (!accepted) {
				synchronized (this) { writesInFlight.remove(key); }
			}
		}
	}

	private void completeCacheWrite(String key, PendingCacheWrite completed, boolean successful) {
		synchronized (this) {
			writesInFlight.remove(key);
			if (pendingWrites.get(key) != completed) return;
			if (successful) {
				pendingWrites.remove(key);
				return;
			}
			int attempts = completed.attempts + 1;
			long delayMillis = Math.min(5_000L, 100L << Math.min(attempts, 5));
			pendingWrites.put(key, new PendingCacheWrite(completed.tile, completed.revision, attempts,
					System.currentTimeMillis() + delayMillis));
		}
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
		flushPendingWrites();
		ExecutorService activeReaders;
		ScheduledExecutorService activeRetries;
		synchronized (this) {
			activeReaders = readers;
			readers = null;
			activeRetries = retries;
			retries = null;
			pendingWrites.clear();
			writesInFlight.clear();
		}
		if (activeReaders != null) activeReaders.shutdownNow();
		if (activeRetries != null) activeRetries.shutdownNow();
		tileBodies.stop();
	}

	private void flushPendingWrites() {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
		while (pendingWriteCount() > 0 && System.nanoTime() < deadline) {
			drainPendingWritesSafely();
			try {
				Thread.sleep(10L);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		int remaining = pendingWriteCount();
		if (remaining > 0) XaeroMapsync_r.LOGGER.warn(
				"map_sync event=client_cache_flush_timeout pending={} timeout_ms=10000", remaining);
	}

	private record PendingCacheWrite(MapTile tile, long revision, int attempts, long retryNotBeforeMillis) {}

	static <K, V> Map<K, V> boundedAccessMap(int maximumEntries) {
		if (maximumEntries <= 0) throw new IllegalArgumentException("Maximum cache entries must be positive");
		return new LinkedHashMap<>(128, 0.75F, true) {
			@Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > maximumEntries;
			}
		};
	}

	private static String key(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + ChunkPos.asLong(chunkX, chunkZ);
	}
}
