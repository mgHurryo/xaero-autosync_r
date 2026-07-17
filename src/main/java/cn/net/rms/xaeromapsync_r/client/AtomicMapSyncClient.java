package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.map.MapPatch;
import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.network.PatchDataPayload;
import cn.net.rms.xaeromapsync_r.network.PatchManifestPagePayload;
import cn.net.rms.xaeromapsync_r.network.PatchUnavailablePayload;
import cn.net.rms.xaeromapsync_r.network.SharedMapNetworking;
import cn.net.rms.xaeromapsync_r.xaero.XaeroMapAdapter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;

/** Client entry point for protocol-v11 adaptive square patch synchronization. */
public final class AtomicMapSyncClient {
	private static final long TICK_BUDGET_NANOS = 4_000_000L;
	private static final int MAX_PATCH_REQUESTS_IN_FLIGHT = 8;
	private static final int SMALL_PATCH_MAX_SIDE = 2;
	private static final int SMALL_PATCH_COMMIT_BATCH = 128;
	private static final long SMALL_PATCH_COMMIT_WINDOW_MILLIS = 2_000L;
	private static final long GAP_RECOVERY_POLL_INTERVAL_MILLIS = 250L;
	private static final int MANIFEST_POLL_TICKS = 100;
	private static final int SUMMARY_TICKS = 200;
	private static final long PATCH_REQUEST_TIMEOUT_MILLIS = 15_000L;
	private static final long SMALL_PATCH_REQUEST_TIMEOUT_MILLIS = 300_000L;
	private static final int MAX_PATCH_RETRIES = 5;
	private static final int MAX_APPLIED_PATCH_HASHES = 16_384;
	private static final int MAX_RETIRED_RESPONSES = 64;
	private final ClientMapTileCache cache = new ClientMapTileCache();
	private final MapGapDetector gapDetector = new MapGapDetector();
	private final XaeroMapAdapter adapter;
	private final AtomicPatchCoordinator coordinator;
	private final ArrayDeque<MapPatchManifest> requestQueue = new ArrayDeque<>();
	private final Set<MapPatchKey> queuedKeys = new HashSet<>();
	private final Map<MapPatchKey, MapPatchManifest> inFlight = new LinkedHashMap<>();
	private final Map<MapPatchKey, Long> inFlightSince = new LinkedHashMap<>();
	private final Map<MapPatchKey, Long> retryNotBefore = new LinkedHashMap<>();
	private final Map<MapPatchKey, Integer> retryAttempts = new LinkedHashMap<>();
	private final Map<MapPatchKey, Long> appliedHashes = SharedMapClient.boundedAccessMap(MAX_APPLIED_PATCH_HASHES);
	private final Map<RetiredResponseKey, MapPatchManifest> retiredResponses = new LinkedHashMap<>();
	private final Map<MapPatchKey, Long> expectedWaveHashes = new LinkedHashMap<>();
	private final Map<MapPatchKey, MapPatch> verifiedWavePatches = new LinkedHashMap<>();
	private final Map<MapPatchKey, MapPatch> verifiedSmallPatches = new LinkedHashMap<>();
	private long smallPatchWindowStartedMillis = -1L;
	private long nextGapRecoveryPollMillis;
	private boolean connected;
	private long syncId;
	private long epoch;
	private int manifestCursor;
	private int manifestTotal;
	private int ticks;
	private int appliedPatches;
	private int rejectedPatches;
	private int retries;
	private long lastProgressMillis;
	private double previousX;
	private double previousZ;
	private boolean previousPositionValid;
	private final long[] tickDurations = new long[200];
	private int durationCursor;
	private int durationCount;
	private boolean resyncRequested;
	private boolean manifestWaveComplete;

	public AtomicMapSyncClient(XaeroMapAdapter adapter, Path cacheRoot) {
		this.adapter = adapter;
		this.coordinator = new AtomicPatchCoordinator(adapter, this::markApplied, this::logTransition);
		cache.start(cacheRoot);
	}

	public void setConnected(boolean connected) {
		this.connected = connected;
		resetSession();
		if (connected) beginManifestSync();
	}

	public void tick() {
		long started = System.nanoTime();
		try {
			if (!connected || !SharedMapClientConfig.get().mapSyncEnabled() || !adapter.isAvailable()) return;
			coordinator.tick(System.currentTimeMillis(), started + TICK_BUDGET_NANOS);
			if (resyncRequested) {
				resyncRequested = false;
				beginManifestSync();
				return;
			}
			long nowMillis = System.currentTimeMillis();
			expirePatchRequests(nowMillis);
			pumpPatchRequests();
			if (shouldPollGapRecovery(nowMillis, nextGapRecoveryPollMillis)) {
				nextGapRecoveryPollMillis = nowMillis + GAP_RECOVERY_POLL_INTERVAL_MILLIS;
				List<cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry> recovery = gapDetector.poll(nowMillis, 2,
						cache::appliedRevision);
				if (!recovery.isEmpty()) {
					SharedMapNetworking.requestGapRecovery(recovery);
					XaeroMapsync_r.LOGGER.info("map_sync event=gap_recovery_requested tiles={} delay_ms={}",
							recovery.size(), MapGapDetector.DETECTION_DELAY_MILLIS);
				}
			}
			if (shouldFlushSmallWave(verifiedSmallPatches.size(), smallPatchWindowStartedMillis, nowMillis))
				flushVerifiedSmallWave();
			if (shouldPollManifest(++ticks, requestQueue.size(), inFlight.size(),
					verifiedWavePatches.size() + verifiedSmallPatches.size(),
					coordinator.pendingCount())) {
				beginManifestSync();
			}
			if (ticks % SUMMARY_TICKS == 0) logSummary();
		} finally {
			recordTickDuration(System.nanoTime() - started);
		}
	}

	public void handleManifestPage(PatchManifestPagePayload page) {
		if (!connected || page.syncId() != syncId) {
			XaeroMapsync_r.LOGGER.debug("map_sync event=manifest_ignored sync_id={} active_sync_id={}", page.syncId(), syncId);
			return;
		}
		if (manifestCursor > 0 && page.epoch() != epoch) {
			XaeroMapsync_r.LOGGER.info("map_sync event=epoch_changed old_epoch={} new_epoch={} action=restart",
					Long.toUnsignedString(epoch), Long.toUnsignedString(page.epoch()));
			beginManifestSync();
			return;
		}
		epoch = page.epoch();
		manifestCursor = page.nextCursor();
		manifestTotal = page.totalCount();
		int queued = 0;
		for (MapPatchManifest manifest : page.manifests()) {
			if (manifest.epoch() != epoch || !manifest.key().dimension().equals(currentDimension())) continue;
			// Adaptive repartitioning can change a patch key when neighbouring tiles
			// arrive. Tile revisions are the stable identity across those new squares.
			if (cache.hasApplied(manifest)) {
				appliedHashes.put(manifest.key(), manifest.contentHash());
				continue;
			}
			if (!shouldDownloadManifest(appliedHashes.get(manifest.key()), manifest.contentHash(),
					coordinator.hasPending(manifest.key()) || verifiedSmallPatches.containsKey(manifest.key()),
					inFlight.containsKey(manifest.key()),
					queuedKeys.contains(manifest.key()))) continue;
			queuedKeys.add(manifest.key());
			requestQueue.addLast(manifest);
			if (manifest.key().sideLength() > SMALL_PATCH_MAX_SIDE)
				expectedWaveHashes.put(manifest.key(), manifest.contentHash());
			queued++;
		}
		manifestWaveComplete = page.complete();
		lastProgressMillis = System.currentTimeMillis();
		XaeroMapsync_r.LOGGER.info(
				"map_sync event=manifest_page sync_id={} epoch={} cursor={} total={} received={} queued={} in_flight={} pending_commits={}",
				syncId, Long.toUnsignedString(epoch), manifestCursor, manifestTotal, page.manifests().size(), queued,
				inFlight.size(), coordinator.pendingCount());
		pumpPatchRequests();
		if (!page.complete()) requestManifestPage();
		releaseVerifiedWaveIfReady();
	}

	public void handlePatchData(PatchDataPayload payload) {
		MapPatch patch = payload.patch();
		MapPatchManifest current = inFlight.get(patch.manifest().key());
		boolean currentResponse = matchesResponse(current, patch.manifest());
		MapPatchManifest expected = currentResponse
				? inFlight.remove(patch.manifest().key())
				: retiredResponses.remove(RetiredResponseKey.from(patch.manifest()));
		if (currentResponse) inFlightSince.remove(patch.manifest().key());
		if (expected == null) {
			rejectedPatches++;
			XaeroMapsync_r.LOGGER.warn(
					"map_sync event=patch_rejected patch_id={} epoch={} reason=unexpected_manifest in_flight={}",
					patch.manifest().key().stableId(), Long.toUnsignedString(patch.manifest().epoch()), inFlight.size());
			pumpPatchRequests();
			return;
		}
		gapDetector.record(patch.manifest(), System.currentTimeMillis());
		if (!currentResponse) {
			// A manifest restart does not invalidate an already verified immutable
			// epoch response. Commit it instead of throwing away useful network work.
			if (!coordinator.enqueueVerified(patch)) {
				rejectedPatches++;
				XaeroMapsync_r.LOGGER.warn(
						"map_sync event=retired_patch_deferred patch_id={} epoch={} reason=commit_queue_full",
						patch.manifest().key().stableId(), Long.toUnsignedString(patch.manifest().epoch()));
			}
		} else if (patch.manifest().key().sideLength() <= SMALL_PATCH_MAX_SIDE) {
			verifiedSmallPatches.put(patch.manifest().key(), patch);
			if (smallPatchWindowStartedMillis < 0L) smallPatchWindowStartedMillis = System.currentTimeMillis();
			if (verifiedSmallPatches.size() >= SMALL_PATCH_COMMIT_BATCH) flushVerifiedSmallWave();
		} else {
			verifiedWavePatches.put(patch.manifest().key(), patch);
		}
		lastProgressMillis = System.currentTimeMillis();
		releaseVerifiedWaveIfReady();
		pumpPatchRequests();
	}

	public void handlePatchUnavailable(PatchUnavailablePayload unavailable) {
		MapPatchManifest manifest = inFlight.remove(unavailable.key());
		inFlightSince.remove(unavailable.key());
		boolean staleCatalog = requiresManifestRestart(unavailable.reason());
		if (staleCatalog) {
			// Retrying the same evicted epoch can never succeed. The next manifest
			// reuses tile-level applied revisions and only queues genuinely new holes.
			resyncRequested = true;
		} else if (manifest != null && manifest.epoch() == epoch) {
			retries++;
			scheduleRetry(manifest, System.currentTimeMillis());
		}
		XaeroMapsync_r.LOGGER.warn("map_sync event=patch_unavailable patch_id={} epoch={} reason={} retries={} action={}",
				unavailable.key().stableId(), Long.toUnsignedString(epoch), unavailable.reason(), retries,
				staleCatalog ? "restart_manifest" : "retry");
		if (!staleCatalog) pumpPatchRequests();
	}

	static boolean requiresManifestRestart(String reason) {
		return "missing-patch".equals(reason) || "missing-tile-body".equals(reason);
	}

	private void beginManifestSync() {
		if (!connected || currentDimension() == null) return;
		syncId = incrementNonZero(syncId);
		epoch = 0L;
		manifestCursor = 0;
		manifestTotal = 0;
		requestQueue.clear();
		queuedKeys.clear();
		for (MapPatchManifest manifest : inFlight.values()) {
			retiredResponses.put(RetiredResponseKey.from(manifest), manifest);
			while (retiredResponses.size() > MAX_RETIRED_RESPONSES)
				retiredResponses.remove(retiredResponses.keySet().iterator().next());
		}
		inFlight.clear();
		inFlightSince.clear();
		retryNotBefore.clear();
		retryAttempts.clear();
		expectedWaveHashes.clear();
		verifiedWavePatches.clear();
		manifestWaveComplete = false;
		lastProgressMillis = System.currentTimeMillis();
		requestManifestPage();
	}

	private void requestManifestPage() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || currentDimension() == null) return;
		double x = minecraft.player.getX();
		double z = minecraft.player.getZ();
		double motionX = previousPositionValid ? x - previousX : 0.0D;
		double motionZ = previousPositionValid ? z - previousZ : 0.0D;
		previousX = x;
		previousZ = z;
		previousPositionValid = true;
		SharedMapNetworking.requestPatchManifests(currentDimension(), syncId, epoch, manifestCursor,
				minecraft.player.chunkPosition().x, minecraft.player.chunkPosition().z, motionX, motionZ);
	}

	private void pumpPatchRequests() {
		int inspected = requestQueue.size();
		long now = System.currentTimeMillis();
		while (inspected-- > 0 && inFlight.size() < MAX_PATCH_REQUESTS_IN_FLIGHT && !requestQueue.isEmpty()) {
			MapPatchManifest manifest = requestQueue.removeFirst();
			queuedKeys.remove(manifest.key());
			if (manifest.epoch() != epoch) continue;
			boolean largePatchQueued = requestQueue.stream()
					.anyMatch(item -> item.key().sideLength() > SMALL_PATCH_MAX_SIDE);
			if (!canStartPatchRequest(manifest.key().sideLength(), inFlight.size(), largePatchQueued,
					manifestWaveComplete)) {
				enqueueLast(manifest);
				continue;
			}
			if (retryNotBefore.getOrDefault(manifest.key(), 0L) > now) {
				enqueueLast(manifest);
				continue;
			}
			inFlight.put(manifest.key(), manifest);
			inFlightSince.put(manifest.key(), now);
			SharedMapNetworking.requestPatch(manifest);
		}
	}

	private void releaseVerifiedWaveIfReady() {
		int queuedLarge = (int) requestQueue.stream()
				.filter(item -> item.key().sideLength() > SMALL_PATCH_MAX_SIDE).count();
		int inFlightLarge = (int) inFlight.values().stream()
				.filter(item -> item.key().sideLength() > SMALL_PATCH_MAX_SIDE).count();
		if (!canReleaseWave(manifestWaveComplete, queuedLarge, inFlightLarge,
				expectedWaveHashes.size(), verifiedWavePatches.size())) return;
		if (expectedWaveHashes.isEmpty()) return;
		List<MapPatch> wave = verifiedWavePatches.values().stream()
				.sorted(java.util.Comparator.comparing(patch -> patch.manifest().key())).toList();
		if (!coordinator.enqueueVerifiedWave(wave)) {
			XaeroMapsync_r.LOGGER.warn("map_sync event=wave_deferred patches={} reason=commit_queue_full",
					wave.size());
			return;
		}
		XaeroMapsync_r.LOGGER.info(
				"map_sync event=wave_released epoch={} patches={} regions={} tiles={} action=atomic_region_commit",
				Long.toUnsignedString(epoch), wave.size(),
				wave.stream().map(patch -> patch.manifest().key().xaeroRegionX() + ":"
						+ patch.manifest().key().xaeroRegionZ()).distinct().count(),
				wave.stream().mapToInt(patch -> patch.tiles().size()).sum());
		expectedWaveHashes.clear();
		verifiedWavePatches.clear();
	}

	private void flushVerifiedSmallWave() {
		if (verifiedSmallPatches.isEmpty()) return;
		List<MapPatch> wave = verifiedSmallPatches.values().stream()
				.sorted(java.util.Comparator.comparing(patch -> patch.manifest().key())).toList();
		if (!coordinator.enqueueVerifiedWave(wave)) {
			XaeroMapsync_r.LOGGER.debug("map_sync event=small_wave_deferred patches={} reason=commit_queue_full",
					wave.size());
			return;
		}
		XaeroMapsync_r.LOGGER.info(
				"map_sync event=small_wave_released patches={} regions={} tiles={} window_ms={} action=batched_region_commit",
				wave.size(), wave.stream().map(patch -> patch.manifest().key().xaeroRegionX() + ":"
						+ patch.manifest().key().xaeroRegionZ()).distinct().count(),
				wave.stream().mapToInt(patch -> patch.tiles().size()).sum(),
				Math.max(0L, System.currentTimeMillis() - smallPatchWindowStartedMillis));
		verifiedSmallPatches.clear();
		smallPatchWindowStartedMillis = -1L;
		nextGapRecoveryPollMillis = 0L;
	}

	private void expirePatchRequests(long nowMillis) {
		java.util.List<MapPatchKey> expired = inFlightSince.entrySet().stream()
				.filter(entry -> {
					MapPatchManifest manifest = inFlight.get(entry.getKey());
					long timeout = manifest != null && manifest.key().sideLength() <= SMALL_PATCH_MAX_SIDE
							? SMALL_PATCH_REQUEST_TIMEOUT_MILLIS : PATCH_REQUEST_TIMEOUT_MILLIS;
					return nowMillis - entry.getValue() >= timeout;
				})
				.map(Map.Entry::getKey).toList();
		for (MapPatchKey key : expired) {
			inFlightSince.remove(key);
			MapPatchManifest manifest = inFlight.remove(key);
			if (manifest != null) {
				retries++;
				scheduleRetry(manifest, nowMillis);
				XaeroMapsync_r.LOGGER.warn("map_sync event=patch_timeout patch_id={} attempts={} queue={}",
						key.stableId(), retryAttempts.getOrDefault(key, 0), requestQueue.size());
			}
		}
	}

	private void scheduleRetry(MapPatchManifest manifest, long nowMillis) {
		int attempts = retryAttempts.merge(manifest.key(), 1, Integer::sum);
		if (attempts > MAX_PATCH_RETRIES) {
			rejectedPatches++;
			retryAttempts.remove(manifest.key());
			retryNotBefore.remove(manifest.key());
			expectedWaveHashes.remove(manifest.key());
			verifiedWavePatches.remove(manifest.key());
			resyncRequested = true;
			XaeroMapsync_r.LOGGER.error(
					"map_sync event=patch_retry_exhausted patch_id={} epoch={} attempts={} action=restart_manifest",
					manifest.key().stableId(), Long.toUnsignedString(manifest.epoch()), attempts);
			return;
		}
		long delay = Math.min(5_000L, 250L << Math.min(attempts - 1, 4));
		retryNotBefore.put(manifest.key(), nowMillis + delay);
		enqueueLast(manifest);
	}

	private void enqueueFirst(MapPatchManifest manifest) {
		if (queuedKeys.add(manifest.key())) requestQueue.addFirst(manifest);
	}

	private void enqueueLast(MapPatchManifest manifest) {
		if (queuedKeys.add(manifest.key())) requestQueue.addLast(manifest);
	}

	private void markApplied(MapPatch patch) {
		for (int index = 0; index < patch.tiles().size(); index++) {
			MapTile tile = patch.tiles().get(index);
			MapPatchManifest.TileReference reference = patch.manifest().tiles().stream()
					.filter(item -> item.chunkX() == tile.chunkX() && item.chunkZ() == tile.chunkZ()).findFirst().orElseThrow();
			cache.cache(tile, reference.revision());
			cache.markApplied(tile, reference.revision());
		}
		appliedHashes.put(patch.manifest().key(), patch.manifest().contentHash());
		retryAttempts.remove(patch.manifest().key());
		retryNotBefore.remove(patch.manifest().key());
		appliedPatches++;
		lastProgressMillis = System.currentTimeMillis();
	}

	private void logTransition(AtomicPatchCoordinator.Transition transition) {
		if (transition.next() == AtomicPatchCoordinator.Phase.FAILED) {
			rejectedPatches++;
			resyncRequested = true;
		}
		if ("local-generation-timeout-local-authoritative".equals(transition.reason())) {
			XaeroMapsync_r.LOGGER.info(
					"map_sync event=local_generation_timeout patch_id={} region={} {} epoch={} patch_hash={} action=complete_remote_subset_keep_local_xaero",
					transition.key().stableId(), transition.key().xaeroRegionX(), transition.key().xaeroRegionZ(),
					Long.toUnsignedString(transition.epoch()), Long.toUnsignedString(transition.patchHash()));
		}
		XaeroMapsync_r.LOGGER.debug(
				"map_sync event=phase patch_id={} region={} {} epoch={} patch_hash={} phase={} next_phase={} attempts={} reason={} queue={} active_regions={}",
				transition.key().stableId(), transition.key().xaeroRegionX(), transition.key().xaeroRegionZ(),
				Long.toUnsignedString(transition.epoch()), Long.toUnsignedString(transition.patchHash()), transition.previous(), transition.next(),
				transition.attempts(), transition.reason(), coordinator.pendingCount(), coordinator.activeRegionCount());
	}

	private void logSummary() {
		long nowMillis = System.currentTimeMillis();
		AtomicPatchCoordinator.Statistics statistics = coordinator.statistics(nowMillis);
		long queuedSmall = requestQueue.stream()
				.filter(manifest -> manifest.key().sideLength() <= SMALL_PATCH_MAX_SIDE).count();
		long queuedLarge = requestQueue.size() - queuedSmall;
		XaeroMapsync_r.LOGGER.info(
				"map_sync event=summary sync_id={} epoch={} manifest_cursor={} manifest_total={} request_queue={} queued_large={} queued_small={} in_flight={} verified_wave={} commit_queue={} cache_writes={} active_regions={} local_waiting={} oldest_local_wait_ms={} forced_remote_commits={} local_rechecks={} phases={} applied_patches={} rejected_patches={} retries={} tick_p95_ms={} tick_max_ms={} idle_ms={}",
				syncId, Long.toUnsignedString(epoch), manifestCursor, manifestTotal, requestQueue.size(),
				queuedLarge, queuedSmall, inFlight.size(), verifiedWavePatches.size(),
				statistics.pending() + verifiedSmallPatches.size(), cache.pendingWriteCount(), statistics.activeRegions(), statistics.localWaiting(), statistics.oldestLocalWaitMillis(),
				statistics.forcedRemoteCommits(), statistics.localGenerationRechecks(), statistics.phaseCounts(),
				appliedPatches, rejectedPatches, retries,
				String.format(java.util.Locale.ROOT, "%.3f", p95TickMillis()),
				String.format(java.util.Locale.ROOT, "%.3f", maxTickMillis()),
				nowMillis - lastProgressMillis);
	}

	private void recordTickDuration(long duration) {
		tickDurations[durationCursor] = Math.max(0L, duration);
		durationCursor = (durationCursor + 1) % tickDurations.length;
		durationCount = Math.min(durationCount + 1, tickDurations.length);
	}

	private double p95TickMillis() {
		if (durationCount == 0) return 0.0D;
		long[] copy = java.util.Arrays.copyOf(tickDurations, durationCount);
		java.util.Arrays.sort(copy);
		return copy[(int) Math.ceil(copy.length * 0.95D) - 1] / 1_000_000.0D;
	}

	private double maxTickMillis() {
		long max = 0L;
		for (int index = 0; index < durationCount; index++) max = Math.max(max, tickDurations[index]);
		return max / 1_000_000.0D;
	}

	public void resetSession() {
		coordinator.clear();
		requestQueue.clear();
		queuedKeys.clear();
		inFlight.clear();
		inFlightSince.clear();
		retryNotBefore.clear();
		retryAttempts.clear();
		appliedHashes.clear();
		retiredResponses.clear();
		expectedWaveHashes.clear();
		verifiedWavePatches.clear();
		verifiedSmallPatches.clear();
		smallPatchWindowStartedMillis = -1L;
		cache.clearSession();
		gapDetector.clear();
		epoch = 0L;
		manifestCursor = 0;
		manifestTotal = 0;
		previousPositionValid = false;
		resyncRequested = false;
		manifestWaveComplete = false;
	}

	public void stop() { cache.stop(); }
	public int appliedTileCount() { return cache.totalCount(); }
	public int pendingCount() { return requestQueue.size() + inFlight.size() + verifiedWavePatches.size()
			+ verifiedSmallPatches.size() + coordinator.pendingCount(); }
	public String status() {
		return "syncId=" + syncId + " epoch=" + Long.toUnsignedString(epoch) + " manifests=" + manifestCursor + "/"
				+ manifestTotal + " requests=" + requestQueue.size() + " inFlight=" + inFlight.size() + " commits="
				+ coordinator.pendingCount() + " appliedPatches=" + appliedPatches + " p95Ms="
				+ String.format(java.util.Locale.ROOT, "%.3f", p95TickMillis());
	}

	private static long incrementNonZero(long value) { return value == Long.MAX_VALUE ? 1L : value + 1L; }
	static boolean matchesResponse(MapPatchManifest expected, MapPatchManifest actual) {
		return expected != null && actual != null && expected.key().equals(actual.key())
				&& expected.epoch() == actual.epoch() && expected.contentHash() == actual.contentHash();
	}
	static boolean shouldPollManifest(int ticks, int queuedRequests, int requestsInFlight, int verifiedWavePatches,
			int pendingCommits) {
		return ticks % MANIFEST_POLL_TICKS == 0 && queuedRequests == 0 && requestsInFlight == 0
				&& verifiedWavePatches == 0 && pendingCommits == 0;
	}
	static boolean shouldPollGapRecovery(long nowMillis, long nextPollMillis) {
		return nowMillis >= nextPollMillis;
	}
	static boolean shouldDownloadManifest(Long appliedHash, long contentHash, boolean commitPending,
			boolean requestInFlight, boolean requestQueued) {
		return (appliedHash == null || appliedHash.longValue() != contentHash)
				&& !commitPending && !requestInFlight && !requestQueued;
	}
	static boolean canReleaseWave(boolean manifestComplete, int queuedLarge, int inFlightLarge, int expected, int verified) {
		return manifestComplete && queuedLarge == 0 && inFlightLarge == 0 && expected == verified;
	}
	static boolean canStartPatchRequest(int sideLength, int inFlight, boolean largePatchQueued,
			boolean manifestComplete) {
		if (sideLength > SMALL_PATCH_MAX_SIDE) return inFlight < MAX_PATCH_REQUESTS_IN_FLIGHT;
		return manifestComplete && !largePatchQueued && inFlight < MAX_PATCH_REQUESTS_IN_FLIGHT;
	}
	static boolean shouldFlushSmallWave(int count, long startedMillis, long nowMillis) {
		return count >= SMALL_PATCH_COMMIT_BATCH
				|| count > 0 && startedMillis >= 0L && nowMillis - startedMillis >= SMALL_PATCH_COMMIT_WINDOW_MILLIS;
	}
	private static String currentDimension() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level == null ? null : minecraft.level.dimension().location().toString();
	}

	private record RetiredResponseKey(MapPatchKey key, long epoch, long contentHash) {
		private static RetiredResponseKey from(MapPatchManifest manifest) {
			return new RetiredResponseKey(manifest.key(), manifest.epoch(), manifest.contentHash());
		}
	}
}
