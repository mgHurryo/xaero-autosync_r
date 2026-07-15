package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.network.MapTileIndexSnapshotPayload;
import cn.net.rms.xaeromapsync_r.network.MapMerkleSnapshotPayload;
import cn.net.rms.xaeromapsync_r.network.ServerHelloPayload;
import cn.net.rms.xaeromapsync_r.network.SharedMapNetworking;
import cn.net.rms.xaeromapsync_r.network.TileDataPayload;
import cn.net.rms.xaeromapsync_r.network.WaypointSnapshotPayload;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.xaero.XaeroDetector;

public final class SharedMapClient {
	private static boolean connectedToSharedMapServer;
	private static final ClientWaypointCache WAYPOINTS = new ClientWaypointCache();
	private static final ClientMapTileIndexCache MAP_TILES = new ClientMapTileIndexCache();
	private static final ClientMapTileCache TILE_DATA = new ClientMapTileCache();
	private static final ClientMerkleCache MERKLE = new ClientMerkleCache();

	private SharedMapClient() {
	}

	public static void register() {
		XaeroMapsync_r.LOGGER.info("Xaero availability: {}", XaeroDetector.status().message());
	}

	public static void handleServerHello(ServerHelloPayload hello) {
		connectedToSharedMapServer = hello.accepted();
		if (hello.accepted()) {
			XaeroMapsync_r.LOGGER.info("Shared map handshake accepted: {}", hello.message());
			return;
		}
		XaeroMapsync_r.LOGGER.warn("Shared map handshake rejected: {}", hello.message());
	}

	public static void disconnect() {
		connectedToSharedMapServer = false;
	}

	public static void handleWaypointSnapshot(WaypointSnapshotPayload snapshot) {
		WAYPOINTS.replace(snapshot.waypoints());
		XaeroMapsync_r.LOGGER.info("Received {} public waypoint entries", snapshot.waypoints().size());
	}

	public static void handleWaypointUpsert(PublicWaypoint waypoint) {
		WAYPOINTS.upsert(waypoint);
	}

	public static void handleWaypointDelete(PublicWaypoint waypoint) {
		WAYPOINTS.delete(waypoint);
	}

	public static void handleWaypointError(String message) {
		XaeroMapsync_r.LOGGER.warn("Public waypoint sync error: {}", message);
	}

	public static void handleMapTileIndexSnapshot(MapTileIndexSnapshotPayload payload) {
		MAP_TILES.replace(payload.rootHash(), payload.entries());
		XaeroMapsync_r.LOGGER.info("Received {} map tile index entries rootHash={}", MAP_TILES.totalCount(), Long.toUnsignedString(MAP_TILES.rootHash()));
		for (MapTileIndexEntry entry : MAP_TILES.missingFrom(TILE_DATA, SharedMapConfig.maxTileRequestsPerSnapshot())) {
			SharedMapNetworking.requestTile(entry);
		}
	}

	public static void handleMerkleSnapshot(MapMerkleSnapshotPayload payload) {
		MERKLE.replace(payload.nodes());
		XaeroMapsync_r.LOGGER.info("Received {} map merkle nodes", MERKLE.totalCount());
	}

	public static void handleTileData(TileDataPayload payload) {
		TILE_DATA.put(payload.tile(), payload.revision());
		XaeroMapsync_r.LOGGER.debug("Received tile data {} {} {} revision={}",
				payload.tile().dimension(),
				payload.tile().chunkX(),
				payload.tile().chunkZ(),
				payload.revision());
	}

	public static long knownMapRootHash() {
		return MAP_TILES.rootHash();
	}

	public static boolean connectedToSharedMapServer() {
		return connectedToSharedMapServer;
	}
}
