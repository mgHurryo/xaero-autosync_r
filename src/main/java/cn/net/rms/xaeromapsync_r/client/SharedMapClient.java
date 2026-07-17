package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.network.MapTileIndexSnapshotPayload;
import cn.net.rms.xaeromapsync_r.network.MapMerkleSnapshotPayload;
import cn.net.rms.xaeromapsync_r.network.MapNodeRequestPayload;
import cn.net.rms.xaeromapsync_r.network.MapNodeResponsePayload;
import cn.net.rms.xaeromapsync_r.map.MerkleNode;
import cn.net.rms.xaeromapsync_r.map.MerkleNodeAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import cn.net.rms.xaeromapsync_r.network.ServerHelloPayload;
import cn.net.rms.xaeromapsync_r.network.SharedMapNetworking;
import cn.net.rms.xaeromapsync_r.network.TileDataPayload;
import cn.net.rms.xaeromapsync_r.network.TileBatchDataPayload;
import cn.net.rms.xaeromapsync_r.network.TileBatchRequestPayload;
import cn.net.rms.xaeromapsync_r.network.TileUnavailablePayload;
import cn.net.rms.xaeromapsync_r.network.WaypointSnapshotPayload;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import cn.net.rms.xaeromapsync_r.xaero.XaeroDetector;
import cn.net.rms.xaeromapsync_r.xaero.ReflectiveXaeroMapAdapter;
import cn.net.rms.xaeromapsync_r.xaero.XaeroMapAdapter;
import cn.net.rms.xaeromapsync_r.xaero.XaeroWaypointAdapter;
import cn.net.rms.xaeromapsync_r.xaero.XaeroWaypointAdapters;
import cn.net.rms.xaeromapsync_r.xaero.XaeroWaypointMutation;
import cn.net.rms.xaeromapsync_r.xaero.XaeroWaypointReconcileResult;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;

public final class SharedMapClient {
	private static final int MAX_LOCAL_HINT_ENTRIES = 4_096;
	private static final int MAX_LOCAL_UPLOAD_HASH_ENTRIES = 16_384;
	private static final int MAX_PENDING_TILE_TARGETS = 8_192;
	private static volatile boolean connectedToSharedMapServer;
	private static final ClientWaypointCache WAYPOINTS = new ClientWaypointCache();
	private static final ClientMapTileIndexCache MAP_TILES = new ClientMapTileIndexCache();
	private static final ClientMapTileCache TILE_DATA = new ClientMapTileCache();
	private static final ClientMerkleCache MERKLE = new ClientMerkleCache();
	private static final ArrayDeque<MapTileIndexEntry> TILE_CACHE_LOOKUP_QUEUE = new ArrayDeque<>();
	private static final ArrayDeque<MapTileIndexEntry> TILE_REQUEST_QUEUE = new ArrayDeque<>();
	private static final Map<String, Long> QUEUED_TILE_REVISIONS = new HashMap<>();
	private static final Map<String, Long> IN_FLIGHT_TILE_REQUESTS = new HashMap<>();
	private static final Map<String, Long> LOCAL_TILE_HINT_TIMES = boundedAccessMap(MAX_LOCAL_HINT_ENTRIES);
	private static final Map<String, Long> LOCAL_TILE_UPLOAD_HASHES = boundedAccessMap(MAX_LOCAL_UPLOAD_HASH_ENTRIES);
	private static final Map<String, Long> LOCAL_TILE_PENDING_HASHES = boundedAccessMap(MAX_LOCAL_UPLOAD_HASH_ENTRIES);
	private static final Map<String, Long> LOCAL_TILE_PENDING_SINCE = boundedAccessMap(MAX_LOCAL_UPLOAD_HASH_ENTRIES);
	private static final Map<String, Long> LOCAL_TILE_UPLOADS_IN_FLIGHT = boundedAccessMap(MAX_LOCAL_UPLOAD_HASH_ENTRIES);
	private static final Set<String> RECOVERY_TILE_UPLOADS_IN_FLIGHT = new HashSet<>();
	private static final ArrayDeque<XaeroMapAdapter.LocalRegion> LOCAL_REGION_SCAN_QUEUE = new ArrayDeque<>();
	private static final ArrayDeque<PendingTileApply> TILE_APPLY_QUEUE = new ArrayDeque<>();
	private static final Set<String> PENDING_TILE_APPLIES = new HashSet<>();
	private static final ArrayDeque<MerkleNodeAddress> MAP_NODE_QUEUE = new ArrayDeque<>();
	private static final Set<Long> PENDING_MAP_REQUEST_IDS = new HashSet<>();
	private static final int MAX_PENDING_TILE_APPLIES = 8192;
	private static final int MAX_CACHE_LOOKUPS_IN_FLIGHT = 256;
	private static final int MAX_MAP_NODE_REQUESTS_IN_FLIGHT = 16;
	private static final int MAX_TILE_APPLIES_PER_TICK = 256;
	private static final long TILE_APPLY_BUDGET_NANOS = 4_000_000L;
	private static final long TILE_APPLY_RETRY_MILLIS = 250L;
	private static final long MAX_TILE_APPLY_RETRY_MILLIS = 5_000L;
	private static final long LOCAL_TILE_HINT_COOLDOWN_MILLIS = 2_000L;
	private static final long LOCAL_TILE_STABILITY_MILLIS = 2_000L;
	private static final int LOCAL_TILE_UPLOAD_RADIUS_MARGIN = 1;
	private static final int MAX_LOCAL_TILE_UPLOADS_PER_SCAN = 64;
	private static final int MAX_LOCAL_TILE_SCANS_PER_TICK = 256;
	private static final int LOCAL_TILE_UPLOAD_SCAN_INTERVAL_TICKS = 1;
	private static final long LOCAL_UPLOAD_TICK_BUDGET_NANOS = 2_000_000L;
	private static final int XAERO_REGION_CHUNKS_PER_SIDE = 32;
	private static final int XAERO_REGION_TILE_COUNT = XAERO_REGION_CHUNKS_PER_SIDE * XAERO_REGION_CHUNKS_PER_SIDE;
	private static final int MAX_ARCHIVE_TILES_PER_TICK = 64;
	private static volatile int tileRequestsInFlight;
	private static final Object CACHE_LOOKUP_LOCK = new Object();
	private static volatile int tileCacheLookupsInFlight;
	private static volatile boolean handlingTileDataBatch;
	private static XaeroWaypointAdapter waypointAdapter;
	private static XaeroMapAdapter mapAdapter;
	private static AtomicMapSyncClient atomicMapSyncClient;
	private static int clientTicks;
	private static int mapRootPollTicks;
	private static volatile int mapNodeRequestsInFlight;
	private static final Map<String, Long> COMPLETED_MAP_ROOTS = new java.util.LinkedHashMap<>();
	private static boolean previousMapSyncEnabled;
	private static boolean previousWaypointsEnabled;
	private static String previousDimension;
	private static String syncingDimension;
	private static long syncingRootHash;
	private static long lastMapProgressMillis;
	private static long retryMapSyncAtMillis;
	private static boolean mapSyncIncomplete;
	private static volatile long mapSessionGeneration;
	private static long activeMapSyncId;
	private static long nextMapSyncId;
	private static long nextMapRequestId;
	private static int localCacheHits;
	private static int localCacheMisses;
	private static boolean waypointSanitizationPending = true;
	private static String localArchiveDimension;
	private static XaeroMapAdapter.LocalRegion activeLocalRegion;
	private static int activeLocalRegionCursor;
	private static int localArchiveRetryTick;
	private static int localArchiveRegionsDiscovered;
	private static int localArchiveRegionsCompleted;
	private static long localArchiveTilesScanned;
	private static long localArchiveTilesUploaded;
	private static long localArchiveTilesMissing;
	private static boolean localArchiveCompleteLogged;
	private static String localLiveScanDimension;
	private static int localLiveScanRadius = -1;
	private static int localLiveScanCursor;

	private SharedMapClient() {
	}

	public static void register() {
		XaeroMapsync_r.LOGGER.info("Xaero availability: {}", XaeroDetector.status().message());
		waypointAdapter = XaeroWaypointAdapters.create();
		mapAdapter = new ReflectiveXaeroMapAdapter();
		atomicMapSyncClient = new AtomicMapSyncClient(mapAdapter,
				FabricLoader.getInstance().getConfigDir().resolve("xaero-mapsync_r-client-patches-v6"));
		previousMapSyncEnabled = SharedMapClientConfig.get().mapSyncEnabled();
		previousWaypointsEnabled = SharedMapClientConfig.get().publicWaypointsEnabled();
		previousDimension = currentDimension();
		XaeroMapsync_r.LOGGER.info(
				"Shared map client state initialized: mapSyncEnabled={} publicWaypointsEnabled={} initialDimension={} waypointAdapterAvailable={} mapAdapterAvailable={}",
				previousMapSyncEnabled, previousWaypointsEnabled, previousDimension,
				waypointAdapter != null && waypointAdapter.isAvailable(),
				mapAdapter != null && mapAdapter.isAvailable());
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			clientTicks++;
			SharedMapNetworking.tickClientTransfers();
			handleConfigChanges();
			processPendingTileApplications();
			long uploadDeadline = System.nanoTime() + LOCAL_UPLOAD_TICK_BUDGET_NANOS;
			if ((clientTicks & 1) == 0) reportNativeLocalTiles(uploadDeadline);
			else scanNativeLocalArchive(uploadDeadline);
			atomicMapSyncClient.tick();
			sanitizeWaypointsIfNeeded();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> atomicMapSyncClient.stop());
	}

	public static void handleServerHello(ServerHelloPayload hello) {
		connectedToSharedMapServer = hello.accepted();
		XaeroMapsync_r.LOGGER.debug(
				"Received shared map server hello accepted={} protocol={} mapFormat={} message={}",
				hello.accepted(), hello.protocolVersion(), hello.mapFormatVersion(), hello.message());
		if (hello.accepted()) {
			atomicMapSyncClient.setConnected(SharedMapClientConfig.get().mapSyncEnabled() && mapSyncAvailable());
			previousDimension = currentDimension();
			XaeroMapsync_r.LOGGER.info("Shared map handshake accepted: {}", hello.message());
			if (SharedMapClientConfig.get().publicWaypointsEnabled()) SharedMapNetworking.requestWaypointSnapshot();
			return;
		}
		atomicMapSyncClient.setConnected(false);
		XaeroMapsync_r.LOGGER.warn("Shared map handshake rejected: {}", hello.message());
	}

	public static void disconnect() {
		XaeroMapsync_r.LOGGER.info(
				"Shared map client disconnected; clearing session state waypointCount={} indexedTiles={} cachedTiles={} pendingTiles={}",
				WAYPOINTS.activeCount(), MAP_TILES.totalCount(), TILE_DATA.totalCount(), pendingTileCount());
		connectedToSharedMapServer = false;
		atomicMapSyncClient.setConnected(false);
		resetLocalArchiveScan();
		clearPendingWaypointMutations();
		WAYPOINTS.replace(java.util.List.of());
		waypointSanitizationPending = true;
	}

	public static void handleWaypointSnapshot(WaypointSnapshotPayload snapshot) {
		WAYPOINTS.replace(snapshot.waypoints());
		if (SharedMapClientConfig.get().publicWaypointsEnabled()) reconcileWaypoints();
		XaeroMapsync_r.LOGGER.info("Received {} public waypoint entries", snapshot.waypoints().size());
	}

	public static void handleWaypointUpsert(PublicWaypoint waypoint) {
		WAYPOINTS.upsert(waypoint);
		if (SharedMapClientConfig.get().publicWaypointsEnabled()) reconcileWaypoints();
	}

	public static void handleWaypointDelete(PublicWaypoint waypoint) {
		WAYPOINTS.delete(waypoint);
		if (SharedMapClientConfig.get().publicWaypointsEnabled()) reconcileWaypoints();
	}

	public static void handleWaypointError(String message) {
		clearPendingWaypointMutations();
		XaeroMapsync_r.LOGGER.warn("Public waypoint sync error: {}", message);
		if (SharedMapClientConfig.get().notificationsEnabled() && net.minecraft.client.Minecraft.getInstance().player != null) {
			net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(new net.minecraft.network.chat.TextComponent(message), false);
		}
	}

	public static void handleMapTileIndexSnapshot(MapTileIndexSnapshotPayload payload) {
		MAP_TILES.replace(payload.rootHash(), payload.entries());
		XaeroMapsync_r.LOGGER.info("Received {} map tile index entries rootHash={}", MAP_TILES.totalCount(), Long.toUnsignedString(MAP_TILES.rootHash()));
		XaeroMapsync_r.LOGGER.debug(
				"Map tile index snapshot details rootHash={} entries={} cachedTiles={} pendingBefore={} currentDimension={}",
				Long.toUnsignedString(payload.rootHash()), payload.entries().size(), TILE_DATA.totalCount(),
				pendingTileCount(), currentDimension());
		enqueueTiles(MAP_TILES.missingFrom(TILE_DATA, Integer.MAX_VALUE));
	}

	public static void handleMerkleSnapshot(MapMerkleSnapshotPayload payload) {
		MERKLE.replace(payload.nodes());
		XaeroMapsync_r.LOGGER.info("Received {} map merkle nodes", MERKLE.totalCount());
	}

	public static void handleTileData(TileDataPayload payload) {
		if (!SharedMapClientConfig.get().mapSyncEnabled()) return;
		if (!payload.tile().dimension().equals(currentDimension())) return;
		String key = tileKey(payload.tile().dimension(), payload.tile().chunkX(), payload.tile().chunkZ());
		if (!payload.tile().hasRenderableSurface()) {
			Long requestedRevision = IN_FLIGHT_TILE_REQUESTS.remove(key);
			if (requestedRevision != null) tileRequestsInFlight = Math.max(0, tileRequestsInFlight - 1);
			removeQueuedRevisionAtMost(key, payload.revision());
			mapSyncIncomplete = true;
			retryMapSyncAtMillis = System.currentTimeMillis() + 5_000L;
			XaeroMapsync_r.LOGGER.warn("Rejected unrenderable received map tile {} {} {} revision={}",
					payload.tile().dimension(), payload.tile().chunkX(), payload.tile().chunkZ(), payload.revision());
			pumpTileRequests();
			return;
		}
		TILE_DATA.cache(payload.tile(), payload.revision());
		Long requestedRevision = IN_FLIGHT_TILE_REQUESTS.remove(key);
		if (requestedRevision != null) tileRequestsInFlight = Math.max(0, tileRequestsInFlight - 1);
		TILE_REQUEST_QUEUE.removeIf(entry -> tileKey(entry.dimension(), entry.chunkX(), entry.chunkZ()).equals(key)
				&& entry.revision() <= payload.revision());
		MAP_TILES.upsert(new MapTileIndexEntry(payload.tile().dimension(), payload.tile().chunkX(), payload.tile().chunkZ(),
				payload.tile().contentHash(), payload.revision(), System.currentTimeMillis()));
		if (isStaleTileResponse(requestedRevision, payload.revision())) {
			MAP_TILES.find(payload.tile().dimension(), payload.tile().chunkX(), payload.tile().chunkZ())
					.filter(entry -> entry.revision() >= requestedRevision)
					.ifPresent(entry -> enqueueTileRequestFirst(key, entry));
			XaeroMapsync_r.LOGGER.debug(
					"Released stale tile response slot and requeued latest revision key={} requestedRevision={} receivedRevision={}",
					key, requestedRevision, payload.revision());
		}
		COMPLETED_MAP_ROOTS.remove(payload.tile().dimension());
		if (mapAdapter == null || !mapAdapter.isAvailable()) {
			resetMapQueues();
			return;
		}
		if (TILE_DATA.hasRevision(payload.tile().dimension(), payload.tile().chunkX(), payload.tile().chunkZ(),
				payload.revision())) {
			long appliedRevision = TILE_DATA.appliedRevision(payload.tile().dimension(), payload.tile().chunkX(),
					payload.tile().chunkZ());
			TILE_APPLY_QUEUE.removeIf(pending -> pending.key.equals(key)
					&& shouldDiscardPendingRevision(pending.revision, appliedRevision));
			boolean stillPending = TILE_APPLY_QUEUE.stream().anyMatch(pending -> pending.key.equals(key));
			if (!stillPending) {
				removeQueuedRevisionAtMost(key, appliedRevision);
				PENDING_TILE_APPLIES.remove(key);
			}
			if (!handlingTileDataBatch) pumpTileRequests();
			return;
		}
		QUEUED_TILE_REVISIONS.merge(key, payload.revision(), Math::max);
		if (!enqueuePendingTileApply(key, payload.tile(), payload.revision())) {
			removeQueuedRevisionAtMost(key, payload.revision());
			mapSyncIncomplete = true;
			retryMapSyncAtMillis = System.currentTimeMillis() + 5_000L;
			return;
		}
		lastMapProgressMillis = System.currentTimeMillis();
		if (!handlingTileDataBatch) pumpTileRequests();
		XaeroMapsync_r.LOGGER.debug("Queued tile data {} {} {} revision={}",
				payload.tile().dimension(),
				payload.tile().chunkX(),
				payload.tile().chunkZ(),
				payload.revision());
	}

	public static void handleTileDataBatch(TileBatchDataPayload payload) {
		XaeroMapsync_r.LOGGER.debug("Received tile data batch size={} pendingBefore={} inFlightBefore={}",
				payload.tiles().size(), TILE_APPLY_QUEUE.size(), tileRequestsInFlight);
		handlingTileDataBatch = true;
		try {
			for (TileDataPayload tile : payload.tiles()) handleTileData(tile);
		} finally {
			handlingTileDataBatch = false;
		}
		pumpTileRequests();
		XaeroMapsync_r.LOGGER.debug("Processed tile data batch size={} pendingAfter={} inFlightAfter={}",
				payload.tiles().size(), TILE_APPLY_QUEUE.size(), tileRequestsInFlight);
	}

	public static void handlePatchManifestPage(cn.net.rms.xaeromapsync_r.network.PatchManifestPagePayload payload) {
		atomicMapSyncClient.handleManifestPage(payload);
	}

	public static void handlePatchData(cn.net.rms.xaeromapsync_r.network.PatchDataPayload payload) {
		atomicMapSyncClient.handlePatchData(payload);
	}

	public static void handlePatchUnavailable(cn.net.rms.xaeromapsync_r.network.PatchUnavailablePayload payload) {
		atomicMapSyncClient.handlePatchUnavailable(payload);
	}

	public static void handleGapRecoveryProbe(TileBatchRequestPayload payload) {
		if (!connectedToSharedMapServer || mapAdapter == null || !mapAdapter.isAvailable()) return;
		String dimension = currentDimension();
		if (dimension == null) return;
		int queued = 0;
		for (cn.net.rms.xaeromapsync_r.network.TileRequestPayload request : payload.requests()) {
			if (!dimension.equals(request.dimension())) continue;
			if (mapAdapter.localTileState(dimension, request.chunkX(), request.chunkZ())
					!= XaeroMapAdapter.LocalTileState.READY) continue;
			Optional<MapTile> local = mapAdapter.localTile(dimension, request.chunkX(), request.chunkZ());
			if (local.isEmpty() || !local.get().hasRenderableSurface()) continue;
			String key = tileKey(dimension, request.chunkX(), request.chunkZ());
			if (!RECOVERY_TILE_UPLOADS_IN_FLIGHT.add(key)) continue;
			MapTile tile = local.get();
			if (!SharedMapNetworking.sendRecoveryTileData(tile, successful -> {
				RECOVERY_TILE_UPLOADS_IN_FLIGHT.remove(key);
				if (!successful) XaeroMapsync_r.LOGGER.debug(
						"map_sync event=gap_recovery_upload_failed dimension={} chunk_x={} chunk_z={}",
						tile.dimension(), tile.chunkX(), tile.chunkZ());
			})) {
				RECOVERY_TILE_UPLOADS_IN_FLIGHT.remove(key);
				continue;
			}
			queued++;
		}
		if (queued > 0) XaeroMapsync_r.LOGGER.info(
				"map_sync event=gap_recovery_peer_response tiles={} priority=high", queued);
	}

	private static boolean enqueuePendingTileApply(String key, MapTile tile, long revision) {
		PendingTileApply existing = null;
		for (PendingTileApply pending : TILE_APPLY_QUEUE) {
			if (pending.key.equals(key)) {
				existing = pending;
				break;
			}
		}
		if (existing != null) {
			if (!shouldReplacePendingRevision(existing.revision, revision)) {
				XaeroMapsync_r.LOGGER.debug("Kept existing pending tile apply {} revision={} incomingRevision={}",
						key, existing.revision, revision);
				return true;
			}
			TILE_APPLY_QUEUE.remove(existing);
			XaeroMapsync_r.LOGGER.debug("Replaced pending tile apply {} oldRevision={} newRevision={}",
					key, existing.revision, revision);
		} else {
			if (TILE_APPLY_QUEUE.size() >= MAX_PENDING_TILE_APPLIES) {
				XaeroMapsync_r.LOGGER.warn("Rejected pending tile apply {} revision={} because queue is full size={} limit={}",
						key, revision, TILE_APPLY_QUEUE.size(), MAX_PENDING_TILE_APPLIES);
				return false;
			}
			PENDING_TILE_APPLIES.add(key);
		}
		TILE_APPLY_QUEUE.addLast(new PendingTileApply(key, tile, revision, 0L, 0));
		XaeroMapsync_r.LOGGER.debug("Queued pending tile apply {} revision={} queueSize={}",
				key, revision, TILE_APPLY_QUEUE.size());
		return true;
	}

	static boolean shouldReplacePendingRevision(long pendingRevision, long incomingRevision) {
		return incomingRevision > pendingRevision;
	}

	static boolean shouldDiscardPendingRevision(long pendingRevision, long appliedRevision) {
		return pendingRevision <= appliedRevision;
	}

	static boolean isStaleTileResponse(Long requestedRevision, long receivedRevision) {
		return requestedRevision != null && receivedRevision < requestedRevision;
	}

	private static void processPendingTileApplications() {
		if (TILE_APPLY_QUEUE.isEmpty() || mapAdapter == null || !mapAdapter.isAvailable()) return;
		long now = System.currentTimeMillis();
		long deadline = System.nanoTime() + TILE_APPLY_BUDGET_NANOS;
		int inspected = TILE_APPLY_QUEUE.size();
		int startingQueueSize = inspected;
		int selected = 0;
		Map<Long, List<PendingTileApply>> remoteBatches = new LinkedHashMap<>();
		while (inspected-- > 0 && selected < MAX_TILE_APPLIES_PER_TICK && !TILE_APPLY_QUEUE.isEmpty()
				&& System.nanoTime() < deadline) {
			PendingTileApply pending = TILE_APPLY_QUEUE.removeFirst();
			if (pending.retryAtMillis > now) {
				TILE_APPLY_QUEUE.addLast(pending);
				continue;
			}
			XaeroMapAdapter.LocalTileState localTileState = mapAdapter.localTileState(pending.tile);
			if (localTileState == XaeroMapAdapter.LocalTileState.READY) {
				XaeroMapsync_r.LOGGER.debug("Pending tile apply resolved by local Xaero tile {} revision={} attempts={}",
						pending.key, pending.revision, pending.attempts);
				completeLocalTile(pending);
				continue;
			}
			if (shouldWaitForLocalTile(localTileState, pending.attempts)) {
				XaeroMapsync_r.LOGGER.debug("Deferring tile apply {} revision={} state={} attempts={} retryInMillis={}",
						pending.key, pending.revision, localTileState, pending.attempts,
						tileApplyRetryMillis(pending.attempts));
				TILE_APPLY_QUEUE.addLast(new PendingTileApply(pending.key, pending.tile, pending.revision,
						now + tileApplyRetryMillis(pending.attempts), pending.attempts + 1));
				continue;
			}
			int regionX = Math.floorDiv(pending.tile.chunkX(), 32);
			int regionZ = Math.floorDiv(pending.tile.chunkZ(), 32);
			long regionKey = (((long) regionX) << 32) ^ (regionZ & 0xffffffffL);
			remoteBatches.computeIfAbsent(regionKey, ignored -> new ArrayList<>()).add(pending);
			selected++;
		}
		var batchIterator = remoteBatches.values().iterator();
		boolean appliedBatch = false;
		while (batchIterator.hasNext()) {
			List<PendingTileApply> remoteBatch = batchIterator.next();
			if (appliedBatch && System.nanoTime() >= deadline) {
				TILE_APPLY_QUEUE.addAll(remoteBatch);
				batchIterator.forEachRemaining(TILE_APPLY_QUEUE::addAll);
				break;
			}
			XaeroMapAdapter.ApplyResult result = mapAdapter.applyBatchResult(remoteBatch.stream()
					.map(PendingTileApply::tile).toList());
			appliedBatch = true;
			if (result == XaeroMapAdapter.ApplyResult.APPLIED) {
				XaeroMapsync_r.LOGGER.debug("Applied remote tile batch size={} remainingQueue={}",
						remoteBatch.size(), TILE_APPLY_QUEUE.size());
				remoteBatch.forEach(SharedMapClient::completeTileApply);
			} else if (result == XaeroMapAdapter.ApplyResult.RETRY_LATER) {
				XaeroMapsync_r.LOGGER.debug("Xaero deferred remote tile batch size={} firstKey={} queueSize={}",
						remoteBatch.size(), remoteBatch.get(0).key, TILE_APPLY_QUEUE.size());
				for (PendingTileApply pending : remoteBatch) {
					TILE_APPLY_QUEUE.addLast(new PendingTileApply(pending.key, pending.tile, pending.revision,
							now + tileApplyRetryMillis(pending.attempts), pending.attempts + 1));
				}
			} else {
				XaeroMapsync_r.LOGGER.warn("Xaero map adapter became unavailable while applying batch size={}; resetting map queues",
						remoteBatch.size());
				resetMapQueues();
				return;
			}
		}
		if (startingQueueSize != TILE_APPLY_QUEUE.size() || selected > 0) {
			XaeroMapsync_r.LOGGER.debug(
					"Tile apply pump completed startingQueue={} selectedRemote={} regionBatches={} endingQueue={} localPending={}",
					startingQueueSize, selected, remoteBatches.size(), TILE_APPLY_QUEUE.size(),
					PENDING_TILE_APPLIES.size());
		}
		pumpTileRequests();
	}

	static long tileApplyRetryMillis(int attempts) {
		int shift = Math.min(Math.max(attempts, 0), 5);
		return Math.min(MAX_TILE_APPLY_RETRY_MILLIS, TILE_APPLY_RETRY_MILLIS << shift);
	}

	static boolean shouldWaitForLocalTile(XaeroMapAdapter.LocalTileState state, int attempts) {
		return state == XaeroMapAdapter.LocalTileState.GENERATING;
	}

	static int localTileUploadRadius(int clientRenderDistance) {
		return Math.max(2, clientRenderDistance + LOCAL_TILE_UPLOAD_RADIUS_MARGIN);
	}

	static int localTileScanCount(int radius) {
		if (radius < 0) throw new IllegalArgumentException("Local tile scan radius must be non-negative");
		int side = Math.addExact(Math.multiplyExact(radius, 2), 1);
		return Math.multiplyExact(side, side);
	}

	/** Packs a center-first square spiral offset as signed dx/dz integer halves. */
	static long localTileScanOffset(int radius, int cursor) {
		int count = localTileScanCount(radius);
		if (cursor < 0 || cursor >= count) throw new IllegalArgumentException("Invalid local tile scan cursor");
		if (cursor == 0) return 0L;
		int ring = 1;
		while (cursor >= localTileScanCount(ring)) ring++;
		int ringIndex = cursor - localTileScanCount(ring - 1);
		int edge = ring * 2;
		int dx;
		int dz;
		if (ringIndex <= edge) {
			dx = -ring + ringIndex;
			dz = -ring;
		} else if ((ringIndex -= edge + 1) < edge) {
			dx = ring;
			dz = -ring + 1 + ringIndex;
		} else if ((ringIndex -= edge) < edge) {
			dx = ring - 1 - ringIndex;
			dz = ring;
		} else {
			ringIndex -= edge;
			dx = -ring;
			dz = ring - 1 - ringIndex;
		}
		return ((long) dx << 32) | (dz & 0xffffffffL);
	}

	private static void completeLocalTile(PendingTileApply pending) {
		PENDING_TILE_APPLIES.remove(pending.key);
		removeQueuedRevisionAtMost(pending.key, pending.revision);
		lastMapProgressMillis = System.currentTimeMillis();
		XaeroMapsync_r.LOGGER.debug("Resolved tile through Xaero native writer {} {} {} revision={}",
				pending.tile.dimension(), pending.tile.chunkX(), pending.tile.chunkZ(), pending.revision);
	}

	private static void reportNativeLocalTiles(long deadline) {
		if (clientTicks % LOCAL_TILE_UPLOAD_SCAN_INTERVAL_TICKS != 0 || !connectedToSharedMapServer || !previousMapSyncEnabled
				|| mapAdapter == null || !mapAdapter.isAvailable()) return;
		if (System.nanoTime() >= deadline) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) return;
		String dimension = minecraft.level.dimension().location().toString();
		net.minecraft.world.level.ChunkPos center = minecraft.player.chunkPosition();
		int sent = 0;
		int scanned = 0;
		int uploadRadius = localTileUploadRadius(minecraft.options.renderDistance);
		if (!dimension.equals(localLiveScanDimension) || uploadRadius != localLiveScanRadius) {
			localLiveScanDimension = dimension;
			localLiveScanRadius = uploadRadius;
			localLiveScanCursor = 0;
		}
		int scanCount = localTileScanCount(uploadRadius);
		while (scanned < MAX_LOCAL_TILE_SCANS_PER_TICK && sent < MAX_LOCAL_TILE_UPLOADS_PER_SCAN
				&& System.nanoTime() < deadline) {
			long offset = localTileScanOffset(uploadRadius, localLiveScanCursor);
			localLiveScanCursor = (localLiveScanCursor + 1) % scanCount;
			scanned++;
			int chunkX = center.x + (int) (offset >> 32);
			int chunkZ = center.z + (int) offset;
			if (mapAdapter.localTileState(dimension, chunkX, chunkZ) != XaeroMapAdapter.LocalTileState.READY) continue;
			if (reportLocalTile(dimension, chunkX, chunkZ)) sent++;
		}
		if (sent > 0) {
			XaeroMapsync_r.LOGGER.debug(
					"Reported local Xaero tiles dimension={} centerChunk={} {} radius={} scanned={} queued={} nextCursor={} hintCacheSize={}",
					dimension, center.x, center.z, uploadRadius, scanned, sent,
					localLiveScanCursor, LOCAL_TILE_HINT_TIMES.size());
		}
	}

	private static boolean reportLocalTile(String dimension, int chunkX, int chunkZ) {
		String key = tileKey(dimension, chunkX, chunkZ);
		Optional<MapTile> local = mapAdapter.localTile(dimension, chunkX, chunkZ);
		if (local.isEmpty()) {
			XaeroMapsync_r.LOGGER.debug("Local Xaero tile ready without extractable body {} {} {}; sending hint",
					dimension, chunkX, chunkZ);
			reportLocalTileReady(dimension, chunkX, chunkZ, 0L);
			return false;
		}
		MapTile tile = local.get();
		return uploadLocalTile(tile, true) == LocalUploadResult.QUEUED;
	}

	private static LocalUploadResult uploadLocalTile(MapTile tile, boolean requireStability) {
		String key = tileKey(tile.dimension(), tile.chunkX(), tile.chunkZ());
		long now = System.currentTimeMillis();
		Long previousHash = LOCAL_TILE_UPLOAD_HASHES.get(key);
		if (previousHash != null && previousHash.longValue() == tile.contentHash()) return LocalUploadResult.UNCHANGED;
		if (requireStability) {
			Long pendingHash = LOCAL_TILE_PENDING_HASHES.get(key);
			if (pendingHash == null || pendingHash.longValue() != tile.contentHash()) {
				LOCAL_TILE_PENDING_HASHES.put(key, tile.contentHash());
				LOCAL_TILE_PENDING_SINCE.put(key, now);
				return LocalUploadResult.PENDING;
			}
			if (now - LOCAL_TILE_PENDING_SINCE.getOrDefault(key, now) < LOCAL_TILE_STABILITY_MILLIS)
				return LocalUploadResult.PENDING;
		}
		Long uploadInFlight = LOCAL_TILE_UPLOADS_IN_FLIGHT.get(key);
		if (uploadInFlight != null && uploadInFlight.longValue() == tile.contentHash()) return LocalUploadResult.PENDING;
		LOCAL_TILE_UPLOADS_IN_FLIGHT.put(key, tile.contentHash());
		if (!SharedMapNetworking.sendLocalTileData(tile,
				successful -> completeLocalTileUpload(key, tile, successful))) {
			LOCAL_TILE_UPLOADS_IN_FLIGHT.remove(key);
			return LocalUploadResult.DEFERRED;
		}
		XaeroMapsync_r.LOGGER.debug("Queued local Xaero tile upload {} {} {} hash={}",
				tile.dimension(), tile.chunkX(), tile.chunkZ(), Long.toUnsignedString(tile.contentHash()));
		return LocalUploadResult.QUEUED;
	}

	private static void completeLocalTileUpload(String key, MapTile tile, boolean successful) {
		Long inFlightHash = LOCAL_TILE_UPLOADS_IN_FLIGHT.get(key);
		if (!shouldCommitLocalTileUpload(inFlightHash, tile.contentHash(), successful)) {
			if (inFlightHash != null && inFlightHash.longValue() == tile.contentHash()) {
				LOCAL_TILE_UPLOADS_IN_FLIGHT.remove(key);
				XaeroMapsync_r.LOGGER.warn("map_sync event=client_tile_upload_failed dimension={} chunk_x={} chunk_z={} hash={}",
						tile.dimension(), tile.chunkX(), tile.chunkZ(), Long.toUnsignedString(tile.contentHash()));
			}
			return;
		}
		LOCAL_TILE_UPLOADS_IN_FLIGHT.remove(key);
		LOCAL_TILE_UPLOAD_HASHES.put(key, tile.contentHash());
		LOCAL_TILE_HINT_TIMES.put(key, System.currentTimeMillis());
		LOCAL_TILE_PENDING_HASHES.remove(key);
		LOCAL_TILE_PENDING_SINCE.remove(key);
	}

	static boolean shouldCommitLocalTileUpload(Long inFlightHash, long completedHash, boolean successful) {
		return successful && inFlightHash != null && inFlightHash.longValue() == completedHash;
	}

	private static void scanNativeLocalArchive(long deadline) {
		if (!connectedToSharedMapServer || !previousMapSyncEnabled || mapAdapter == null || !mapAdapter.isAvailable()) return;
		if (System.nanoTime() >= deadline) return;
		String dimension = currentDimension();
		if (dimension == null) return;
		if (!dimension.equals(localArchiveDimension)) initializeLocalArchiveScan(dimension);
		if (LOCAL_REGION_SCAN_QUEUE.isEmpty() && activeLocalRegion == null) {
			if (localArchiveRegionsDiscovered == 0 && clientTicks >= localArchiveRetryTick) {
				initializeLocalArchiveScan(dimension);
				return;
			}
			if (!localArchiveCompleteLogged && localArchiveRegionsDiscovered > 0) {
				localArchiveCompleteLogged = true;
				XaeroMapsync_r.LOGGER.info(
						"map_sync event=client_archive_scan_complete dimension={} regions={} scanned={} uploaded={} missing={}",
						dimension, localArchiveRegionsCompleted, localArchiveTilesScanned,
						localArchiveTilesUploaded, localArchiveTilesMissing);
			}
			return;
		}
		if (activeLocalRegion == null) {
			activeLocalRegion = LOCAL_REGION_SCAN_QUEUE.removeFirst();
			activeLocalRegionCursor = 0;
		}
		if (!mapAdapter.prepareLocalRegion(dimension, activeLocalRegion)) return;

		int processed = 0;
		while (activeLocalRegionCursor < XAERO_REGION_TILE_COUNT && processed < MAX_ARCHIVE_TILES_PER_TICK
				&& System.nanoTime() < deadline) {
			int offset = activeLocalRegionCursor++;
			int chunkX = archiveChunkX(activeLocalRegion, offset);
			int chunkZ = archiveChunkZ(activeLocalRegion, offset);
			Optional<MapTile> local = mapAdapter.localTile(dimension, chunkX, chunkZ);
			localArchiveTilesScanned++;
			if (local.isPresent()) {
				LocalUploadResult result = uploadLocalTile(local.get(), false);
				if (result == LocalUploadResult.QUEUED) localArchiveTilesUploaded++;
				if (result == LocalUploadResult.DEFERRED) {
					// Compression backpressure is temporary. Keep this exact archive tile
					// at the head instead of silently punching a permanent hole.
					activeLocalRegionCursor--;
					localArchiveTilesScanned--;
					break;
				}
			} else {
				localArchiveTilesMissing++;
			}
			processed++;
		}
		if (activeLocalRegionCursor >= XAERO_REGION_TILE_COUNT) {
			localArchiveRegionsCompleted++;
			XaeroMapsync_r.LOGGER.info(
					"map_sync event=client_archive_region_scanned dimension={} region_x={} region_z={} completed={}/{} scanned={} uploaded={} missing={}",
					dimension, activeLocalRegion.regionX(), activeLocalRegion.regionZ(),
					localArchiveRegionsCompleted, localArchiveRegionsDiscovered, localArchiveTilesScanned,
					localArchiveTilesUploaded, localArchiveTilesMissing);
			activeLocalRegion = null;
			activeLocalRegionCursor = 0;
		}
	}

	private enum LocalUploadResult { QUEUED, UNCHANGED, PENDING, DEFERRED }

	private static void initializeLocalArchiveScan(String dimension) {
		resetLocalArchiveScan();
		localArchiveDimension = dimension;
		List<XaeroMapAdapter.LocalRegion> regions = mapAdapter.knownLocalRegions(dimension);
		LOCAL_REGION_SCAN_QUEUE.addAll(regions);
		localArchiveRegionsDiscovered = regions.size();
		localArchiveRetryTick = clientTicks + 100;
		if (!regions.isEmpty()) {
			XaeroMapsync_r.LOGGER.info("map_sync event=client_archive_scan_started dimension={} regions={}",
					dimension, regions.size());
		} else {
			XaeroMapsync_r.LOGGER.debug(
					"map_sync event=client_archive_scan_waiting dimension={} reason=no_detected_regions retry_tick={}",
					dimension, localArchiveRetryTick);
		}
	}

	private static void resetLocalArchiveScan() {
		LOCAL_REGION_SCAN_QUEUE.clear();
		localArchiveDimension = null;
		activeLocalRegion = null;
		activeLocalRegionCursor = 0;
		localArchiveRetryTick = 0;
		localArchiveRegionsDiscovered = 0;
		localArchiveRegionsCompleted = 0;
		localArchiveTilesScanned = 0L;
		localArchiveTilesUploaded = 0L;
		localArchiveTilesMissing = 0L;
		localArchiveCompleteLogged = false;
	}

	static int archiveChunkX(XaeroMapAdapter.LocalRegion region, int offset) {
		if (region == null || offset < 0 || offset >= XAERO_REGION_TILE_COUNT)
			throw new IllegalArgumentException("Invalid Xaero archive region offset");
		return region.regionX() * XAERO_REGION_CHUNKS_PER_SIDE + offset % XAERO_REGION_CHUNKS_PER_SIDE;
	}

	static int archiveChunkZ(XaeroMapAdapter.LocalRegion region, int offset) {
		if (region == null || offset < 0 || offset >= XAERO_REGION_TILE_COUNT)
			throw new IllegalArgumentException("Invalid Xaero archive region offset");
		return region.regionZ() * XAERO_REGION_CHUNKS_PER_SIDE + offset / XAERO_REGION_CHUNKS_PER_SIDE;
	}

	private static void reportLocalTileReady(String dimension, int chunkX, int chunkZ, long contentHash) {
		String key = tileKey(dimension, chunkX, chunkZ);
		long now = System.currentTimeMillis();
		long previous = LOCAL_TILE_HINT_TIMES.getOrDefault(key, 0L);
		if (now - previous < LOCAL_TILE_HINT_COOLDOWN_MILLIS) return;
		LOCAL_TILE_HINT_TIMES.put(key, now);
		SharedMapNetworking.sendLocalTileReady(dimension, chunkX, chunkZ, contentHash);
		XaeroMapsync_r.LOGGER.debug("Sent local Xaero tile ready hint {} {} {} hash={}",
				dimension, chunkX, chunkZ, Long.toUnsignedString(contentHash));
	}

	private static void completeTileApply(PendingTileApply pending) {
		TILE_DATA.markApplied(pending.tile, pending.revision);
		PENDING_TILE_APPLIES.remove(pending.key);
		removeQueuedRevisionAtMost(pending.key, pending.revision);
		lastMapProgressMillis = System.currentTimeMillis();
		XaeroMapsync_r.LOGGER.debug("Applied tile data {} {} {} revision={} attempts={}",
				pending.tile.dimension(), pending.tile.chunkX(), pending.tile.chunkZ(), pending.revision,
				pending.attempts + 1);
	}

	public static void handleTileUnavailable(TileUnavailablePayload payload) {
		String key = tileKey(payload.dimension(), payload.chunkX(), payload.chunkZ());
		Long requestedRevision = IN_FLIGHT_TILE_REQUESTS.remove(key);
		if (requestedRevision != null) tileRequestsInFlight = Math.max(0, tileRequestsInFlight - 1);
		if (PENDING_TILE_APPLIES.contains(key)) {
			pumpTileRequests();
			return;
		}
		if (requestedRevision == null || !removeQueuedRevisionAtMost(key, requestedRevision)) {
			return;
		}
		mapSyncIncomplete = true;
		retryMapSyncAtMillis = System.currentTimeMillis() + 5_000L;
		lastMapProgressMillis = System.currentTimeMillis();
		XaeroMapsync_r.LOGGER.debug("Map tile unavailable {} {} {}: {}",
				payload.dimension(), payload.chunkX(), payload.chunkZ(), payload.reason());
		pumpTileRequests();
	}

	public static void handleMapNodeResponse(MapNodeResponsePayload payload) {
		if (!SharedMapClientConfig.get().mapSyncEnabled()) return;
		if (!payload.dimension().equals(currentDimension())) return;
		if (!isExpectedMapResponse(activeMapSyncId, PENDING_MAP_REQUEST_IDS, payload.syncId(), payload.requestId())) {
			XaeroMapsync_r.LOGGER.debug(
					"Ignored stale map node response dimension={} syncId={} requestId={} activeSyncId={} pendingRequests={}",
					payload.dimension(), payload.syncId(), payload.requestId(), activeMapSyncId,
					PENDING_MAP_REQUEST_IDS.size());
			return;
		}
		PENDING_MAP_REQUEST_IDS.remove(payload.requestId());
		mapNodeRequestsInFlight = PENDING_MAP_REQUEST_IDS.size();
		if (!payload.nodeRequestResponse()) {
			syncingDimension = payload.dimension();
			syncingRootHash = payload.rootHash();
		} else if (syncingDimension == null || !syncingDimension.equals(payload.dimension())
				|| syncingRootHash != payload.rootHash()) {
			resetMapQueues();
			retryMapSyncAtMillis = System.currentTimeMillis() + 250L;
			XaeroMapsync_r.LOGGER.warn(
					"Map node response root changed mid-sync; dimension={} payloadRoot={} syncingRoot={} retryAt={}",
					payload.dimension(), Long.toUnsignedString(payload.rootHash()),
					Long.toUnsignedString(syncingRootHash), retryMapSyncAtMillis);
			return;
		}
		lastMapProgressMillis = System.currentTimeMillis();
		MAP_TILES.setRootHash(syncingRootHash);
		Map<String, MapTileIndexEntry> leafEntries = payload.entries().stream().collect(Collectors.toMap(
				entry -> tileKey(entry.dimension(), entry.chunkX(), entry.chunkZ()), Function.identity(), (left, right) -> right));
		List<MerkleNodeAddress> changedBranches = new ArrayList<>();
		List<MapTileIndexEntry> changedLeaves = new ArrayList<>();
		for (MerkleNode node : payload.nodes()) {
			boolean changed = MERKLE.find(node).map(previous -> previous.hash() != node.hash()).orElse(true);
			MERKLE.put(node);
			if (!shouldInspectMerkleNode(node.level(), changed)) {
				continue;
			}
			if (node.level() > 0) {
				changedBranches.add(MerkleNodeAddress.of(node));
				continue;
			}
			MapTileIndexEntry entry = leafEntries.get(tileKey(node.dimension(), node.nodeX(), node.nodeZ()));
			if (entry != null) {
				MAP_TILES.upsert(entry);
				if (!TILE_DATA.hasRevision(entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision())) {
					changedLeaves.add(entry);
				}
			}
		}
		XaeroMapsync_r.LOGGER.debug(
				"Processed map node response dimension={} syncId={} requestId={} rootHash={} nodes={} entries={} changedBranches={} changedLeaves={} pendingNodeQueue={}",
				payload.dimension(), payload.syncId(), payload.requestId(),
				Long.toUnsignedString(payload.rootHash()), payload.nodes().size(), payload.entries().size(),
				changedBranches.size(), changedLeaves.size(), MAP_NODE_QUEUE.size());
		MAP_NODE_QUEUE.addAll(changedBranches);
		pumpMapNodeRequests();
		enqueueTiles(changedLeaves);
		markRootCompleteIfIdle();
	}

	static boolean isExpectedMapResponse(long activeSyncId, Set<Long> pendingRequestIds,
			long responseSyncId, long responseRequestId) {
		return activeSyncId != 0L && responseSyncId == activeSyncId && pendingRequestIds.contains(responseRequestId);
	}

	static boolean shouldInspectMerkleNode(int level, boolean changed) {
		// Unchanged leaves can still be missing locally after Xaero deferred an earlier injection.
		return level == 0 || changed;
	}

	private static void enqueueTiles(Iterable<MapTileIndexEntry> entries) {
		int inspected = 0;
		int queued = 0;
		int deferred = 0;
		for (MapTileIndexEntry entry : entries) {
			inspected++;
			String key = tileKey(entry.dimension(), entry.chunkX(), entry.chunkZ());
			if (TILE_DATA.hasRevision(entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision())) continue;
			Long targetRevision = QUEUED_TILE_REVISIONS.get(key);
			if (targetRevision == null || entry.revision() > targetRevision) {
				if (targetRevision == null && !canTrackTileTarget(QUEUED_TILE_REVISIONS.size(), MAX_PENDING_TILE_TARGETS)) {
					mapSyncIncomplete = true;
					retryMapSyncAtMillis = System.currentTimeMillis() + 5_000L;
					deferred++;
					continue;
				}
				QUEUED_TILE_REVISIONS.put(key, entry.revision());
				TILE_CACHE_LOOKUP_QUEUE.add(entry);
				queued++;
			}
		}
		if (inspected > 0) {
			XaeroMapsync_r.LOGGER.debug(
					"Enqueued tile cache lookups inspected={} queued={} deferred={} lookupQueue={} requestQueue={} targetRevisions={}",
					inspected, queued, deferred, TILE_CACHE_LOOKUP_QUEUE.size(), TILE_REQUEST_QUEUE.size(),
					QUEUED_TILE_REVISIONS.size());
		}
		pumpTileCacheLookups();
		pumpTileRequests();
	}

	private static void pumpTileCacheLookups() {
		while (cacheLookupsInFlight() < MAX_CACHE_LOOKUPS_IN_FLIGHT && !TILE_CACHE_LOOKUP_QUEUE.isEmpty()) {
			MapTileIndexEntry entry = TILE_CACHE_LOOKUP_QUEUE.remove();
			long generation = reserveCacheLookup();
			int inFlight = cacheLookupsInFlight();
			XaeroMapsync_r.LOGGER.debug("Started tile cache lookup {} {} {} revision={} generation={} inFlight={} remainingQueue={}",
					entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision(), generation,
					inFlight, TILE_CACHE_LOOKUP_QUEUE.size());
			TILE_DATA.findCached(entry).whenComplete((cached, exception) -> {
				try {
					Minecraft.getInstance().execute(() -> handleCachedTile(entry, generation, cached, exception));
				} catch (RejectedExecutionException rejected) {
					releaseCacheLookup(generation);
					XaeroMapsync_r.LOGGER.debug("Discarded cache lookup completion after client executor shutdown generation={}",
							generation);
				}
			});
		}
	}

	private static void handleCachedTile(MapTileIndexEntry entry, long generation, Optional<MapTile> cached,
			Throwable exception) {
		if (!releaseCacheLookup(generation)) return;
		if (exception != null) {
			XaeroMapsync_r.LOGGER.warn("Failed client tile cache lookup {} {} {} revision={}; requesting from server",
					entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision(), exception);
			cached = Optional.empty();
		}
		String key = tileKey(entry.dimension(), entry.chunkX(), entry.chunkZ());
		Long targetRevision = QUEUED_TILE_REVISIONS.get(key);
		if (targetRevision == null || targetRevision.longValue() != entry.revision()) {
			XaeroMapsync_r.LOGGER.debug("Discarded stale cache lookup result {} revision={} targetRevision={} generation={}",
					key, entry.revision(), targetRevision, generation);
			pumpTileCacheLookups();
			markRootCompleteIfIdle();
			return;
		}
		if (TILE_DATA.hasRevision(entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision())) {
			removeQueuedRevisionAtMost(key, entry.revision());
			XaeroMapsync_r.LOGGER.debug("Tile cache lookup became unnecessary {} revision={} alreadyApplied=true",
					key, entry.revision());
		} else if (cached.isPresent()) {
			localCacheHits++;
			XaeroMapsync_r.LOGGER.debug("Tile cache lookup hit {} revision={} localCacheHits={} pendingApplyQueue={}",
					key, entry.revision(), localCacheHits, TILE_APPLY_QUEUE.size());
			if (!enqueuePendingTileApply(key, cached.get(), entry.revision())) {
				removeQueuedRevisionAtMost(key, entry.revision());
				mapSyncIncomplete = true;
				retryMapSyncAtMillis = System.currentTimeMillis() + 5_000L;
			}
		} else {
			localCacheMisses++;
			TILE_REQUEST_QUEUE.add(entry);
			XaeroMapsync_r.LOGGER.debug("Tile cache lookup miss {} revision={} localCacheMisses={} requestQueue={}",
					key, entry.revision(), localCacheMisses, TILE_REQUEST_QUEUE.size());
		}
		lastMapProgressMillis = System.currentTimeMillis();
		pumpTileCacheLookups();
		pumpTileRequests();
		markRootCompleteIfIdle();
	}

	private static void pumpTileRequests() {
		int limit = Math.min(SharedMapConfig.maxTileRequestsPerSnapshot(), MAX_PENDING_TILE_TARGETS);
		while (canRequestTile(tileRequestsInFlight, TILE_APPLY_QUEUE.size(), limit, MAX_PENDING_TILE_APPLIES)
				&& !TILE_REQUEST_QUEUE.isEmpty()) {
			List<MapTileIndexEntry> batch = new ArrayList<>(TileBatchRequestPayload.MAX_REQUESTS);
			while (canRequestTile(tileRequestsInFlight, TILE_APPLY_QUEUE.size(), limit, MAX_PENDING_TILE_APPLIES)
					&& !TILE_REQUEST_QUEUE.isEmpty() && batch.size() < TileBatchRequestPayload.MAX_REQUESTS) {
				MapTileIndexEntry entry = TILE_REQUEST_QUEUE.remove();
				String key = tileKey(entry.dimension(), entry.chunkX(), entry.chunkZ());
				Long targetRevision = QUEUED_TILE_REVISIONS.get(key);
				if (targetRevision == null || targetRevision != entry.revision()) continue;
				Long requestedRevision = IN_FLIGHT_TILE_REQUESTS.get(key);
				if (requestedRevision != null) continue;
				batch.add(entry);
				IN_FLIGHT_TILE_REQUESTS.put(key, entry.revision());
				tileRequestsInFlight++;
				lastMapProgressMillis = System.currentTimeMillis();
			}
			if (!batch.isEmpty()) SharedMapNetworking.requestTiles(batch);
			if (!batch.isEmpty()) {
				XaeroMapsync_r.LOGGER.debug("Requested tile batch size={} inFlight={} requestQueue={} applyQueue={} targetRevisions={}",
						batch.size(), tileRequestsInFlight, TILE_REQUEST_QUEUE.size(), TILE_APPLY_QUEUE.size(),
						QUEUED_TILE_REVISIONS.size());
			}
		}
		markRootCompleteIfIdle();
	}

	static boolean canRequestTile(int inFlight, int pendingApplies, int requestLimit, int maxPendingApplies) {
		return inFlight < requestLimit && inFlight + pendingApplies < maxPendingApplies;
	}

	static boolean releaseCacheLookup(long generation) {
		synchronized (CACHE_LOOKUP_LOCK) {
			if (generation != mapSessionGeneration) return false;
			tileCacheLookupsInFlight = cacheLookupCountAfterCompletion(
					tileCacheLookupsInFlight, generation, mapSessionGeneration);
			return true;
		}
	}

	static int cacheLookupCountAfterCompletion(int currentCount, long completionGeneration, long activeGeneration) {
		if (completionGeneration != activeGeneration) return currentCount;
		return Math.max(0, currentCount - 1);
	}

	private static long reserveCacheLookup() {
		synchronized (CACHE_LOOKUP_LOCK) {
			tileCacheLookupsInFlight++;
			return mapSessionGeneration;
		}
	}

	private static int cacheLookupsInFlight() {
		synchronized (CACHE_LOOKUP_LOCK) {
			return tileCacheLookupsInFlight;
		}
	}

	private static void advanceCacheLookupGeneration() {
		synchronized (CACHE_LOOKUP_LOCK) {
			mapSessionGeneration++;
			tileCacheLookupsInFlight = 0;
		}
	}

	static boolean canTrackTileTarget(int trackedTargets, int limit) {
		return trackedTargets < limit;
	}

	private static void enqueueTileRequestFirst(String key, MapTileIndexEntry entry) {
		boolean alreadyQueued = TILE_REQUEST_QUEUE.stream().anyMatch(candidate ->
				tileKey(candidate.dimension(), candidate.chunkX(), candidate.chunkZ()).equals(key)
						&& candidate.revision() >= entry.revision());
		if (!alreadyQueued) TILE_REQUEST_QUEUE.addFirst(entry);
	}

	private static void pumpMapNodeRequests() {
		while (mapNodeRequestsInFlight < MAX_MAP_NODE_REQUESTS_IN_FLIGHT && !MAP_NODE_QUEUE.isEmpty()) {
			List<MerkleNodeAddress> batch = new ArrayList<>(MapNodeRequestPayload.MAX_REQUESTS);
			while (batch.size() < MapNodeRequestPayload.MAX_REQUESTS && !MAP_NODE_QUEUE.isEmpty()) {
				batch.add(MAP_NODE_QUEUE.remove());
			}
			long requestId = nextMapRequestId();
			PENDING_MAP_REQUEST_IDS.add(requestId);
			mapNodeRequestsInFlight = PENDING_MAP_REQUEST_IDS.size();
			SharedMapNetworking.requestMapNodes(activeMapSyncId, requestId, batch);
			XaeroMapsync_r.LOGGER.debug("Requested map node batch syncId={} requestId={} size={} inFlight={} queuedNodes={}",
					activeMapSyncId, requestId, batch.size(), mapNodeRequestsInFlight, MAP_NODE_QUEUE.size());
			lastMapProgressMillis = System.currentTimeMillis();
		}
	}

	private static void requestMapRoot() {
		if (!connectedToSharedMapServer || !mapSyncAvailable() || currentDimension() == null) {
			XaeroMapsync_r.LOGGER.debug(
					"Skipped map root request connected={} mapSyncAvailable={} dimension={}",
					connectedToSharedMapServer, mapSyncAvailable(), currentDimension());
			return;
		}
		activeMapSyncId = incrementNonZero(nextMapSyncId);
		nextMapSyncId = activeMapSyncId;
		PENDING_MAP_REQUEST_IDS.clear();
		long requestId = nextMapRequestId();
		PENDING_MAP_REQUEST_IDS.add(requestId);
		mapNodeRequestsInFlight = 1;
		lastMapProgressMillis = System.currentTimeMillis();
		XaeroMapsync_r.LOGGER.debug("Requesting map root dimension={} syncId={} requestId={} knownRoot={}",
				currentDimension(), activeMapSyncId, requestId,
				Long.toUnsignedString(knownMapRootHash(currentDimension())));
		SharedMapNetworking.requestMapSync(activeMapSyncId, requestId);
	}

	private static long nextMapRequestId() {
		nextMapRequestId = incrementNonZero(nextMapRequestId);
		return nextMapRequestId;
	}

	private static long incrementNonZero(long value) {
		long next = value + 1L;
		return next == 0L ? 1L : next;
	}

	static <K, V> Map<K, V> boundedAccessMap(int maximumSize) {
		return new LinkedHashMap<>(Math.min(maximumSize, 256), 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > maximumSize;
			}
		};
	}

	private static String tileKey(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + chunkX + ":" + chunkZ;
	}

	private static boolean removeQueuedRevisionAtMost(String key, long revision) {
		Long targetRevision = QUEUED_TILE_REVISIONS.get(key);
		if (targetRevision == null || targetRevision > revision) return false;
		QUEUED_TILE_REVISIONS.remove(key);
		return true;
	}

	public static long knownMapRootHash(String dimension) {
		return COMPLETED_MAP_ROOTS.getOrDefault(dimension, 0L);
	}

	public static boolean connectedToSharedMapServer() {
		return connectedToSharedMapServer;
	}

	public static boolean mapSyncAvailable() {
		return mapAdapter != null && mapAdapter.isAvailable();
	}

	private static void reconcileWaypoints() {
		if (waypointAdapter != null && waypointAdapter.isAvailable()) waypointAdapter.reconcile(WAYPOINTS.snapshot());
	}

	private static void sanitizeWaypointsIfNeeded() {
		if (!waypointSanitizationPending || waypointAdapter == null || !waypointAdapter.isAvailable()) {
			return;
		}
		XaeroWaypointReconcileResult result = waypointAdapter.reconcile(WAYPOINTS.snapshot());
		if (result.outcome() == XaeroWaypointReconcileResult.Outcome.APPLIED
				|| result.outcome() == XaeroWaypointReconcileResult.Outcome.NO_CHANGES) {
			waypointSanitizationPending = false;
		}
	}

	private static void handleConfigChanges() {
		boolean mapEnabled = SharedMapClientConfig.get().mapSyncEnabled() && mapSyncAvailable();
		boolean waypointsEnabled = SharedMapClientConfig.get().publicWaypointsEnabled();
		String dimension = currentDimension();
		if (mapEnabled && connectedToSharedMapServer && dimension != null && !dimension.equals(previousDimension)) {
			XaeroMapsync_r.LOGGER.info("Shared map dimension changed: {} -> {}; restarting map sync",
					previousDimension, dimension);
			previousDimension = dimension;
			resetLocalArchiveScan();
			atomicMapSyncClient.setConnected(true);
		}
		if (mapEnabled != previousMapSyncEnabled) {
			XaeroMapsync_r.LOGGER.info("Shared map client map sync config changed: {} -> {} connected={} available={}",
					previousMapSyncEnabled, mapEnabled, connectedToSharedMapServer, mapSyncAvailable());
			previousMapSyncEnabled = mapEnabled;
			atomicMapSyncClient.setConnected(mapEnabled && connectedToSharedMapServer);
		}
		if (waypointsEnabled != previousWaypointsEnabled) {
			XaeroMapsync_r.LOGGER.info("Shared map public waypoint config changed: {} -> {} connected={}",
					previousWaypointsEnabled, waypointsEnabled, connectedToSharedMapServer);
			previousWaypointsEnabled = waypointsEnabled;
			if (waypointsEnabled && connectedToSharedMapServer) {
				SharedMapNetworking.requestWaypointSnapshot();
				reconcileWaypoints();
			}
		}
	}

	private static void handleSyncWatchdog() {
		long now = System.currentTimeMillis();
		boolean networkWaiting = tileRequestsInFlight > 0 || mapNodeRequestsInFlight > 0;
		if (shouldResetMapSync(networkWaiting, now - lastMapProgressMillis)) {
			XaeroMapsync_r.LOGGER.warn(
					"Map sync watchdog reset networkWaiting={} millisWithoutProgress={} tileRequestsInFlight={} mapNodeRequestsInFlight={} tileQueue={} nodeQueue={} applyQueue={}",
					networkWaiting, now - lastMapProgressMillis, tileRequestsInFlight,
					mapNodeRequestsInFlight, TILE_REQUEST_QUEUE.size(), MAP_NODE_QUEUE.size(),
					TILE_APPLY_QUEUE.size());
			resetMapQueues();
			retryMapSyncAtMillis = now + 250L;
		}
		if (retryMapSyncAtMillis != 0L && now >= retryMapSyncAtMillis && connectedToSharedMapServer && mapSyncAvailable()) {
			retryMapSyncAtMillis = 0L;
			if (mapSyncIncomplete) {
				XaeroMapsync_r.LOGGER.info("Retrying incomplete map sync after unavailable/unrenderable tile response");
				resetMapQueues();
			}
			XaeroMapsync_r.LOGGER.debug("Retrying map root request retryAt={} now={}", retryMapSyncAtMillis, now);
			requestMapRoot();
		}
		if (clientTicks % 100 == 0 && previousWaypointsEnabled) reconcileWaypoints();
	}

	static boolean shouldResetMapSync(boolean networkWaiting, long millisWithoutProgress) {
		// Xaero can legitimately defer a whole region while it loads or saves. Those downloaded
		// tiles must stay queued; only a network request that stopped responding warrants a reset.
		return networkWaiting && millisWithoutProgress >= 30_000L;
	}

	private static void pollMapRootIfIdle() {
		if (++mapRootPollTicks < 100) {
			return;
		}
		mapRootPollTicks = 0;
		if (!connectedToSharedMapServer || !previousMapSyncEnabled || !mapSyncAvailable() || currentDimension() == null) {
			return;
		}
		if (tileRequestsInFlight == 0 && mapNodeRequestsInFlight == 0
				&& TILE_REQUEST_QUEUE.isEmpty() && TILE_APPLY_QUEUE.isEmpty() && MAP_NODE_QUEUE.isEmpty()) {
			XaeroMapsync_r.LOGGER.debug("Polling map root while idle dimension={} completedRoot={}",
					currentDimension(), Long.toUnsignedString(knownMapRootHash(currentDimension())));
			requestMapRoot();
		}
	}

	public static int waypointCount() { return WAYPOINTS.activeCount(); }
	public static int tileCount() { return atomicMapSyncClient == null ? 0 : atomicMapSyncClient.appliedTileCount(); }
	public static int indexedTileCount() { return MAP_TILES.totalCount(); }
	public static int pendingTileCount() { return atomicMapSyncClient == null ? 0 : atomicMapSyncClient.pendingCount(); }
	public static String mapSyncStatus() { return atomicMapSyncClient == null ? "not-initialized" : atomicMapSyncClient.status(); }
	public static java.util.List<PublicWaypoint> waypointSnapshot() { return WAYPOINTS.snapshot(); }

	public static void shareSelectedXaeroWaypoint(Screen screen, WaypointVisibility visibility) {
		Minecraft minecraft = Minecraft.getInstance();
		try {
			requireWaypointMutationReady(minecraft);
			XaeroWaypointMutation mutation = waypointAdapter.prepareShare(screen, visibility, WAYPOINTS.snapshot(),
					minecraft.player.getUUID(), minecraft.player.getGameProfile().getName());
			if (mutation.update()) {
				SharedMapNetworking.updateWaypoint(mutation.waypoint());
			} else {
				SharedMapNetworking.createWaypoint(mutation.waypoint());
			}
			displayWaypointMessage(new TranslatableComponent("screen.xaero-mapsync_r.share.submitted",
					mutation.waypoint().name()));
		} catch (RuntimeException exception) {
			clearPendingWaypointMutations();
			XaeroMapsync_r.LOGGER.warn("Failed to share selected Xaero waypoint", exception);
			displayWaypointMessage(new TranslatableComponent("screen.xaero-mapsync_r.share.failed", message(exception)));
		}
	}

	public static void unshareSelectedXaeroWaypoint(Screen screen) {
		Minecraft minecraft = Minecraft.getInstance();
		try {
			requireWaypointMutationReady(minecraft);
			PublicWaypoint waypoint = waypointAdapter.prepareUnshare(screen, WAYPOINTS.snapshot(), minecraft.player.getUUID());
			SharedMapNetworking.deleteWaypoint(waypoint);
			displayWaypointMessage(new TranslatableComponent("screen.xaero-mapsync_r.share.remove_submitted", waypoint.name()));
		} catch (RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to unshare selected Xaero waypoint", exception);
			displayWaypointMessage(new TranslatableComponent("screen.xaero-mapsync_r.share.failed", message(exception)));
		}
	}

	public static Optional<WaypointVisibility> selectedXaeroWaypointVisibility(Screen screen) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!connectedToSharedMapServer || !SharedMapClientConfig.get().publicWaypointsEnabled()
				|| minecraft.player == null || waypointAdapter == null || !waypointAdapter.isAvailable()) {
			return Optional.empty();
		}
		return waypointAdapter.selectedVisibility(screen, WAYPOINTS.snapshot(), minecraft.player.getUUID());
	}

	private static void requireWaypointMutationReady(Minecraft minecraft) {
		if (!connectedToSharedMapServer) {
			throw new IllegalStateException("Not connected to a shared map server");
		}
		if (!SharedMapClientConfig.get().publicWaypointsEnabled()) {
			throw new IllegalStateException("Shared waypoints are disabled in the client config");
		}
		if (minecraft.player == null) {
			throw new IllegalStateException("Minecraft player is not initialized");
		}
		if (waypointAdapter == null || !waypointAdapter.isAvailable()) {
			throw new IllegalStateException("Xaero waypoint integration is unavailable");
		}
	}

	private static void clearPendingWaypointMutations() {
		if (waypointAdapter != null) {
			waypointAdapter.clearPendingMutations();
		}
	}

	private static void displayWaypointMessage(net.minecraft.network.chat.Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.displayClientMessage(message, false);
		}
	}

	private static String message(RuntimeException exception) {
		return exception.getMessage() == null || exception.getMessage().isBlank()
				? exception.getClass().getSimpleName()
				: exception.getMessage();
	}

	private static void markRootCompleteIfIdle() {
		if (canCompleteMapRoot(mapSyncIncomplete, mapNodeRequestsInFlight, MAP_NODE_QUEUE.size(),
				cacheLookupsInFlight(), TILE_CACHE_LOOKUP_QUEUE.size(), tileRequestsInFlight,
				TILE_REQUEST_QUEUE.size(), TILE_APPLY_QUEUE.size(), QUEUED_TILE_REVISIONS.size(),
				TILE_DATA.pendingWriteCount())
				&& syncingDimension != null && MAP_TILES.matchesRootHash(syncingDimension, syncingRootHash)
				&& COMPLETED_MAP_ROOTS.getOrDefault(syncingDimension, 0L) != MAP_TILES.rootHash()) {
			COMPLETED_MAP_ROOTS.put(syncingDimension, MAP_TILES.rootHash());
			XaeroMapsync_r.LOGGER.info("Map sync complete for {} rootHash={} localCacheHits={} serverTileRequests={}",
					syncingDimension, Long.toUnsignedString(MAP_TILES.rootHash()), localCacheHits, localCacheMisses);
		}
	}

	static boolean canCompleteMapRoot(boolean incomplete, int nodeRequests, int queuedNodes,
			int cacheLookups, int queuedCacheLookups, int tileRequests, int queuedTiles, int pendingApplies,
			int targetRevisions, int pendingCacheWrites) {
		return !incomplete && nodeRequests == 0 && queuedNodes == 0 && cacheLookups == 0
				&& queuedCacheLookups == 0 && tileRequests == 0 && queuedTiles == 0 && pendingApplies == 0
				&& targetRevisions == 0 && pendingCacheWrites == 0;
	}

	private static String currentDimension() {
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		return minecraft.level == null ? null : minecraft.level.dimension().location().toString();
	}

	private static void resetMapQueues() {
		XaeroMapsync_r.LOGGER.debug(
				"Resetting map queues generation={} lookupQueue={} requestQueue={} applyQueue={} nodeQueue={} targetRevisions={} inFlightTiles={} inFlightNodes={} pendingCacheWrites={}",
				mapSessionGeneration, TILE_CACHE_LOOKUP_QUEUE.size(), TILE_REQUEST_QUEUE.size(),
				TILE_APPLY_QUEUE.size(), MAP_NODE_QUEUE.size(), QUEUED_TILE_REVISIONS.size(),
				tileRequestsInFlight, mapNodeRequestsInFlight, TILE_DATA.pendingWriteCount());
		advanceCacheLookupGeneration();
		TILE_CACHE_LOOKUP_QUEUE.clear();
		TILE_REQUEST_QUEUE.clear();
		TILE_APPLY_QUEUE.clear();
		MAP_NODE_QUEUE.clear();
		QUEUED_TILE_REVISIONS.clear();
		IN_FLIGHT_TILE_REQUESTS.clear();
		PENDING_TILE_APPLIES.clear();
		LOCAL_TILE_HINT_TIMES.clear();
		LOCAL_TILE_UPLOAD_HASHES.clear();
		LOCAL_TILE_PENDING_HASHES.clear();
		LOCAL_TILE_PENDING_SINCE.clear();
		LOCAL_TILE_UPLOADS_IN_FLIGHT.clear();
		RECOVERY_TILE_UPLOADS_IN_FLIGHT.clear();
		localLiveScanDimension = null;
		localLiveScanRadius = -1;
		localLiveScanCursor = 0;
		PENDING_MAP_REQUEST_IDS.clear();
		MERKLE.replace(java.util.List.of());
		tileRequestsInFlight = 0;
		mapNodeRequestsInFlight = 0;
		activeMapSyncId = 0L;
		mapSyncIncomplete = false;
		syncingDimension = null;
		syncingRootHash = 0L;
	}

	private static void beginMapSession() {
		XaeroMapsync_r.LOGGER.debug("Beginning map session previousGeneration={} indexedTiles={} cachedTiles={}",
				mapSessionGeneration, MAP_TILES.totalCount(), TILE_DATA.totalCount());
		resetMapQueues();
		TILE_DATA.clearSession();
		MAP_TILES.replace(0L, java.util.List.of());
		COMPLETED_MAP_ROOTS.clear();
		localCacheHits = 0;
		localCacheMisses = 0;
		retryMapSyncAtMillis = 0L;
		lastMapProgressMillis = System.currentTimeMillis();
	}

	private record PendingTileApply(String key, MapTile tile, long revision, long retryAtMillis, int attempts) {}
}
