package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.network.MapTileIndexSnapshotPayload;
import cn.net.rms.xaeromapsync_r.network.MapMerkleSnapshotPayload;
import cn.net.rms.xaeromapsync_r.network.MapNodeResponsePayload;
import cn.net.rms.xaeromapsync_r.map.MerkleNode;
import cn.net.rms.xaeromapsync_r.map.MerkleNodeAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import cn.net.rms.xaeromapsync_r.network.ServerHelloPayload;
import cn.net.rms.xaeromapsync_r.network.SharedMapNetworking;
import cn.net.rms.xaeromapsync_r.network.TileDataPayload;
import cn.net.rms.xaeromapsync_r.network.WaypointSnapshotPayload;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.xaero.XaeroDetector;
import cn.net.rms.xaeromapsync_r.xaero.ReflectiveXaeroMapAdapter;
import cn.net.rms.xaeromapsync_r.xaero.XaeroMapAdapter;
import cn.net.rms.xaeromapsync_r.xaero.XaeroWaypointAdapter;
import cn.net.rms.xaeromapsync_r.xaero.XaeroWaypointAdapters;

public final class SharedMapClient {
	private static boolean connectedToSharedMapServer;
	private static final ClientWaypointCache WAYPOINTS = new ClientWaypointCache();
	private static final ClientMapTileIndexCache MAP_TILES = new ClientMapTileIndexCache();
	private static final ClientMapTileCache TILE_DATA = new ClientMapTileCache();
	private static final ClientMerkleCache MERKLE = new ClientMerkleCache();
	private static final ArrayDeque<MapTileIndexEntry> TILE_REQUEST_QUEUE = new ArrayDeque<>();
	private static final Set<String> QUEUED_TILES = new HashSet<>();
	private static final ArrayDeque<MerkleNodeAddress> MAP_NODE_QUEUE = new ArrayDeque<>();
	private static int tileRequestsInFlight;
	private static XaeroWaypointAdapter waypointAdapter;
	private static XaeroMapAdapter mapAdapter;
	private static int persistenceTicks;
	private static int mapNodeRequestsInFlight;
	private static final Map<String, Long> COMPLETED_MAP_ROOTS = new java.util.LinkedHashMap<>();
	private static java.nio.file.Path rootHashPath;
	private static boolean previousMapSyncEnabled;
	private static boolean previousWaypointsEnabled;
	private static String previousDimension;
	private static String syncingDimension;
	private static long syncingRootHash;
	private static long lastMapProgressMillis;
	private static long retryMapSyncAtMillis;

	private SharedMapClient() {
	}

	public static void register() {
		XaeroMapsync_r.LOGGER.info("Xaero availability: {}", XaeroDetector.status().message());
		waypointAdapter = XaeroWaypointAdapters.create();
		mapAdapter = new ReflectiveXaeroMapAdapter();
		TILE_DATA.load(FabricLoader.getInstance().getConfigDir().resolve("xaero-mapsync_r-client-revisions.properties"));
		rootHashPath = FabricLoader.getInstance().getConfigDir().resolve("xaero-mapsync_r-client-root.properties");
		loadCompletedRootHash();
		previousMapSyncEnabled = SharedMapClientConfig.get().mapSyncEnabled();
		previousWaypointsEnabled = SharedMapClientConfig.get().publicWaypointsEnabled();
		previousDimension = currentDimension();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			SharedMapNetworking.tickClientTransfers();
			handleConfigChanges();
			handleSyncWatchdog();
			if (++persistenceTicks >= 200) {
				persistenceTicks = 0;
				TILE_DATA.saveIfDirty();
			}
		});
	}

	public static void handleServerHello(ServerHelloPayload hello) {
		connectedToSharedMapServer = hello.accepted();
		if (hello.accepted()) {
			XaeroMapsync_r.LOGGER.info("Shared map handshake accepted: {}", hello.message());
			SharedMapNetworking.requestEnabledSnapshots();
			return;
		}
		XaeroMapsync_r.LOGGER.warn("Shared map handshake rejected: {}", hello.message());
	}

	public static void disconnect() {
		connectedToSharedMapServer = false;
		resetMapQueues();
		TILE_DATA.saveIfDirty();
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
		if (mapAdapter == null || !mapAdapter.isAvailable()) {
			resetMapQueues();
			return;
		}
		if (!mapAdapter.apply(payload.tile())) {
			resetMapQueues();
			if (mapAdapter.isAvailable()) retryMapSyncAtMillis = System.currentTimeMillis() + 2_000L;
			return;
		}
		TILE_DATA.put(payload.tile(), payload.revision());
		QUEUED_TILES.remove(tileKey(payload.tile().dimension(), payload.tile().chunkX(), payload.tile().chunkZ()));
		tileRequestsInFlight = Math.max(0, tileRequestsInFlight - 1);
		lastMapProgressMillis = System.currentTimeMillis();
		pumpTileRequests();
		XaeroMapsync_r.LOGGER.debug("Received tile data {} {} {} revision={}",
				payload.tile().dimension(),
				payload.tile().chunkX(),
				payload.tile().chunkZ(),
				payload.revision());
	}

	public static void handleMapNodeResponse(MapNodeResponsePayload payload) {
		if (!SharedMapClientConfig.get().mapSyncEnabled()) return;
		if (!payload.dimension().equals(currentDimension())) return;
		if (syncingDimension == null) {
			syncingDimension = payload.dimension();
			syncingRootHash = payload.rootHash();
		} else if (!syncingDimension.equals(payload.dimension()) || syncingRootHash != payload.rootHash()) {
			resetMapQueues();
			retryMapSyncAtMillis = System.currentTimeMillis() + 250L;
			return;
		}
		lastMapProgressMillis = System.currentTimeMillis();
		if (mapNodeRequestsInFlight > 0) mapNodeRequestsInFlight--;
		MAP_TILES.setRootHash(syncingRootHash);
		Map<String, MapTileIndexEntry> leafEntries = payload.entries().stream().collect(Collectors.toMap(
				entry -> tileKey(entry.dimension(), entry.chunkX(), entry.chunkZ()), Function.identity(), (left, right) -> right));
		List<MerkleNodeAddress> changedBranches = new ArrayList<>();
		List<MapTileIndexEntry> changedLeaves = new ArrayList<>();
		for (MerkleNode node : payload.nodes()) {
			boolean changed = MERKLE.find(node).map(previous -> previous.hash() != node.hash()).orElse(true);
			MERKLE.put(node);
			if (!changed) {
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

	private static void enqueueTiles(Iterable<MapTileIndexEntry> entries) {
		for (MapTileIndexEntry entry : entries) {
			String key = tileKey(entry.dimension(), entry.chunkX(), entry.chunkZ());
			if (QUEUED_TILES.add(key)) {
				TILE_REQUEST_QUEUE.add(entry);
			}
		}
		pumpTileRequests();
	}

	private static void pumpTileRequests() {
		int limit = SharedMapConfig.maxTileRequestsPerSnapshot();
		while (tileRequestsInFlight < limit && !TILE_REQUEST_QUEUE.isEmpty()) {
			SharedMapNetworking.requestTile(TILE_REQUEST_QUEUE.remove());
			tileRequestsInFlight++;
			lastMapProgressMillis = System.currentTimeMillis();
		}
		markRootCompleteIfIdle();
	}

	private static void pumpMapNodeRequests() {
		while (mapNodeRequestsInFlight < 4 && !MAP_NODE_QUEUE.isEmpty()) {
			List<MerkleNodeAddress> batch = new ArrayList<>(64);
			while (batch.size() < 64 && !MAP_NODE_QUEUE.isEmpty()) batch.add(MAP_NODE_QUEUE.remove());
			SharedMapNetworking.requestMapNodes(batch);
			mapNodeRequestsInFlight++;
			lastMapProgressMillis = System.currentTimeMillis();
		}
	}

	private static String tileKey(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + chunkX + ":" + chunkZ;
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

	private static void handleConfigChanges() {
		boolean mapEnabled = SharedMapClientConfig.get().mapSyncEnabled() && mapSyncAvailable();
		boolean waypointsEnabled = SharedMapClientConfig.get().publicWaypointsEnabled();
		String dimension = currentDimension();
		if (mapEnabled && connectedToSharedMapServer && dimension != null && !dimension.equals(previousDimension)) {
			previousDimension = dimension;
			resetMapQueues();
			SharedMapNetworking.requestMapSync();
		}
		if (mapEnabled != previousMapSyncEnabled) {
			previousMapSyncEnabled = mapEnabled;
			if (mapEnabled && connectedToSharedMapServer) SharedMapNetworking.requestMapSync();
			if (!mapEnabled) resetMapQueues();
		}
		if (waypointsEnabled != previousWaypointsEnabled) {
			previousWaypointsEnabled = waypointsEnabled;
			if (waypointsEnabled && connectedToSharedMapServer) {
				SharedMapNetworking.requestWaypointSnapshot();
				reconcileWaypoints();
			} else if (!waypointsEnabled && waypointAdapter != null && waypointAdapter.isAvailable()) {
				waypointAdapter.reconcile(java.util.List.of());
			}
		}
	}

	private static void handleSyncWatchdog() {
		long now = System.currentTimeMillis();
		boolean waiting = tileRequestsInFlight > 0 || mapNodeRequestsInFlight > 0;
		if (waiting && now - lastMapProgressMillis >= 30_000L) {
			resetMapQueues();
			retryMapSyncAtMillis = now + 250L;
		}
		if (retryMapSyncAtMillis != 0L && now >= retryMapSyncAtMillis && connectedToSharedMapServer && mapSyncAvailable()) {
			retryMapSyncAtMillis = 0L;
			SharedMapNetworking.requestMapSync();
		}
		if (persistenceTicks % 100 == 0 && previousWaypointsEnabled) reconcileWaypoints();
	}

	public static int waypointCount() { return WAYPOINTS.activeCount(); }
	public static int tileCount() { return TILE_DATA.totalCount(); }
	public static int indexedTileCount() { return MAP_TILES.totalCount(); }
	public static int pendingTileCount() { return TILE_REQUEST_QUEUE.size() + tileRequestsInFlight; }
	public static java.util.List<PublicWaypoint> waypointSnapshot() { return WAYPOINTS.snapshot(); }

	private static void markRootCompleteIfIdle() {
		if (mapNodeRequestsInFlight == 0 && MAP_NODE_QUEUE.isEmpty() && tileRequestsInFlight == 0 && TILE_REQUEST_QUEUE.isEmpty()
				&& syncingDimension != null && COMPLETED_MAP_ROOTS.getOrDefault(syncingDimension, 0L) != MAP_TILES.rootHash()) {
			COMPLETED_MAP_ROOTS.put(syncingDimension, MAP_TILES.rootHash());
			saveCompletedRootHash();
		}
	}

	private static void loadCompletedRootHash() {
		if (rootHashPath == null || !java.nio.file.Files.isRegularFile(rootHashPath)) return;
		java.util.Properties values = new java.util.Properties();
		try (java.io.InputStream input = java.nio.file.Files.newInputStream(rootHashPath)) {
			values.load(input);
			for (String key : values.stringPropertyNames()) {
				String dimension = new String(java.util.Base64.getUrlDecoder().decode(key), java.nio.charset.StandardCharsets.UTF_8);
				COMPLETED_MAP_ROOTS.put(dimension, Long.parseUnsignedLong(values.getProperty(key)));
			}
		} catch (java.io.IOException | RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to load completed client map root", exception);
		}
	}

	private static void saveCompletedRootHash() {
		if (rootHashPath == null) return;
		java.util.Properties values = new java.util.Properties();
		for (Map.Entry<String, Long> entry : COMPLETED_MAP_ROOTS.entrySet()) {
			String key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			values.setProperty(key, Long.toUnsignedString(entry.getValue()));
		}
		java.nio.file.Path temp = rootHashPath.resolveSibling(rootHashPath.getFileName() + ".tmp");
		try {
			java.nio.file.Files.createDirectories(rootHashPath.getParent());
			try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(temp)) { values.store(output, "Xaero Map Sync completed root"); }
			try {
				java.nio.file.Files.move(temp, rootHashPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
						java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
				java.nio.file.Files.move(temp, rootHashPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (java.io.IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to save completed client map root", exception);
		}
	}

	private static String currentDimension() {
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		return minecraft.level == null ? null : minecraft.level.dimension().location().toString();
	}

	private static void resetMapQueues() {
		TILE_REQUEST_QUEUE.clear();
		MAP_NODE_QUEUE.clear();
		QUEUED_TILES.clear();
		MERKLE.replace(java.util.List.of());
		tileRequestsInFlight = 0;
		mapNodeRequestsInFlight = 0;
		syncingDimension = null;
		syncingRootHash = 0L;
	}
}
