package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.client.SharedMapClient;
import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileDebugRenderer;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.server.SharedMapServer;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import io.netty.buffer.Unpooled;
import java.util.Collections;
import java.util.Optional;
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
	public static final net.minecraft.resources.ResourceLocation S2C_MAP_INDEX_SNAPSHOT = XaeroMapsync_r.id("s2c_map_index_snapshot");
	public static final net.minecraft.resources.ResourceLocation S2C_MAP_MERKLE_SNAPSHOT = XaeroMapsync_r.id("s2c_map_merkle_snapshot");
	public static final net.minecraft.resources.ResourceLocation S2C_TILE_DATA = XaeroMapsync_r.id("s2c_tile_data");
	public static final net.minecraft.resources.ResourceLocation S2C_WAYPOINT_SNAPSHOT = XaeroMapsync_r.id("s2c_waypoint_snapshot");
	public static final net.minecraft.resources.ResourceLocation S2C_WAYPOINT_UPSERT = XaeroMapsync_r.id("s2c_waypoint_upsert");
	public static final net.minecraft.resources.ResourceLocation S2C_WAYPOINT_DELETE = XaeroMapsync_r.id("s2c_waypoint_delete");
	public static final net.minecraft.resources.ResourceLocation S2C_WAYPOINT_ERROR = XaeroMapsync_r.id("s2c_waypoint_error");

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
				server.execute(() -> sendWaypointSnapshot(player)));
		ServerPlayNetworking.registerGlobalReceiver(C2S_MAP_ROOT_HASH, (server, player, handler, buffer, responseSender) -> {
			MapRootHashPayload payload = MapRootHashPayload.read(buffer);
			server.execute(() -> sendMapIndexIfChanged(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_TILE_REQUEST, (server, player, handler, buffer, responseSender) -> {
			TileRequestPayload payload = TileRequestPayload.read(buffer);
			server.execute(() -> sendTileDataIfAvailable(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_WAYPOINT_CREATE, (server, player, handler, buffer, responseSender) -> {
			WaypointCreatePayload payload = WaypointCreatePayload.read(buffer);
			server.execute(() -> handleWaypointCreate(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_WAYPOINT_UPDATE, (server, player, handler, buffer, responseSender) -> {
			WaypointUpdatePayload payload = WaypointUpdatePayload.read(buffer);
			server.execute(() -> handleWaypointUpdate(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_WAYPOINT_DELETE, (server, player, handler, buffer, responseSender) -> {
			WaypointDeletePayload payload = WaypointDeletePayload.read(buffer);
			server.execute(() -> handleWaypointDelete(player, payload));
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
	}

	@Environment(EnvType.CLIENT)
	public static void requestTile(MapTileIndexEntry entry) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new TileRequestPayload(entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision()).write(buffer);
		ClientPlayNetworking.send(C2S_TILE_REQUEST, buffer);
	}

	@Environment(EnvType.CLIENT)
	private static void sendClientHello() {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		ClientHelloPayload.current().write(buffer);
		ClientPlayNetworking.send(C2S_HELLO, buffer);
		ClientPlayNetworking.send(C2S_WAYPOINT_SNAPSHOT_REQUEST, PacketByteBufs.create());
		FriendlyByteBuf mapRootBuffer = PacketByteBufs.create();
		new MapRootHashPayload(SharedMapClient.knownMapRootHash()).write(mapRootBuffer);
		ClientPlayNetworking.send(C2S_MAP_ROOT_HASH, mapRootBuffer);
	}

	private static void sendWaypointSnapshot(net.minecraft.server.level.ServerPlayer player) {
		WaypointSnapshotPayload payload = new WaypointSnapshotPayload(0L, SharedMapServer.waypoints().snapshot());
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		ServerPlayNetworking.send(player, S2C_WAYPOINT_SNAPSHOT, buffer);
	}

	private static void sendMapIndexIfChanged(net.minecraft.server.level.ServerPlayer player, MapRootHashPayload request) {
		long rootHash = SharedMapServer.mapTiles().rootHash();
		if (rootHash == request.knownRootHash()) {
			return;
		}
		MapTileIndexSnapshotPayload payload = new MapTileIndexSnapshotPayload(rootHash, SharedMapServer.mapTiles().snapshot());
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		ServerPlayNetworking.send(player, S2C_MAP_INDEX_SNAPSHOT, buffer);
		MapMerkleSnapshotPayload merklePayload = new MapMerkleSnapshotPayload(SharedMapServer.mapTiles().merkleSnapshot());
		FriendlyByteBuf merkleBuffer = PacketByteBufs.create();
		merklePayload.write(merkleBuffer);
		if (merkleBuffer.readableBytes() <= SharedMapConfig.maxPacketBytes()) {
			ServerPlayNetworking.send(player, S2C_MAP_MERKLE_SNAPSHOT, merkleBuffer);
		}
	}

	private static void sendTileDataIfAvailable(net.minecraft.server.level.ServerPlayer player, TileRequestPayload request) {
		if (!SharedMapServer.exploredChunks().isExplored(request.dimension(), request.chunkX(), request.chunkZ())) {
			sendWaypointError(player, "Tile is not explored");
			return;
		}
		net.minecraft.server.level.ServerLevel level = player.getServer().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.Registry.DIMENSION_REGISTRY, new net.minecraft.resources.ResourceLocation(request.dimension())));
		if (level == null) {
			sendWaypointError(player, "Tile dimension is not loaded");
			return;
		}
		MapTile tile = MapTileDebugRenderer.renderIfLoaded(level, request.chunkX(), request.chunkZ());
		if (tile == null) {
			sendWaypointError(player, "Tile chunk is not currently loaded");
			return;
		}
		MapTileIndexEntry entry = SharedMapServer.mapTiles().upsert(tile);
		if (entry.revision() <= request.knownRevision()) {
			return;
		}
		TileDataPayload payload = TileDataPayload.fromTile(tile, entry.revision(), SharedMapConfig.compression());
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		if (buffer.readableBytes() > SharedMapConfig.maxPacketBytes()) {
			sendWaypointError(player, "Tile payload exceeds max packet size");
			return;
		}
		if (!SharedMapServer.networkBudget().trySpend(player.getUUID(), buffer.readableBytes())) {
			sendWaypointError(player, "Tile transfer is rate limited");
			return;
		}
		ServerPlayNetworking.send(player, S2C_TILE_DATA, buffer);
	}

	private static void handleWaypointCreate(net.minecraft.server.level.ServerPlayer player, WaypointCreatePayload payload) {
		try {
			PublicWaypoint submitted = payload.waypoint();
			long now = System.currentTimeMillis();
			PublicWaypoint waypoint = new PublicWaypoint(
					submitted.id(),
					player.getUUID(),
					player.getGameProfile().getName(),
					submitted.name(),
					submitted.dimension(),
					submitted.x(),
					submitted.y(),
					submitted.z(),
					submitted.symbol(),
					submitted.color(),
					submitted.category(),
					submitted.visibility(),
					0L,
					false,
					0L,
					0L);
			PublicWaypoint stored = SharedMapServer.waypoints().upsert(waypoint, now);
			broadcastWaypoint(player.getServer(), S2C_WAYPOINT_UPSERT, stored);
		} catch (RuntimeException exception) {
			sendWaypointError(player, exception.getMessage());
		}
	}

	private static void handleWaypointUpdate(net.minecraft.server.level.ServerPlayer player, WaypointUpdatePayload payload) {
		try {
			Optional<PublicWaypoint> current = SharedMapServer.waypoints().find(payload.waypoint().id());
			if (current.isEmpty()) {
				throw new IllegalArgumentException("Waypoint does not exist");
			}
			if (current.get().revision() != payload.knownRevision()) {
				throw new IllegalArgumentException("Waypoint revision conflict");
			}
			if (!canMutate(player, current.get())) {
				throw new IllegalArgumentException("Waypoint permission denied");
			}
			PublicWaypoint submitted = payload.waypoint();
			PublicWaypoint waypoint = new PublicWaypoint(
					submitted.id(),
					current.get().creatorId(),
					current.get().creatorName(),
					submitted.name(),
					submitted.dimension(),
					submitted.x(),
					submitted.y(),
					submitted.z(),
					submitted.symbol(),
					submitted.color(),
					submitted.category(),
					submitted.visibility(),
					current.get().revision(),
					false,
					current.get().createdAtMillis(),
					current.get().updatedAtMillis());
			PublicWaypoint stored = SharedMapServer.waypoints().upsert(waypoint, System.currentTimeMillis());
			broadcastWaypoint(player.getServer(), S2C_WAYPOINT_UPSERT, stored);
		} catch (RuntimeException exception) {
			sendWaypointError(player, exception.getMessage());
		}
	}

	private static void handleWaypointDelete(net.minecraft.server.level.ServerPlayer player, WaypointDeletePayload payload) {
		try {
			Optional<PublicWaypoint> current = SharedMapServer.waypoints().find(payload.waypointId());
			if (current.isEmpty()) {
				throw new IllegalArgumentException("Waypoint does not exist");
			}
			if (current.get().revision() != payload.knownRevision()) {
				throw new IllegalArgumentException("Waypoint revision conflict");
			}
			if (!canMutate(player, current.get())) {
				throw new IllegalArgumentException("Waypoint permission denied");
			}
			PublicWaypoint tombstone = SharedMapServer.waypoints().delete(payload.waypointId(), System.currentTimeMillis());
			if (tombstone != null) {
				broadcastWaypoint(player.getServer(), S2C_WAYPOINT_DELETE, tombstone);
			}
		} catch (RuntimeException exception) {
			sendWaypointError(player, exception.getMessage());
		}
	}

	private static boolean canMutate(net.minecraft.server.level.ServerPlayer player, PublicWaypoint waypoint) {
		return player.hasPermissions(2) || player.getUUID().equals(waypoint.creatorId());
	}

	private static void broadcastWaypoint(net.minecraft.server.MinecraftServer server, net.minecraft.resources.ResourceLocation channel, PublicWaypoint waypoint) {
		WaypointSnapshotPayload payload = new WaypointSnapshotPayload(waypoint.revision(), Collections.singletonList(waypoint));
		for (net.minecraft.server.level.ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.all(server)) {
			if (SharedMapServer.hasAcceptedClient(player.getUUID())) {
				FriendlyByteBuf buffer = PacketByteBufs.create();
				payload.write(buffer);
				ServerPlayNetworking.send(player, channel, buffer);
			}
		}
	}

	private static void sendWaypointError(net.minecraft.server.level.ServerPlayer player, String message) {
		WaypointErrorPayload payload = new WaypointErrorPayload(message == null ? "Waypoint operation failed" : message);
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		ServerPlayNetworking.send(player, S2C_WAYPOINT_ERROR, buffer);
	}
}
