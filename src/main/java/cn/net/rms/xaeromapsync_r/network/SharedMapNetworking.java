package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.client.SharedMapClient;
import cn.net.rms.xaeromapsync_r.client.ClientTransferManager;
import cn.net.rms.xaeromapsync_r.client.SharedMapClientConfig;
import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileDebugRenderer;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.server.SharedMapServer;
import cn.net.rms.xaeromapsync_r.server.access.SharedMapActor;
import cn.net.rms.xaeromapsync_r.server.access.SharedMapActors;
import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import cn.net.rms.xaeromapsync_r.map.MerkleNode;
import cn.net.rms.xaeromapsync_r.map.MerkleNodeAddress;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;

public final class SharedMapNetworking {
	public static final net.minecraft.resources.ResourceLocation C2S_HELLO = XaeroMapsync_r.id("c2s_hello");
	public static final net.minecraft.resources.ResourceLocation S2C_HELLO = XaeroMapsync_r.id("s2c_hello");
	public static final net.minecraft.resources.ResourceLocation C2S_WAYPOINT_CREATE = XaeroMapsync_r.id("c2s_waypoint_create");
	public static final net.minecraft.resources.ResourceLocation C2S_WAYPOINT_UPDATE = XaeroMapsync_r.id("c2s_waypoint_update");
	public static final net.minecraft.resources.ResourceLocation C2S_WAYPOINT_DELETE = XaeroMapsync_r.id("c2s_waypoint_delete");
	public static final net.minecraft.resources.ResourceLocation C2S_WAYPOINT_SNAPSHOT_REQUEST = XaeroMapsync_r.id("c2s_waypoint_snapshot_request");
	public static final net.minecraft.resources.ResourceLocation C2S_MAP_ROOT_HASH = XaeroMapsync_r.id("c2s_map_root_hash");
	public static final net.minecraft.resources.ResourceLocation C2S_TILE_REQUEST = XaeroMapsync_r.id("c2s_tile_request");
	public static final net.minecraft.resources.ResourceLocation C2S_MAP_NODE_REQUEST = XaeroMapsync_r.id("c2s_map_node_request");
	public static final net.minecraft.resources.ResourceLocation C2S_TRANSFER_ACK = XaeroMapsync_r.id("c2s_transfer_ack");
	public static final net.minecraft.resources.ResourceLocation S2C_MAP_INDEX_SNAPSHOT = XaeroMapsync_r.id("s2c_map_index_snapshot");
	public static final net.minecraft.resources.ResourceLocation S2C_MAP_MERKLE_SNAPSHOT = XaeroMapsync_r.id("s2c_map_merkle_snapshot");
	public static final net.minecraft.resources.ResourceLocation S2C_TILE_DATA = XaeroMapsync_r.id("s2c_tile_data");
	public static final net.minecraft.resources.ResourceLocation S2C_TILE_UNAVAILABLE = XaeroMapsync_r.id("s2c_tile_unavailable");
	public static final net.minecraft.resources.ResourceLocation S2C_MAP_NODE_RESPONSE = XaeroMapsync_r.id("s2c_map_node_response");
	public static final net.minecraft.resources.ResourceLocation S2C_TRANSFER_PART = XaeroMapsync_r.id("s2c_transfer_part");
	public static final net.minecraft.resources.ResourceLocation S2C_WAYPOINT_SNAPSHOT = XaeroMapsync_r.id("s2c_waypoint_snapshot");
	public static final net.minecraft.resources.ResourceLocation S2C_WAYPOINT_UPSERT = XaeroMapsync_r.id("s2c_waypoint_upsert");
	public static final net.minecraft.resources.ResourceLocation S2C_WAYPOINT_DELETE = XaeroMapsync_r.id("s2c_waypoint_delete");
	public static final net.minecraft.resources.ResourceLocation S2C_WAYPOINT_ERROR = XaeroMapsync_r.id("s2c_waypoint_error");
	private static final int TRANSFER_TYPE_MAP_NODE_RESPONSE = 1;
	private static final int TRANSFER_TYPE_TILE_DATA = 2;
	@Environment(EnvType.CLIENT)
	public static void tickClientTransfers() { ClientTransfers.MANAGER.tick(System.currentTimeMillis()); }

	private SharedMapNetworking() {
	}

	public static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(C2S_HELLO, (server, player, handler, buffer, responseSender) -> {
			ClientHelloPayload hello = ClientHelloPayload.read(buffer);
			server.execute(() -> {
				ServerHelloPayload response = ServerHelloPayload.from(hello);
				SharedMapServer.recordHandshake(player, hello, response.accepted());
				FriendlyByteBuf responseBuffer = new FriendlyByteBuf(Unpooled.buffer());
				response.write(responseBuffer);
				ServerPlayNetworking.send(player, S2C_HELLO, responseBuffer);
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_WAYPOINT_SNAPSHOT_REQUEST, (server, player, handler, buffer, responseSender) ->
				server.execute(() -> runForAcceptedClient(player, () -> refreshWaypointVisibility(player))));
		ServerPlayNetworking.registerGlobalReceiver(C2S_MAP_ROOT_HASH, (server, player, handler, buffer, responseSender) -> {
			MapRootHashPayload payload = MapRootHashPayload.read(buffer);
			server.execute(() -> runForAcceptedClient(player, () -> sendMapIndexIfChanged(player, payload)));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_TILE_REQUEST, (server, player, handler, buffer, responseSender) -> {
			TileRequestPayload payload = TileRequestPayload.read(buffer);
			server.execute(() -> runForAcceptedClient(player, () -> sendTileDataIfAvailable(player, payload)));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_MAP_NODE_REQUEST, (server, player, handler, buffer, responseSender) -> {
			MapNodeRequestPayload payload = MapNodeRequestPayload.read(buffer);
			server.execute(() -> runForAcceptedClient(player, () -> sendMapNodeResponse(player, payload)));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_TRANSFER_ACK, (server, player, handler, buffer, responseSender) -> {
			TransferAckPayload payload = TransferAckPayload.read(buffer);
			server.execute(() -> runForAcceptedClient(player, () -> {
				try {
					SharedMapServer.transfers().acknowledge(player.getUUID(), payload);
				} catch (RuntimeException exception) {
					XaeroMapsync_r.LOGGER.warn("Rejected invalid transfer acknowledgement from {}", player.getGameProfile().getName(), exception);
				}
			}));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_WAYPOINT_CREATE, (server, player, handler, buffer, responseSender) -> {
			WaypointCreatePayload payload = WaypointCreatePayload.read(buffer);
			server.execute(() -> runForAcceptedClient(player, () -> handleWaypointCreate(player, payload)));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_WAYPOINT_UPDATE, (server, player, handler, buffer, responseSender) -> {
			WaypointUpdatePayload payload = WaypointUpdatePayload.read(buffer);
			server.execute(() -> runForAcceptedClient(player, () -> handleWaypointUpdate(player, payload)));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_WAYPOINT_DELETE, (server, player, handler, buffer, responseSender) -> {
			WaypointDeletePayload payload = WaypointDeletePayload.read(buffer);
			server.execute(() -> runForAcceptedClient(player, () -> handleWaypointDelete(player, payload)));
		});
	}

	@Environment(EnvType.CLIENT)
	public static void registerClientReceivers() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sendClientHello());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SharedMapClient.disconnect());
		ClientPlayNetworking.registerGlobalReceiver(S2C_HELLO, (client, handler, buffer, responseSender) -> {
			ServerHelloPayload hello = ServerHelloPayload.read(buffer);
			client.execute(() -> SharedMapClient.handleServerHello(hello));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_WAYPOINT_SNAPSHOT, (client, handler, buffer, responseSender) -> {
			WaypointSnapshotPayload payload = WaypointSnapshotPayload.read(buffer);
			client.execute(() -> SharedMapClient.handleWaypointSnapshot(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_WAYPOINT_UPSERT, (client, handler, buffer, responseSender) -> {
			WaypointSnapshotPayload payload = WaypointSnapshotPayload.read(buffer);
			client.execute(() -> {
				for (PublicWaypoint waypoint : payload.waypoints()) {
					SharedMapClient.handleWaypointUpsert(waypoint);
				}
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_WAYPOINT_DELETE, (client, handler, buffer, responseSender) -> {
			WaypointSnapshotPayload payload = WaypointSnapshotPayload.read(buffer);
			client.execute(() -> {
				for (PublicWaypoint waypoint : payload.waypoints()) {
					SharedMapClient.handleWaypointDelete(waypoint);
				}
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_WAYPOINT_ERROR, (client, handler, buffer, responseSender) -> {
			WaypointErrorPayload payload = WaypointErrorPayload.read(buffer);
			client.execute(() -> SharedMapClient.handleWaypointError(payload.message()));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_MAP_INDEX_SNAPSHOT, (client, handler, buffer, responseSender) -> {
			MapTileIndexSnapshotPayload payload = MapTileIndexSnapshotPayload.read(buffer);
			client.execute(() -> SharedMapClient.handleMapTileIndexSnapshot(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_MAP_MERKLE_SNAPSHOT, (client, handler, buffer, responseSender) -> {
			MapMerkleSnapshotPayload payload = MapMerkleSnapshotPayload.read(buffer);
			client.execute(() -> SharedMapClient.handleMerkleSnapshot(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_TILE_DATA, (client, handler, buffer, responseSender) -> {
			TileDataPayload payload = TileDataPayload.read(buffer);
			client.execute(() -> SharedMapClient.handleTileData(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_TILE_UNAVAILABLE, (client, handler, buffer, responseSender) -> {
			TileUnavailablePayload payload = TileUnavailablePayload.read(buffer);
			client.execute(() -> SharedMapClient.handleTileUnavailable(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_MAP_NODE_RESPONSE, (client, handler, buffer, responseSender) -> {
			MapNodeResponsePayload payload = MapNodeResponsePayload.read(buffer);
			client.execute(() -> SharedMapClient.handleMapNodeResponse(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_TRANSFER_PART, (client, handler, buffer, responseSender) -> {
			TransferPartPayload payload = TransferPartPayload.read(buffer);
			client.execute(() -> ClientTransfers.MANAGER.accept(payload));
		});
	}

	@Environment(EnvType.CLIENT)
	public static void requestTile(MapTileIndexEntry entry) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new TileRequestPayload(entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision()).write(buffer);
		ClientPlayNetworking.send(C2S_TILE_REQUEST, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void requestMapNodes(Collection<MerkleNodeAddress> nodes) {
		if (nodes.isEmpty()) {
			return;
		}
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new MapNodeRequestPayload(nodes).write(buffer);
		ClientPlayNetworking.send(C2S_MAP_NODE_REQUEST, buffer);
	}

	public static void sendTransferPart(net.minecraft.server.level.ServerPlayer player, TransferPartPayload payload) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		ServerPlayNetworking.send(player, S2C_TRANSFER_PART, buffer);
	}

	@Environment(EnvType.CLIENT)
	private static void sendClientHello() {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		ClientHelloPayload.current().write(buffer);
		ClientPlayNetworking.send(C2S_HELLO, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void requestEnabledSnapshots() {
		if (SharedMapClientConfig.get().publicWaypointsEnabled()) requestWaypointSnapshot();
		if (SharedMapClientConfig.get().mapSyncEnabled() && SharedMapClient.mapSyncAvailable()) requestMapSync();
	}

	@Environment(EnvType.CLIENT)
	public static void createWaypoint(PublicWaypoint waypoint) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new WaypointCreatePayload(waypoint).write(buffer);
		ClientPlayNetworking.send(C2S_WAYPOINT_CREATE, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void updateWaypoint(PublicWaypoint waypoint) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new WaypointUpdatePayload(waypoint, waypoint.revision()).write(buffer);
		ClientPlayNetworking.send(C2S_WAYPOINT_UPDATE, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void deleteWaypoint(PublicWaypoint waypoint) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new WaypointDeletePayload(waypoint.id(), waypoint.revision()).write(buffer);
		ClientPlayNetworking.send(C2S_WAYPOINT_DELETE, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void requestWaypointSnapshot() {
		ClientPlayNetworking.send(C2S_WAYPOINT_SNAPSHOT_REQUEST, PacketByteBufs.create());
	}

	@Environment(EnvType.CLIENT)
	public static void requestMapSync() {
		if (!SharedMapClient.mapSyncAvailable()) return;
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		if (minecraft.level == null) return;
		String dimension = minecraft.level.dimension().location().toString();
		FriendlyByteBuf mapRootBuffer = PacketByteBufs.create();
		new MapRootHashPayload(dimension, SharedMapClient.knownMapRootHash(dimension)).write(mapRootBuffer);
		ClientPlayNetworking.send(C2S_MAP_ROOT_HASH, mapRootBuffer);
	}

	public static void refreshWaypointVisibility(net.minecraft.server.level.ServerPlayer player) {
		SharedMapActor actor = SharedMapActors.from(player);
		List<PublicWaypoint> visible = new ArrayList<>();
		for (PublicWaypoint waypoint : SharedMapServer.waypoints().snapshot()) {
			if (SharedMapServer.permissions().canView(actor, waypoint)) {
				visible.add(waypoint);
			}
		}
		WaypointSnapshotPayload payload = new WaypointSnapshotPayload(0L, visible);
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		ServerPlayNetworking.send(player, S2C_WAYPOINT_SNAPSHOT, buffer);
	}

	private static void sendMapIndexIfChanged(net.minecraft.server.level.ServerPlayer player, MapRootHashPayload request) {
		long rootHash = SharedMapServer.mapTiles().rootHash(request.dimension());
		if (rootHash == request.knownRootHash()) {
			return;
		}
		List<MerkleNode> roots = SharedMapServer.mapTiles().merkleRoots(request.dimension());
		sendMapNodePayload(player, request.dimension(), rootHash, roots, leafEntries(roots));
	}

	private static void sendMapNodeResponse(net.minecraft.server.level.ServerPlayer player, MapNodeRequestPayload request) {
		try {
			List<MerkleNode> children = new ArrayList<>();
			String dimension = request.nodes().get(0).dimension();
			for (MerkleNodeAddress node : request.nodes()) {
				if (!dimension.equals(node.dimension())) throw new IllegalArgumentException("Mixed-dimension Merkle request");
				children.addAll(SharedMapServer.mapTiles().merkleChildren(node.dimension(), node.level(), node.nodeX(), node.nodeZ()));
			}
			sendMapNodePayload(player, dimension, SharedMapServer.mapTiles().rootHash(dimension), children, leafEntries(children));
		} catch (RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Rejected invalid Merkle request from {}", player.getGameProfile().getName(), exception);
			sendWaypointError(player, "Invalid map node request");
		}
	}

	private static List<MapTileIndexEntry> leafEntries(Collection<MerkleNode> nodes) {
		List<MapTileIndexEntry> entries = new ArrayList<>();
		for (MerkleNode node : nodes) {
			if (node.level() == 0) {
				SharedMapServer.mapTiles().find(node.dimension(), node.nodeX(), node.nodeZ()).ifPresent(entries::add);
			}
		}
		return entries;
	}

	private static void sendMapNodePayload(net.minecraft.server.level.ServerPlayer player, String dimension, long rootHash,
			Collection<MerkleNode> nodes,
			Collection<MapTileIndexEntry> entries) {
		MapNodeResponsePayload payload = new MapNodeResponsePayload(dimension, rootHash, nodes, entries);
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		if (buffer.readableBytes() > SharedMapConfig.maxPacketBytes()) {
			byte[] envelope = new byte[buffer.readableBytes() + 1];
			envelope[0] = TRANSFER_TYPE_MAP_NODE_RESPONSE;
			buffer.readBytes(envelope, 1, buffer.readableBytes());
			startTransfer(player, envelope);
			return;
		}
		if (!SharedMapServer.networkBudget().trySpend(player.getUUID(), buffer.readableBytes())) {
			byte[] envelope = new byte[buffer.readableBytes() + 1];
			envelope[0] = TRANSFER_TYPE_MAP_NODE_RESPONSE;
			buffer.readBytes(envelope, 1, buffer.readableBytes());
			startTransfer(player, envelope);
			return;
		}
		ServerPlayNetworking.send(player, S2C_MAP_NODE_RESPONSE, buffer);
	}

	private static void sendTileDataIfAvailable(net.minecraft.server.level.ServerPlayer player, TileRequestPayload request) {
		MapTile tile = SharedMapServer.tileData().find(request.dimension(), request.chunkX(), request.chunkZ()).orElse(null);
		if (tile == null) {
			if (!SharedMapServer.exploredChunks().isExplored(request.dimension(), request.chunkX(), request.chunkZ())) {
				sendTileUnavailable(player, request, "Tile is not explored");
				return;
			}
			net.minecraft.server.level.ServerLevel level = player.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
					net.minecraft.core.Registry.DIMENSION_REGISTRY, new net.minecraft.resources.ResourceLocation(request.dimension())));
			if (level != null) tile = MapTileDebugRenderer.renderIfLoaded(level, request.chunkX(), request.chunkZ());
			if (tile == null) {
				sendTileUnavailable(player, request, "Tile is deferred until its chunk is naturally loaded");
				return;
			}
			SharedMapServer.tileData().put(tile);
		}
		MapTileIndexEntry entry = SharedMapServer.mapTiles().upsert(tile);
		// An explicit request means the client has the index entry but not the tile body.
		// The index revision is therefore not proof that the body was already applied.
		TileDataPayload payload = TileDataPayload.fromTile(tile, entry.revision(), SharedMapConfig.compression());
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		if (buffer.readableBytes() > SharedMapConfig.maxPacketBytes()
				|| !SharedMapServer.networkBudget().trySpend(player.getUUID(), buffer.readableBytes())) {
			byte[] envelope = new byte[buffer.readableBytes() + 1];
			envelope[0] = TRANSFER_TYPE_TILE_DATA;
			buffer.readBytes(envelope, 1, buffer.readableBytes());
			startTransfer(player, envelope);
			return;
		}
		ServerPlayNetworking.send(player, S2C_TILE_DATA, buffer);
	}

	private static void sendTileUnavailable(net.minecraft.server.level.ServerPlayer player, TileRequestPayload request,
			String reason) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new TileUnavailablePayload(request.dimension(), request.chunkX(), request.chunkZ(), reason).write(buffer);
		ServerPlayNetworking.send(player, S2C_TILE_UNAVAILABLE, buffer);
	}

	private static void handleWaypointCreate(net.minecraft.server.level.ServerPlayer player, WaypointCreatePayload payload) {
		SharedMapActor actor = SharedMapActors.from(player);
		try {
			PublicWaypoint submitted = payload.waypoint();
			if (SharedMapServer.waypoints().find(submitted.id()).isPresent()) {
				throw new IllegalArgumentException("Waypoint id already exists");
			}
			PublicWaypoint waypoint = SharedMapServer.permissions().prepareCreate(
					actor,
					submitted,
					SharedMapServer.waypoints().activeCount(),
					SharedMapServer.waypoints().activeCount(player.getUUID()));
			long now = System.currentTimeMillis();
			PublicWaypoint stored = SharedMapServer.waypoints().upsert(waypoint, now);
			auditWaypoint(actor, "waypoint.create", true, stored, "revision=" + stored.revision());
			broadcastWaypointUpsert(player.getServer(), null, stored);
		} catch (RuntimeException exception) {
			auditWaypoint(actor, "waypoint.create", false, payload.waypoint(), exception.getMessage());
			sendWaypointError(player, exception.getMessage());
		}
	}

	private static void handleWaypointUpdate(net.minecraft.server.level.ServerPlayer player, WaypointUpdatePayload payload) {
		SharedMapActor actor = SharedMapActors.from(player);
		try {
			Optional<PublicWaypoint> current = SharedMapServer.waypoints().find(payload.waypoint().id());
			if (current.isEmpty()) {
				throw new IllegalArgumentException("Waypoint does not exist");
			}
			if (current.get().revision() != payload.knownRevision()) {
				throw new IllegalArgumentException("Waypoint revision conflict");
			}
			PublicWaypoint submitted = payload.waypoint();
			PublicWaypoint waypoint = SharedMapServer.permissions().prepareUpdate(actor, current.get(), submitted);
			PublicWaypoint stored = SharedMapServer.waypoints().upsert(waypoint, System.currentTimeMillis());
			auditWaypoint(actor, "waypoint.update", true, stored, "revision=" + stored.revision());
			broadcastWaypointUpsert(player.getServer(), current.get(), stored);
		} catch (RuntimeException exception) {
			auditWaypoint(actor, "waypoint.update", false, payload.waypoint(), exception.getMessage());
			sendWaypointError(player, exception.getMessage());
		}
	}

	private static void handleWaypointDelete(net.minecraft.server.level.ServerPlayer player, WaypointDeletePayload payload) {
		SharedMapActor actor = SharedMapActors.from(player);
		try {
			Optional<PublicWaypoint> current = SharedMapServer.waypoints().find(payload.waypointId());
			if (current.isEmpty()) {
				throw new IllegalArgumentException("Waypoint does not exist");
			}
			if (current.get().revision() != payload.knownRevision()) {
				throw new IllegalArgumentException("Waypoint revision conflict");
			}
			SharedMapServer.permissions().validateDelete(actor, current.get());
			PublicWaypoint tombstone = SharedMapServer.waypoints().delete(payload.waypointId(), System.currentTimeMillis());
			if (tombstone != null) {
				auditWaypoint(actor, "waypoint.delete", true, tombstone, "revision=" + tombstone.revision());
				broadcastWaypointDelete(player.getServer(), current.get(), tombstone);
			}
		} catch (RuntimeException exception) {
			SharedMapServer.access().audit().record(actor, "waypoint.delete", false, null, payload.waypointId(), exception.getMessage());
			sendWaypointError(player, exception.getMessage());
		}
	}

	private static void broadcastWaypointUpsert(net.minecraft.server.MinecraftServer server, PublicWaypoint previous, PublicWaypoint waypoint) {
		for (net.minecraft.server.level.ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.all(server)) {
			if (!SharedMapServer.hasAcceptedClient(player.getUUID())) {
				continue;
			}
			SharedMapActor recipient = SharedMapActors.from(player);
			if (SharedMapServer.permissions().canView(recipient, waypoint)) {
				sendWaypoint(player, S2C_WAYPOINT_UPSERT, waypoint);
			} else if (previous != null && SharedMapServer.permissions().canView(recipient, previous)) {
				sendWaypoint(player, S2C_WAYPOINT_DELETE, waypoint.tombstone(waypoint.revision(), waypoint.updatedAtMillis()));
			}
		}
	}

	public static void broadcastWaypointDelete(net.minecraft.server.MinecraftServer server, PublicWaypoint previous, PublicWaypoint tombstone) {
		for (net.minecraft.server.level.ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.all(server)) {
			if (SharedMapServer.hasAcceptedClient(player.getUUID())
					&& SharedMapServer.permissions().canView(SharedMapActors.from(player), previous)) {
				sendWaypoint(player, S2C_WAYPOINT_DELETE, tombstone);
			}
		}
	}

	private static void sendWaypoint(net.minecraft.server.level.ServerPlayer player, net.minecraft.resources.ResourceLocation channel, PublicWaypoint waypoint) {
		WaypointSnapshotPayload payload = new WaypointSnapshotPayload(waypoint.revision(), List.of(waypoint));
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		ServerPlayNetworking.send(player, channel, buffer);
	}

	private static void auditWaypoint(SharedMapActor actor, String action, boolean success, PublicWaypoint waypoint, String detail) {
		RegionKey region = null;
		try {
			region = SharedMapServer.permissions().regionOf(waypoint);
		} catch (RuntimeException ignored) {
			// Invalid coordinates are already represented by the failed audit detail.
		}
		SharedMapServer.access().audit().record(actor, action, success, region, waypoint.id(), detail);
	}

	private static void sendWaypointError(net.minecraft.server.level.ServerPlayer player, String message) {
		WaypointErrorPayload payload = new WaypointErrorPayload(message == null ? "Waypoint operation failed" : message);
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		ServerPlayNetworking.send(player, S2C_WAYPOINT_ERROR, buffer);
	}

	private static void runForAcceptedClient(net.minecraft.server.level.ServerPlayer player, Runnable action) {
		if (!SharedMapServer.hasAcceptedClient(player.getUUID())) {
			XaeroMapsync_r.LOGGER.warn("Ignored shared map packet before accepted handshake from {}", player.getGameProfile().getName());
			return;
		}
		action.run();
	}

	private static void startTransfer(net.minecraft.server.level.ServerPlayer player, byte[] envelope) {
		try {
			SharedMapServer.transfers().start(player, envelope);
		} catch (IllegalStateException exception) {
			XaeroMapsync_r.LOGGER.warn("Rejected excess fragmented transfer for {}", player.getGameProfile().getName());
			sendWaypointError(player, "Too many active map transfers");
		}
	}

	@Environment(EnvType.CLIENT)
	private static final class ClientTransfers {
		private static final ClientTransferManager MANAGER = new ClientTransferManager(
				ClientTransfers::sendAck, ClientTransfers::handleCompleted);

		private static void sendAck(TransferAckPayload payload) {
			FriendlyByteBuf buffer = PacketByteBufs.create();
			payload.write(buffer);
			ClientPlayNetworking.send(C2S_TRANSFER_ACK, buffer);
		}

		private static void handleCompleted(byte[] data) {
			if (data.length < 1) throw new IllegalArgumentException("Transfer envelope is empty");
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
			int type = buffer.readUnsignedByte();
			if (type == TRANSFER_TYPE_MAP_NODE_RESPONSE) {
				SharedMapClient.handleMapNodeResponse(MapNodeResponsePayload.read(buffer));
				return;
			}
			if (type == TRANSFER_TYPE_TILE_DATA) {
				SharedMapClient.handleTileData(TileDataPayload.read(buffer));
				return;
			}
			throw new IllegalArgumentException("Unknown transfer envelope type: " + type);
		}
	}
}
