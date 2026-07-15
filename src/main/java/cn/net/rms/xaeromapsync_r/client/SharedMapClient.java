package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.network.MapTileIndexSnapshotPayload;
import cn.net.rms.xaeromapsync_r.network.MapMerkleSnapshotPayload;
import cn.net.rms.xaeromapsync_r.network.MapNodeResponsePayload;
import cn.net.rms.xaeromapsync_r.map.MerkleNode;
import cn.net.rms.xaeromapsync_r.map.MerkleNodeAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import cn.net.rms.xaeromapsync_r.network.ServerHelloPayload;
import cn.net.rms.xaeromapsync_r.network.SharedMapNetworking;
import cn.net.rms.xaeromapsync_r.network.TileDataPayload;
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
	private static boolean connectedToSharedMapServer;
	private static final ClientWaypointCache WAYPOINTS = new ClientWaypointCache();
	private static final ClientMapTileIndexCache MAP_TILES = new ClientMapTileIndexCache();
	private static final ClientMapTileCache TILE_DATA = new ClientMapTileCache();
	private static final ClientMerkleCache MERKLE = new ClientMerkleCache();
	private static final ArrayDeque<MapTileIndexEntry> TILE_CACHE_LOOKUP_QUEUE = new ArrayDeque<>();
	private static final ArrayDeque<MapTileIndexEntry> TILE_REQUEST_QUEUE = new ArrayDeque<>();
	private static final Map<String, Long> QUEUED_TILE_REVISIONS = new HashMap<>();
	private static final Map<String, Long> IN_FLIGHT_TILE_REQUESTS = new HashMap<>();
	private static final Map<String, Long> LOCAL_TILE_REVISIONS = new HashMap<>();
	private static final Map<String, Long> LOCAL_TILE_HINT_TIMES = new HashMap<>();
	private static final ArrayDeque<PendingTileApply> TILE_APPLY_QUEUE = new ArrayDeque<>();
	private static final Set<String> PENDING_TILE_APPLIES = new HashSet<>();
	private static final ArrayDeque<MerkleNodeAddress> MAP_NODE_QUEUE = new ArrayDeque<>();
	private static final Set<Long> PENDING_MAP_REQUEST_IDS = new HashSet<>();
	private static final int MAX_PENDING_TILE_APPLIES = 1024;
	private static final int MAX_CACHE_LOOKUPS_IN_FLIGHT = 32;
	private static final int MAX_TILE_APPLIES_PER_TICK = 24;
	private static final long TILE_APPLY_BUDGET_NANOS = 8_000_000L;
	private static final long TILE_APPLY_RETRY_MILLIS = 250L;
	private static final long MAX_TILE_APPLY_RETRY_MILLIS = 5_000L;
	private static final long LOCAL_TILE_HINT_COOLDOWN_MILLIS = 300_000L;
	private static final int LOCAL_TILE_HINT_RADIUS = 4;
	private static final int MAX_LOCAL_TILE_HINTS_PER_SCAN = 6;
	private static int tileRequestsInFlight;
	private static int tileCacheLookupsInFlight;
	private static XaeroWaypointAdapter waypointAdapter;
	private static XaeroMapAdapter mapAdapter;
	private static int clientTicks;
	private static int mapRootPollTicks;
	private static int mapNodeRequestsInFlight;
	private static final Map<String, Long> COMPLETED_MAP_ROOTS = new java.util.LinkedHashMap<>();
	private static boolean previousMapSyncEnabled;
	private static boolean previousWaypointsEnabled;
	private static String previousDimension;
	private static String syncingDimension;
	private static long syncingRootHash;
	private static long lastMapProgressMillis;
	private static long retryMapSyncAtMillis;
	private static boolean mapSyncIncomplete;
	private static long mapSessionGeneration;
	private static long activeMapSyncId;
	private static long nextMapSyncId;
	private static long nextMapRequestId;
	private static int localCacheHits;
	private static int localCacheMisses;
	private static boolean waypointSanitizationPending = true;

	private SharedMapClient() {
	}

	public static void register() {
		XaeroMapsync_r.LOGGER.info("Xaero availability: {}", XaeroDetector.status().message());
		waypointAdapter = XaeroWaypointAdapters.create();
		mapAdapter = new ReflectiveXaeroMapAdapter();
		TILE_DATA.start(FabricLoader.getInstance().getConfigDir().resolve("xaero-mapsync_r-client-tiles-v5"));
		previousMapSyncEnabled = SharedMapClientConfig.get().mapSyncEnabled();
		previousWaypointsEnabled = SharedMapClientConfig.get().publicWaypointsEnabled();
		previousDimension = currentDimension();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			clientTicks++;
			SharedMapNetworking.tickClientTransfers();
			handleConfigChanges();
			processPendingTileApplications();
			reportNativeLocalTiles();
			sanitizeWaypointsIfNeeded();
			handleSyncWatchdog();
			pollMapRootIfIdle();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> TILE_DATA.stop());
	}

	public static void handleServerHello(ServerHelloPayload hello) {
		connectedToSharedMapServer = hello.accepted();
		if (hello.accepted()) {
			beginMapSession();
			previousDimension = currentDimension();
			XaeroMapsync_r.LOGGER.info("Shared map handshake accepted: {}", hello.message());
			if (SharedMapClientConfig.get().publicWaypointsEnabled()) SharedMapNetworking.requestWaypointSnapshot();
			if (SharedMapClientConfig.get().mapSyncEnabled() && mapSyncAvailable()) requestMapRoot();
			return;
		}
		XaeroMapsync_r.LOGGER.warn("Shared map handshake rejected: {}", hello.message());
	}

	public static void disconnect() {
		connectedToSharedMapServer = false;
		beginMapSession();
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
		TILE_DATA.cache(payload.tile(), payload.revision());
		Long requestedRevision = IN_FLIGHT_TILE_REQUESTS.get(key);
		if (requestedRevision != null && payload.revision() >= requestedRevision) {
			IN_FLIGHT_TILE_REQUESTS.remove(key);
			tileRequestsInFlight = Math.max(0, tileRequestsInFlight - 1);
		}
		TILE_REQUEST_QUEUE.removeIf(entry -> tileKey(entry.dimension(), entry.chunkX(), entry.chunkZ()).equals(key)
				&& entry.revision() <= payload.revision());
		MAP_TILES.upsert(new MapTileIndexEntry(payload.tile().dimension(), payload.tile().chunkX(), payload.tile().chunkZ(),
				payload.tile().contentHash(), payload.revision(), System.currentTimeMillis()));
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
			pumpTileRequests();
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
		pumpTileRequests();
		XaeroMapsync_r.LOGGER.debug("Queued tile data {} {} {} revision={}",
				payload.tile().dimension(),
				payload.tile().chunkX(),
				payload.tile().chunkZ(),
				payload.revision());
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
			if (!shouldReplacePendingRevision(existing.revision, revision)) return true;
			TILE_APPLY_QUEUE.remove(existing);
		} else {
			if (TILE_APPLY_QUEUE.size() >= MAX_PENDING_TILE_APPLIES) return false;
			PENDING_TILE_APPLIES.add(key);
		}
		TILE_APPLY_QUEUE.addLast(new PendingTileApply(key, tile, revision, 0L, 0));
		return true;
	}

	static boolean shouldReplacePendingRevision(long pendingRevision, long incomingRevision) {
		return incomingRevision > pendingRevision;
	}

	static boolean shouldDiscardPendingRevision(long pendingRevision, long appliedRevision) {
		return pendingRevision <= appliedRevision;
	}

	private static void processPendingTileApplications() {
		if (TILE_APPLY_QUEUE.isEmpty() || mapAdapter == null || !mapAdapter.isAvailable()) return;
		long now = System.currentTimeMillis();
		long deadline = System.nanoTime() + TILE_APPLY_BUDGET_NANOS;
		int inspected = TILE_APPLY_QUEUE.size();
		int attempted = 0;
		Integer batchRegionX = null;
		Integer batchRegionZ = null;
		List<PendingTileApply> remoteBatch = new ArrayList<>();
		while (inspected-- > 0 && attempted < MAX_TILE_APPLIES_PER_TICK && !TILE_APPLY_QUEUE.isEmpty()) {
			PendingTileApply pending = TILE_APPLY_QUEUE.removeFirst();
			if (pending.retryAtMillis > now) {
				TILE_APPLY_QUEUE.addLast(pending);
				continue;
			}
			XaeroMapAdapter.LocalTileState localTileState = mapAdapter.localTileState(pending.tile);
			if (localTileState == XaeroMapAdapter.LocalTileState.READY) {
				completeLocalTile(pending);
				continue;
			}
			if (shouldWaitForLocalTile(localTileState, pending.attempts)) {
				TILE_APPLY_QUEUE.addLast(new PendingTileApply(pending.key, pending.tile, pending.revision,
						now + tileApplyRetryMillis(pending.attempts), pending.attempts + 1));
				continue;
			}
			int regionX = Math.floorDiv(pending.tile.chunkX(), 32);
			int regionZ = Math.floorDiv(pending.tile.chunkZ(), 32);
			if (batchRegionX != null && (batchRegionX != regionX || batchRegionZ != regionZ)) {
				TILE_APPLY_QUEUE.addLast(pending);
				continue;
			}
			batchRegionX = regionX;
			batchRegionZ = regionZ;
			remoteBatch.add(pending);
			attempted++;
			if (System.nanoTime() >= deadline) break;
		}
		if (!remoteBatch.isEmpty()) {
			XaeroMapAdapter.ApplyResult result = mapAdapter.applyBatchResult(remoteBatch.stream()
					.map(PendingTileApply::tile).toList());
			if (result == XaeroMapAdapter.ApplyResult.APPLIED) {
				remoteBatch.forEach(SharedMapClient::completeTileApply);
			} else if (result == XaeroMapAdapter.ApplyResult.RETRY_LATER) {
				for (PendingTileApply pending : remoteBatch) {
					TILE_APPLY_QUEUE.addLast(new PendingTileApply(pending.key, pending.tile, pending.revision,
							now + tileApplyRetryMillis(pending.attempts), pending.attempts + 1));
				}
			} else {
				resetMapQueues();
				return;
			}
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

	private static void completeLocalTile(PendingTileApply pending) {
		LOCAL_TILE_REVISIONS.merge(pending.key, pending.revision, Math::max);
		reportLocalTileReady(pending.tile.dimension(), pending.tile.chunkX(), pending.tile.chunkZ(),
				pending.tile.contentHash());
		PENDING_TILE_APPLIES.remove(pending.key);
		removeQueuedRevisionAtMost(pending.key, pending.revision);
		lastMapProgressMillis = System.currentTimeMillis();
		XaeroMapsync_r.LOGGER.debug("Resolved tile through Xaero native writer {} {} {} revision={}",
				pending.tile.dimension(), pending.tile.chunkX(), pending.tile.chunkZ(), pending.revision);
	}

	private static void reportNativeLocalTiles() {
		if (clientTicks % 20 != 0 || !connectedToSharedMapServer || !previousMapSyncEnabled
				|| mapAdapter == null || !mapAdapter.isAvailable()) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) return;
		String dimension = minecraft.level.dimension().location().toString();
		net.minecraft.world.level.ChunkPos center = minecraft.player.chunkPosition();
		long now = System.currentTimeMillis();
		int sent = 0;
		for (int radius = 0; radius <= LOCAL_TILE_HINT_RADIUS && sent < MAX_LOCAL_TILE_HINTS_PER_SCAN; radius++) {
			for (int dx = -radius; dx <= radius && sent < MAX_LOCAL_TILE_HINTS_PER_SCAN; dx++) {
				for (int dz = -radius; dz <= radius && sent < MAX_LOCAL_TILE_HINTS_PER_SCAN; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
					int chunkX = center.x + dx;
					int chunkZ = center.z + dz;
					String key = tileKey(dimension, chunkX, chunkZ);
					long previous = LOCAL_TILE_HINT_TIMES.getOrDefault(key, 0L);
					if (now - previous < LOCAL_TILE_HINT_COOLDOWN_MILLIS) continue;
					if (mapAdapter.localTileState(dimension, chunkX, chunkZ) != XaeroMapAdapter.LocalTileState.READY) continue;
					long contentHash = MAP_TILES.find(dimension, chunkX, chunkZ)
							.map(MapTileIndexEntry::contentHash).orElse(0L);
					reportLocalTileReady(dimension, chunkX, chunkZ, contentHash);
					sent++;
				}
			}
		}
		if (LOCAL_TILE_HINT_TIMES.size() > 4_096) {
			LOCAL_TILE_HINT_TIMES.entrySet().removeIf(entry -> now - entry.getValue() >= LOCAL_TILE_HINT_COOLDOWN_MILLIS);
		}
	}

	private static void reportLocalTileReady(String dimension, int chunkX, int chunkZ, long contentHash) {
		String key = tileKey(dimension, chunkX, chunkZ);
		long now = System.currentTimeMillis();
		long previous = LOCAL_TILE_HINT_TIMES.getOrDefault(key, 0L);
		if (now - previous < LOCAL_TILE_HINT_COOLDOWN_MILLIS) return;
		LOCAL_TILE_HINT_TIMES.put(key, now);
		SharedMapNetworking.sendLocalTileReady(dimension, chunkX, chunkZ, contentHash);
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
		for (MapTileIndexEntry entry : entries) {
			String key = tileKey(entry.dimension(), entry.chunkX(), entry.chunkZ());
			if (TILE_DATA.hasRevision(entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision())) continue;
			Long targetRevision = QUEUED_TILE_REVISIONS.get(key);
			if (targetRevision == null || entry.revision() > targetRevision) {
				QUEUED_TILE_REVISIONS.put(key, entry.revision());
				TILE_CACHE_LOOKUP_QUEUE.add(entry);
			}
		}
		pumpTileCacheLookups();
		pumpTileRequests();
	}

	private static void pumpTileCacheLookups() {
		while (tileCacheLookupsInFlight < MAX_CACHE_LOOKUPS_IN_FLIGHT && !TILE_CACHE_LOOKUP_QUEUE.isEmpty()) {
			MapTileIndexEntry entry = TILE_CACHE_LOOKUP_QUEUE.remove();
			long generation = mapSessionGeneration;
			tileCacheLookupsInFlight++;
			TILE_DATA.findCached(entry).thenAccept(cached -> Minecraft.getInstance().execute(
					() -> handleCachedTile(entry, generation, cached)));
		}
	}

	private static void handleCachedTile(MapTileIndexEntry entry, long generation, Optional<MapTile> cached) {
		if (generation != mapSessionGeneration) return;
		tileCacheLookupsInFlight = Math.max(0, tileCacheLookupsInFlight - 1);
		String key = tileKey(entry.dimension(), entry.chunkX(), entry.chunkZ());
		Long targetRevision = QUEUED_TILE_REVISIONS.get(key);
		if (targetRevision == null || targetRevision != entry.revision()) {
			pumpTileCacheLookups();
			markRootCompleteIfIdle();
			return;
		}
		if (TILE_DATA.hasRevision(entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision())) {
			removeQueuedRevisionAtMost(key, entry.revision());
		} else if (cached.isPresent()) {
			localCacheHits++;
			if (!enqueuePendingTileApply(key, cached.get(), entry.revision())) {
				removeQueuedRevisionAtMost(key, entry.revision());
				mapSyncIncomplete = true;
				retryMapSyncAtMillis = System.currentTimeMillis() + 5_000L;
			}
		} else {
			localCacheMisses++;
			TILE_REQUEST_QUEUE.add(entry);
		}
		lastMapProgressMillis = System.currentTimeMillis();
		pumpTileCacheLookups();
		pumpTileRequests();
		markRootCompleteIfIdle();
	}

	private static void pumpTileRequests() {
		int limit = SharedMapConfig.maxTileRequestsPerSnapshot();
		while (canRequestTile(tileRequestsInFlight, TILE_APPLY_QUEUE.size(), limit, MAX_PENDING_TILE_APPLIES)
				&& !TILE_REQUEST_QUEUE.isEmpty()) {
			MapTileIndexEntry entry = TILE_REQUEST_QUEUE.remove();
			String key = tileKey(entry.dimension(), entry.chunkX(), entry.chunkZ());
			Long targetRevision = QUEUED_TILE_REVISIONS.get(key);
			if (targetRevision == null || targetRevision != entry.revision()) continue;
			Long requestedRevision = IN_FLIGHT_TILE_REQUESTS.get(key);
			if (requestedRevision != null) continue;
			SharedMapNetworking.requestTile(entry);
			IN_FLIGHT_TILE_REQUESTS.put(key, entry.revision());
			tileRequestsInFlight++;
			lastMapProgressMillis = System.currentTimeMillis();
		}
		markRootCompleteIfIdle();
	}

	static boolean canRequestTile(int inFlight, int pendingApplies, int requestLimit, int maxPendingApplies) {
		return inFlight < requestLimit && inFlight + pendingApplies < maxPendingApplies;
	}

	private static void pumpMapNodeRequests() {
		while (mapNodeRequestsInFlight < 4 && !MAP_NODE_QUEUE.isEmpty()) {
			List<MerkleNodeAddress> batch = new ArrayList<>(64);
			while (batch.size() < 64 && !MAP_NODE_QUEUE.isEmpty()) batch.add(MAP_NODE_QUEUE.remove());
			long requestId = nextMapRequestId();
			PENDING_MAP_REQUEST_IDS.add(requestId);
			mapNodeRequestsInFlight = PENDING_MAP_REQUEST_IDS.size();
			SharedMapNetworking.requestMapNodes(activeMapSyncId, requestId, batch);
			lastMapProgressMillis = System.currentTimeMillis();
		}
	}

	private static void requestMapRoot() {
		if (!connectedToSharedMapServer || !mapSyncAvailable() || currentDimension() == null) return;
		activeMapSyncId = incrementNonZero(nextMapSyncId);
		nextMapSyncId = activeMapSyncId;
		PENDING_MAP_REQUEST_IDS.clear();
		long requestId = nextMapRequestId();
		PENDING_MAP_REQUEST_IDS.add(requestId);
		mapNodeRequestsInFlight = 1;
		lastMapProgressMillis = System.currentTimeMillis();
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
			previousDimension = dimension;
			resetMapQueues();
			requestMapRoot();
		}
		if (mapEnabled != previousMapSyncEnabled) {
			previousMapSyncEnabled = mapEnabled;
			if (mapEnabled && connectedToSharedMapServer) requestMapRoot();
			if (!mapEnabled) resetMapQueues();
		}
		if (waypointsEnabled != previousWaypointsEnabled) {
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
			resetMapQueues();
			retryMapSyncAtMillis = now + 250L;
		}
		if (retryMapSyncAtMillis != 0L && now >= retryMapSyncAtMillis && connectedToSharedMapServer && mapSyncAvailable()) {
			retryMapSyncAtMillis = 0L;
			if (mapSyncIncomplete) {
				resetMapQueues();
			}
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
			requestMapRoot();
		}
	}

	public static int waypointCount() { return WAYPOINTS.activeCount(); }
	public static int tileCount() { return TILE_DATA.totalCount(); }
	public static int indexedTileCount() { return MAP_TILES.totalCount(); }
	public static int pendingTileCount() { return TILE_CACHE_LOOKUP_QUEUE.size() + tileCacheLookupsInFlight
			+ TILE_REQUEST_QUEUE.size() + tileRequestsInFlight + TILE_APPLY_QUEUE.size(); }
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
				tileCacheLookupsInFlight, TILE_CACHE_LOOKUP_QUEUE.size(), tileRequestsInFlight,
				TILE_REQUEST_QUEUE.size(), TILE_APPLY_QUEUE.size(), QUEUED_TILE_REVISIONS.size())
				&& syncingDimension != null && MAP_TILES.matchesRootHash(syncingDimension, syncingRootHash)
				&& COMPLETED_MAP_ROOTS.getOrDefault(syncingDimension, 0L) != MAP_TILES.rootHash()) {
			COMPLETED_MAP_ROOTS.put(syncingDimension, MAP_TILES.rootHash());
			XaeroMapsync_r.LOGGER.info("Map sync complete for {} rootHash={} localCacheHits={} serverTileRequests={}",
					syncingDimension, Long.toUnsignedString(MAP_TILES.rootHash()), localCacheHits, localCacheMisses);
		}
	}

	static boolean canCompleteMapRoot(boolean incomplete, int nodeRequests, int queuedNodes,
			int cacheLookups, int queuedCacheLookups, int tileRequests, int queuedTiles, int pendingApplies,
			int targetRevisions) {
		return !incomplete && nodeRequests == 0 && queuedNodes == 0 && cacheLookups == 0
				&& queuedCacheLookups == 0 && tileRequests == 0 && queuedTiles == 0 && pendingApplies == 0
				&& targetRevisions == 0;
	}

	private static String currentDimension() {
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		return minecraft.level == null ? null : minecraft.level.dimension().location().toString();
	}

	private static void resetMapQueues() {
		mapSessionGeneration++;
		TILE_CACHE_LOOKUP_QUEUE.clear();
		TILE_REQUEST_QUEUE.clear();
		TILE_APPLY_QUEUE.clear();
		MAP_NODE_QUEUE.clear();
		QUEUED_TILE_REVISIONS.clear();
		IN_FLIGHT_TILE_REQUESTS.clear();
		PENDING_TILE_APPLIES.clear();
		LOCAL_TILE_REVISIONS.clear();
		LOCAL_TILE_HINT_TIMES.clear();
		PENDING_MAP_REQUEST_IDS.clear();
		MERKLE.replace(java.util.List.of());
		tileRequestsInFlight = 0;
		tileCacheLookupsInFlight = 0;
		mapNodeRequestsInFlight = 0;
		activeMapSyncId = 0L;
		mapSyncIncomplete = false;
		syncingDimension = null;
		syncingRootHash = 0L;
	}

	private static void beginMapSession() {
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
