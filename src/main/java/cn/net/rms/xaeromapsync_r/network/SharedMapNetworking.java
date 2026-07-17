package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.client.SharedMapClient;
import cn.net.rms.xaeromapsync_r.client.ClientTransferManager;
import cn.net.rms.xaeromapsync_r.client.SharedMapClientConfig;
import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.map.MapPatch;
import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import cn.net.rms.xaeromapsync_r.server.SharedMapServer;
import cn.net.rms.xaeromapsync_r.server.access.SharedMapActor;
import cn.net.rms.xaeromapsync_r.server.access.SharedMapActors;
import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import cn.net.rms.xaeromapsync_r.map.MerkleNode;
import cn.net.rms.xaeromapsync_r.map.MerkleNodeAddress;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;

public final class SharedMapNetworking {
	enum LocalTileMergeDecision {
		ACCEPT,
		DUPLICATE,
		KEEP_SERVER
	}
	public static final net.minecraft.resources.ResourceLocation C2S_HELLO = XaeroMapsync_r.id("c2s_hello");
	public static final net.minecraft.resources.ResourceLocation S2C_HELLO = XaeroMapsync_r.id("s2c_hello");
	public static final net.minecraft.resources.ResourceLocation C2S_WAYPOINT_CREATE = XaeroMapsync_r.id("c2s_waypoint_create");
	public static final net.minecraft.resources.ResourceLocation C2S_WAYPOINT_UPDATE = XaeroMapsync_r.id("c2s_waypoint_update");
	public static final net.minecraft.resources.ResourceLocation C2S_WAYPOINT_DELETE = XaeroMapsync_r.id("c2s_waypoint_delete");
	public static final net.minecraft.resources.ResourceLocation C2S_WAYPOINT_SNAPSHOT_REQUEST = XaeroMapsync_r.id("c2s_waypoint_snapshot_request");
	public static final net.minecraft.resources.ResourceLocation C2S_MAP_ROOT_HASH = XaeroMapsync_r.id("c2s_map_root_hash");
	public static final net.minecraft.resources.ResourceLocation C2S_TILE_REQUEST = XaeroMapsync_r.id("c2s_tile_request");
	public static final net.minecraft.resources.ResourceLocation C2S_TILE_BATCH_REQUEST = XaeroMapsync_r.id("c2s_tile_batch_request");
	public static final net.minecraft.resources.ResourceLocation C2S_MAP_NODE_REQUEST = XaeroMapsync_r.id("c2s_map_node_request");
	public static final net.minecraft.resources.ResourceLocation C2S_LOCAL_TILE_READY = XaeroMapsync_r.id("c2s_local_tile_ready");
	public static final net.minecraft.resources.ResourceLocation C2S_LOCAL_TILE_DATA = XaeroMapsync_r.id("c2s_local_tile_data");
	public static final net.minecraft.resources.ResourceLocation C2S_TRANSFER_ACK = XaeroMapsync_r.id("c2s_transfer_ack");
	public static final net.minecraft.resources.ResourceLocation C2S_TRANSFER_NACK = XaeroMapsync_r.id("c2s_transfer_nack");
	public static final net.minecraft.resources.ResourceLocation C2S_PATCH_MANIFEST_REQUEST = XaeroMapsync_r.id("c2s_patch_manifest_request");
	public static final net.minecraft.resources.ResourceLocation C2S_PATCH_REQUEST = XaeroMapsync_r.id("c2s_patch_request");
	public static final net.minecraft.resources.ResourceLocation C2S_GAP_RECOVERY_REQUEST = XaeroMapsync_r.id("c2s_gap_recovery_request");
	public static final net.minecraft.resources.ResourceLocation S2C_MAP_INDEX_SNAPSHOT = XaeroMapsync_r.id("s2c_map_index_snapshot");
	public static final net.minecraft.resources.ResourceLocation S2C_MAP_MERKLE_SNAPSHOT = XaeroMapsync_r.id("s2c_map_merkle_snapshot");
	public static final net.minecraft.resources.ResourceLocation S2C_TILE_DATA = XaeroMapsync_r.id("s2c_tile_data");
	public static final net.minecraft.resources.ResourceLocation S2C_TILE_DATA_BATCH = XaeroMapsync_r.id("s2c_tile_data_batch");
	public static final net.minecraft.resources.ResourceLocation S2C_TILE_UNAVAILABLE = XaeroMapsync_r.id("s2c_tile_unavailable");
	public static final net.minecraft.resources.ResourceLocation S2C_MAP_NODE_RESPONSE = XaeroMapsync_r.id("s2c_map_node_response");
	public static final net.minecraft.resources.ResourceLocation S2C_TRANSFER_PART = XaeroMapsync_r.id("s2c_transfer_part");
	public static final net.minecraft.resources.ResourceLocation S2C_PATCH_MANIFEST_PAGE = XaeroMapsync_r.id("s2c_patch_manifest_page");
	public static final net.minecraft.resources.ResourceLocation S2C_PATCH_DATA = XaeroMapsync_r.id("s2c_patch_data");
	public static final net.minecraft.resources.ResourceLocation S2C_PATCH_UNAVAILABLE = XaeroMapsync_r.id("s2c_patch_unavailable");
	public static final net.minecraft.resources.ResourceLocation S2C_GAP_RECOVERY_PROBE = XaeroMapsync_r.id("s2c_gap_recovery_probe");
	public static final net.minecraft.resources.ResourceLocation S2C_WAYPOINT_SNAPSHOT = XaeroMapsync_r.id("s2c_waypoint_snapshot");
	public static final net.minecraft.resources.ResourceLocation S2C_WAYPOINT_UPSERT = XaeroMapsync_r.id("s2c_waypoint_upsert");
	public static final net.minecraft.resources.ResourceLocation S2C_WAYPOINT_DELETE = XaeroMapsync_r.id("s2c_waypoint_delete");
	public static final net.minecraft.resources.ResourceLocation S2C_WAYPOINT_ERROR = XaeroMapsync_r.id("s2c_waypoint_error");
	private static final int TRANSFER_TYPE_MAP_NODE_RESPONSE = 1;
	private static final int TRANSFER_TYPE_TILE_DATA = 2;
	private static final int TRANSFER_TYPE_TILE_DATA_BATCH = 3;
	private static final int TRANSFER_TYPE_PATCH_MANIFEST_PAGE = 4;
	private static final int TRANSFER_TYPE_PATCH_DATA = 5;
	private static final int LOCAL_TILE_HINT_DISTANCE_GRACE_CHUNKS = 1;
	private static final int TILE_BATCH_ENVELOPE_BYTES = 16;
	private static final int TILE_BATCH_TILE_ENVELOPE_BYTES = 96;
	private static final int MAX_GAP_RECOVERY_PEERS = 8;
	private static final LocalTileReadyHintLimiter LOCAL_TILE_HINT_LIMITER = new LocalTileReadyHintLimiter();
	private static final ClientTileUploadLimiter CLIENT_TILE_UPLOAD_LIMITER = new ClientTileUploadLimiter();
	private static final GapRecoveryRequestLimiter GAP_RECOVERY_REQUEST_LIMITER = new GapRecoveryRequestLimiter();
	private static final java.util.concurrent.ConcurrentHashMap<String, Long> LAST_LOGGED_WAVE_EPOCH =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static final AtomicInteger TILE_BATCH_WORKER_IDS = new AtomicInteger();
	private static final ThreadPoolExecutor TILE_BATCH_WORKERS = new ThreadPoolExecutor(2, 2, 0L,
			TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), runnable -> {
				Thread thread = new Thread(runnable,
						"xaero-mapsync-tile-batch-" + TILE_BATCH_WORKER_IDS.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			}, new ThreadPoolExecutor.AbortPolicy());
	private static final ThreadPoolExecutor CLIENT_UPLOAD_WORKERS = boundedDaemonWorkers(
			"xaero-mapsync-client-upload-", 2, 256);
	private static final ThreadPoolExecutor CLIENT_RECOVERY_UPLOAD_WORKERS = boundedDaemonWorkers(
			"xaero-mapsync-gap-recovery-", 1, 32);
	@Environment(EnvType.CLIENT)
	public static void tickClientTransfers() { ClientTransfers.MANAGER.tick(System.currentTimeMillis()); }

	private SharedMapNetworking() {
	}

	public static void registerServerReceivers() {
		XaeroMapsync_r.LOGGER.info("Registering shared map server packet receivers");
		ServerPlayNetworking.registerGlobalReceiver(C2S_HELLO, (server, player, handler, buffer, responseSender) -> {
			ClientHelloPayload hello = ClientHelloPayload.read(buffer);
			server.execute(() -> {
				ServerHelloPayload response = ServerHelloPayload.from(hello);
				XaeroMapsync_r.LOGGER.debug(
						"Handling client hello player={} protocol={} mapFormat={} xaeroAdapter={} compression={} maxPacketBytes={} accepted={}",
						player.getGameProfile().getName(), hello.protocolVersion(), hello.mapFormatVersion(),
						hello.xaeroAdapterVersion(), hello.compression(), hello.maxPacketBytes(),
						response.accepted());
				SharedMapServer.recordHandshake(player, hello, response.accepted());
				FriendlyByteBuf responseBuffer = new FriendlyByteBuf(Unpooled.buffer());
				response.write(responseBuffer);
				ServerPlayNetworking.send(player, S2C_HELLO, responseBuffer);
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_WAYPOINT_SNAPSHOT_REQUEST, (server, player, handler, buffer, responseSender) ->
				server.execute(() -> runForAcceptedClient(player, () -> refreshWaypointVisibility(player))));
		ServerPlayNetworking.registerGlobalReceiver(C2S_PATCH_MANIFEST_REQUEST, (server, player, handler, buffer, responseSender) -> {
			PatchManifestRequestPayload payload = PatchManifestRequestPayload.read(buffer);
			server.execute(() -> runForAcceptedClient(player, () -> sendPatchManifestPage(player, payload)));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_PATCH_REQUEST, (server, player, handler, buffer, responseSender) -> {
			PatchRequestPayload payload = PatchRequestPayload.read(buffer);
			server.execute(() -> runForAcceptedClient(player, () -> sendPatchDataIfAvailable(player, payload)));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_GAP_RECOVERY_REQUEST, (server, player, handler, buffer, responseSender) -> {
			TileBatchRequestPayload payload = TileBatchRequestPayload.read(buffer);
			server.execute(() -> runForAcceptedClient(player, () -> handleGapRecoveryRequest(player, payload)));
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_LOCAL_TILE_READY, (server, player, handler, buffer, responseSender) -> {
			try {
				LocalTileReadyPayload payload = LocalTileReadyPayload.read(buffer);
				server.execute(() -> runForAcceptedClient(player, () -> handleLocalTileReady(player, payload)));
			} catch (RuntimeException exception) {
				XaeroMapsync_r.LOGGER.warn("map_sync event=client_tile_hint_rejected player={} reason=malformed",
						player.getGameProfile().getName(), exception);
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(C2S_LOCAL_TILE_DATA, (server, player, handler, buffer, responseSender) -> {
			int packetBytes = buffer.readableBytes();
			if (!SharedMapServer.hasAcceptedClient(player.getUUID())) {
				XaeroMapsync_r.LOGGER.warn(
						"map_sync event=client_tile_upload_rejected player={} bytes={} reason=handshake_required",
						player.getGameProfile().getName(), packetBytes);
				return;
			}
			if (packetBytes > SharedMapConfig.maxPacketBytes()) {
				XaeroMapsync_r.LOGGER.warn(
						"map_sync event=client_tile_upload_rejected player={} bytes={} max_bytes={} reason=oversized",
						player.getGameProfile().getName(), packetBytes, SharedMapConfig.maxPacketBytes());
				return;
			}
			if (!CLIENT_TILE_UPLOAD_LIMITER.acquire(player.getUUID(), packetBytes, System.currentTimeMillis())) {
				XaeroMapsync_r.LOGGER.debug(
						"map_sync event=client_tile_upload_rejected player={} bytes={} reason=rate_limited",
						player.getGameProfile().getName(), packetBytes);
				return;
			}
			try {
				TileDataPayload payload = TileDataPayload.read(buffer);
				server.execute(() -> runForAcceptedClient(player, () -> handleLocalTileData(player, payload)));
			} catch (RuntimeException exception) {
				XaeroMapsync_r.LOGGER.warn(
						"map_sync event=client_tile_upload_rejected player={} bytes={} reason=malformed",
						player.getGameProfile().getName(), packetBytes, exception);
			}
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
		ServerPlayNetworking.registerGlobalReceiver(C2S_TRANSFER_NACK, (server, player, handler, buffer, responseSender) -> {
			TransferNackPayload payload = TransferNackPayload.read(buffer);
			server.execute(() -> runForAcceptedClient(player,
					() -> SharedMapServer.transfers().negativeAcknowledge(player.getUUID(), payload)));
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
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			XaeroMapsync_r.LOGGER.debug("Clearing networking limiters for disconnected player {}",
					handler.player.getGameProfile().getName());
			LOCAL_TILE_HINT_LIMITER.remove(handler.player.getUUID());
			CLIENT_TILE_UPLOAD_LIMITER.remove(handler.player.getUUID());
			GAP_RECOVERY_REQUEST_LIMITER.remove(handler.player.getUUID());
		});
	}

	@Environment(EnvType.CLIENT)
	public static void registerClientReceivers() {
		XaeroMapsync_r.LOGGER.info("Registering shared map client packet receivers");
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sendClientHello());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			XaeroMapsync_r.LOGGER.info("Shared map client network disconnected; clearing transfer manager");
			try {
				ClientTransfers.MANAGER.clear();
			} catch (RuntimeException exception) {
				XaeroMapsync_r.LOGGER.warn("Failed to clear client transfers during disconnect", exception);
			} finally {
				try {
					SharedMapClient.disconnect();
				} catch (RuntimeException exception) {
					XaeroMapsync_r.LOGGER.warn("Failed to clear shared map client state during disconnect", exception);
				}
			}
		});
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
		ClientPlayNetworking.registerGlobalReceiver(S2C_TILE_DATA, (client, handler, buffer, responseSender) -> {
			TileDataPayload payload = TileDataPayload.read(buffer);
			client.execute(() -> SharedMapClient.handleTileData(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_TILE_DATA_BATCH, (client, handler, buffer, responseSender) -> {
			TileBatchDataPayload payload = TileBatchDataPayload.read(buffer);
			client.execute(() -> SharedMapClient.handleTileDataBatch(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_TILE_UNAVAILABLE, (client, handler, buffer, responseSender) -> {
			TileUnavailablePayload payload = TileUnavailablePayload.read(buffer);
			client.execute(() -> SharedMapClient.handleTileUnavailable(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_PATCH_MANIFEST_PAGE, (client, handler, buffer, responseSender) -> {
			PatchManifestPagePayload payload = PatchManifestPagePayload.read(buffer);
			client.execute(() -> SharedMapClient.handlePatchManifestPage(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_PATCH_DATA, (client, handler, buffer, responseSender) -> {
			PatchDataPayload payload = PatchDataPayload.read(buffer);
			client.execute(() -> SharedMapClient.handlePatchData(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_PATCH_UNAVAILABLE, (client, handler, buffer, responseSender) -> {
			PatchUnavailablePayload payload = PatchUnavailablePayload.read(buffer);
			client.execute(() -> SharedMapClient.handlePatchUnavailable(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_GAP_RECOVERY_PROBE, (client, handler, buffer, responseSender) -> {
			TileBatchRequestPayload payload = TileBatchRequestPayload.read(buffer);
			client.execute(() -> SharedMapClient.handleGapRecoveryProbe(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(S2C_TRANSFER_PART, (client, handler, buffer, responseSender) -> {
			TransferPartPayload payload = TransferPartPayload.read(buffer);
			client.execute(() -> {
				try {
					ClientTransfers.MANAGER.accept(payload);
				} catch (RuntimeException exception) {
					XaeroMapsync_r.LOGGER.warn("map_sync event=client_transfer_part_rejected transfer_id={} part_index={}",
							payload.transferId(), payload.partIndex(), exception);
				}
			});
		});
	}

	@Environment(EnvType.CLIENT)
	public static void requestTile(MapTileIndexEntry entry) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new TileRequestPayload(entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision()).write(buffer);
		ClientPlayNetworking.send(C2S_TILE_REQUEST, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void requestTiles(Collection<MapTileIndexEntry> entries) {
		if (entries.isEmpty()) return;
		List<TileRequestPayload> requests = entries.stream()
				.map(entry -> new TileRequestPayload(entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision()))
				.toList();
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new TileBatchRequestPayload(requests).write(buffer);
		XaeroMapsync_r.LOGGER.debug("Sending tile batch request count={} first={} {} {} revision={}",
				requests.size(), requests.get(0).dimension(), requests.get(0).chunkX(),
				requests.get(0).chunkZ(), requests.get(0).knownRevision());
		ClientPlayNetworking.send(C2S_TILE_BATCH_REQUEST, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void requestMapNodes(Collection<MerkleNodeAddress> nodes) {
		requestMapNodes(0L, 0L, nodes);
	}

	@Environment(EnvType.CLIENT)
	public static void requestMapNodes(long syncId, long requestId, Collection<MerkleNodeAddress> nodes) {
		if (nodes.isEmpty()) {
			return;
		}
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new MapNodeRequestPayload(syncId, requestId, nodes).write(buffer);
		XaeroMapsync_r.LOGGER.debug("Sending map node request syncId={} requestId={} count={} first={}",
				syncId, requestId, nodes.size(), nodes.iterator().next());
		ClientPlayNetworking.send(C2S_MAP_NODE_REQUEST, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void sendLocalTileReady(String dimension, int chunkX, int chunkZ, long contentHash) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new LocalTileReadyPayload(dimension, chunkX, chunkZ, contentHash).write(buffer);
		XaeroMapsync_r.LOGGER.debug("Sending local tile ready hint dimension={} chunk={} {} hash={}",
				dimension, chunkX, chunkZ, Long.toUnsignedString(contentHash));
		ClientPlayNetworking.send(C2S_LOCAL_TILE_READY, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static boolean sendLocalTileData(MapTile tile, Consumer<Boolean> completion) {
		return queueLocalTileData(CLIENT_UPLOAD_WORKERS, tile, completion);
	}

	@Environment(EnvType.CLIENT)
	public static boolean sendRecoveryTileData(MapTile tile, Consumer<Boolean> completion) {
		return queueLocalTileData(CLIENT_RECOVERY_UPLOAD_WORKERS, tile, completion);
	}

	@Environment(EnvType.CLIENT)
	private static boolean queueLocalTileData(ThreadPoolExecutor workers, MapTile tile, Consumer<Boolean> completion) {
		if (completion == null) throw new IllegalArgumentException("Local tile upload completion is required");
		try {
			workers.execute(() -> {
				try {
					TileDataPayload payload = TileDataPayload.fromTile(tile, 0L, SharedMapConfig.compression());
					int payloadBytes = payload.surfacePayload().length;
					executeLocalTileUploadCompletion(() -> {
						try {
							if (payloadBytes + 512 > SharedMapConfig.maxPacketBytes()) {
								sendLocalTileReady(tile.dimension(), tile.chunkX(), tile.chunkZ(), tile.contentHash());
							} else {
								FriendlyByteBuf buffer = PacketByteBufs.create();
								payload.write(buffer);
								ClientPlayNetworking.send(C2S_LOCAL_TILE_DATA, buffer);
							}
							completion.accept(true);
						} catch (RuntimeException exception) {
							XaeroMapsync_r.LOGGER.warn("map_sync event=client_tile_send_failed dimension={} chunk_x={} chunk_z={}",
									tile.dimension(), tile.chunkX(), tile.chunkZ(), exception);
							completion.accept(false);
						}
					});
				} catch (RuntimeException exception) {
					XaeroMapsync_r.LOGGER.warn("map_sync event=client_tile_encode_failed dimension={} chunk_x={} chunk_z={}",
							tile.dimension(), tile.chunkX(), tile.chunkZ(), exception);
					executeLocalTileUploadCompletion(() -> completion.accept(false));
				}
			});
			return true;
		} catch (RejectedExecutionException exception) {
			XaeroMapsync_r.LOGGER.debug("map_sync event=client_tile_encode_deferred dimension={} chunk_x={} chunk_z={} reason=queue_full",
					tile.dimension(), tile.chunkX(), tile.chunkZ());
			return false;
		}
	}

	@Environment(EnvType.CLIENT)
	public static void requestGapRecovery(Collection<MapTileIndexEntry> entries) {
		if (entries == null || entries.isEmpty()) return;
		List<TileRequestPayload> requests = entries.stream().limit(TileBatchRequestPayload.MAX_REQUESTS)
				.map(entry -> new TileRequestPayload(entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision()))
				.toList();
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new TileBatchRequestPayload(requests).write(buffer);
		ClientPlayNetworking.send(C2S_GAP_RECOVERY_REQUEST, buffer);
	}

	@Environment(EnvType.CLIENT)
	private static void executeLocalTileUploadCompletion(Runnable completion) {
		try {
			net.minecraft.client.Minecraft.getInstance().execute(completion);
		} catch (RejectedExecutionException ignored) {
			// Client shutdown makes the per-session upload state unreachable.
		}
	}

	private static ThreadPoolExecutor boundedDaemonWorkers(String namePrefix, int threads, int queueCapacity) {
		AtomicInteger ids = new AtomicInteger();
		return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity), runnable -> {
					Thread thread = new Thread(runnable, namePrefix + ids.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
	}

	public static void sendTransferPart(net.minecraft.server.level.ServerPlayer player, TransferPartPayload payload) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		ServerPlayNetworking.send(player, S2C_TRANSFER_PART, buffer);
		if (SharedMapServer.traceEnabled(player.getUUID())) {
			XaeroMapsync_r.LOGGER.trace(
					"map_sync event=fragment_sent trace_id={} player={} transfer_id={} part={} part_count={} bytes={} crc={}",
					traceId(player), player.getGameProfile().getName(), payload.transferId(), payload.partIndex(),
					payload.partCount(), payload.payload().length, payload.checksum());
		}
	}

	@Environment(EnvType.CLIENT)
	private static void sendClientHello() {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		ClientHelloPayload hello = ClientHelloPayload.current();
		hello.write(buffer);
		XaeroMapsync_r.LOGGER.info(
				"Sending shared map client hello protocol={} mapFormat={} xaeroAdapter={} compression={} maxPacketBytes={}",
				hello.protocolVersion(), hello.mapFormatVersion(), hello.xaeroAdapterVersion(),
				hello.compression(), hello.maxPacketBytes());
		ClientPlayNetworking.send(C2S_HELLO, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void requestEnabledSnapshots() {
		XaeroMapsync_r.LOGGER.debug("Requesting enabled snapshots publicWaypoints={} mapSync={} mapAvailable={}",
				SharedMapClientConfig.get().publicWaypointsEnabled(), SharedMapClientConfig.get().mapSyncEnabled(),
				SharedMapClient.mapSyncAvailable());
		if (SharedMapClientConfig.get().publicWaypointsEnabled()) requestWaypointSnapshot();
	}

	@Environment(EnvType.CLIENT)
	public static void requestPatchManifests(String dimension, long syncId, long expectedEpoch, int cursor,
			int centerChunkX, int centerChunkZ, double motionX, double motionZ) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new PatchManifestRequestPayload(dimension, syncId, expectedEpoch, cursor, centerChunkX, centerChunkZ,
				motionX, motionZ).write(buffer);
		ClientPlayNetworking.send(C2S_PATCH_MANIFEST_REQUEST, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void requestPatch(MapPatchManifest manifest) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new PatchRequestPayload(manifest.key(), manifest.epoch(), manifest.contentHash()).write(buffer);
		ClientPlayNetworking.send(C2S_PATCH_REQUEST, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void createWaypoint(PublicWaypoint waypoint) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new WaypointCreatePayload(waypoint).write(buffer);
		XaeroMapsync_r.LOGGER.debug("Sending waypoint create id={} name={} visibility={} dimension={} revision={}",
				waypoint.id(), waypoint.name(), waypoint.visibility(), waypoint.dimension(), waypoint.revision());
		ClientPlayNetworking.send(C2S_WAYPOINT_CREATE, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void updateWaypoint(PublicWaypoint waypoint) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new WaypointUpdatePayload(waypoint, waypoint.revision()).write(buffer);
		XaeroMapsync_r.LOGGER.debug("Sending waypoint update id={} name={} visibility={} dimension={} revision={}",
				waypoint.id(), waypoint.name(), waypoint.visibility(), waypoint.dimension(), waypoint.revision());
		ClientPlayNetworking.send(C2S_WAYPOINT_UPDATE, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void deleteWaypoint(PublicWaypoint waypoint) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new WaypointDeletePayload(waypoint.id(), waypoint.revision()).write(buffer);
		XaeroMapsync_r.LOGGER.debug("Sending waypoint delete id={} name={} revision={}",
				waypoint.id(), waypoint.name(), waypoint.revision());
		ClientPlayNetworking.send(C2S_WAYPOINT_DELETE, buffer);
	}

	@Environment(EnvType.CLIENT)
	public static void requestWaypointSnapshot() {
		XaeroMapsync_r.LOGGER.debug("Sending waypoint snapshot request");
		ClientPlayNetworking.send(C2S_WAYPOINT_SNAPSHOT_REQUEST, PacketByteBufs.create());
	}

	@Environment(EnvType.CLIENT)
	public static void requestMapSync() {
		requestMapSync(0L, 0L);
	}

	@Environment(EnvType.CLIENT)
	public static void requestMapSync(long syncId, long requestId) {
		if (!SharedMapClient.mapSyncAvailable()) return;
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		if (minecraft.level == null) return;
		String dimension = minecraft.level.dimension().location().toString();
		FriendlyByteBuf mapRootBuffer = PacketByteBufs.create();
		long knownRootHash = SharedMapClient.knownMapRootHash(dimension);
		new MapRootHashPayload(dimension, knownRootHash, syncId, requestId).write(mapRootBuffer);
		XaeroMapsync_r.LOGGER.debug("Sending map root request dimension={} knownRoot={} syncId={} requestId={}",
				dimension, Long.toUnsignedString(knownRootHash), syncId, requestId);
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
		XaeroMapsync_r.LOGGER.debug("Sending waypoint visibility snapshot to {} visibleCount={} team={}",
				player.getGameProfile().getName(), visible.size(), actor.teamName());
		ServerPlayNetworking.send(player, S2C_WAYPOINT_SNAPSHOT, buffer);
	}

	private static void sendPatchManifestPage(net.minecraft.server.level.ServerPlayer player,
			PatchManifestRequestPayload request) {
		if (!SharedMapConfig.mapSyncEnabled()) {
			XaeroMapsync_r.LOGGER.debug("map_sync event=manifest_rejected player={} reason=kill_switch",
					player.getGameProfile().getName());
			return;
		}
		String playerDimension = player.getLevel().dimension().location().toString();
		if (!playerDimension.equals(request.dimension())) {
			XaeroMapsync_r.LOGGER.warn("map_sync event=manifest_rejected player={} dimension={} player_dimension={} reason=dimension_mismatch",
					player.getGameProfile().getName(), request.dimension(), playerDimension);
			return;
		}
		net.minecraft.server.MinecraftServer server = player.getServer();
		try {
			TILE_BATCH_WORKERS.execute(() -> runManifestPreparationTask(() -> {
				cn.net.rms.xaeromapsync_r.map.MapPatchCatalog.Snapshot catalogSnapshot =
						SharedMapServer.patches().snapshot(request.dimension(), request.expectedEpoch());
				Long previousWave = LAST_LOGGED_WAVE_EPOCH.put(request.dimension(), catalogSnapshot.epoch());
				if (previousWave == null || previousWave.longValue() != catalogSnapshot.epoch()) {
					java.util.Map<Integer, Long> sizes = catalogSnapshot.manifests().stream().collect(
							java.util.stream.Collectors.groupingBy(item -> item.key().sideLength(),
									java.util.TreeMap::new, java.util.stream.Collectors.counting()));
					XaeroMapsync_r.LOGGER.info(
							"map_sync event=wave_published dimension={} epoch={} squares={} tiles={} sizes={} coalesce_ms={}",
							request.dimension(), Long.toUnsignedString(catalogSnapshot.epoch()),
							catalogSnapshot.manifests().size(), catalogSnapshot.manifests().stream()
									.mapToInt(item -> item.key().tileCount()).sum(), sizes, 2_000);
				}
				List<MapPatchManifest> sorted = cn.net.rms.xaeromapsync_r.server.ViewportPatchPrioritizer.sort(
						catalogSnapshot.manifests(), request.centerChunkX(), request.centerChunkZ(),
						request.motionX(), request.motionZ());
				long currentEpoch = catalogSnapshot.epoch();
				int cursor = resumeManifestCursor(request.cursor(), request.expectedEpoch(), currentEpoch);
				cursor = Math.min(cursor, sorted.size());
				int nextCursor = Math.min(sorted.size(), cursor + PatchManifestPagePayload.MAX_MANIFESTS);
				PatchManifestPagePayload page = new PatchManifestPagePayload(request.syncId(), currentEpoch, nextCursor,
						sorted.size(), sorted.subList(cursor, nextCursor));
				try {
					server.execute(() -> sendPatchManifestPagePayload(player, page));
				} catch (RejectedExecutionException ignored) {
					// Server stopped while the bounded manifest page was prepared.
				}
			}, exception -> XaeroMapsync_r.LOGGER.error(
					"map_sync event=manifest_prepare_failed trace_id={} player={} sync_id={} dimension={}",
					traceId(player), player.getGameProfile().getName(), request.syncId(), request.dimension(), exception)));
		} catch (RejectedExecutionException exception) {
			XaeroMapsync_r.LOGGER.warn("map_sync event=manifest_rejected player={} reason=worker_queue_full",
					player.getGameProfile().getName());
		}
	}

	static void runManifestPreparationTask(Runnable task, Consumer<RuntimeException> failureSink) {
		try {
			task.run();
		} catch (RuntimeException exception) {
			failureSink.accept(exception);
		}
	}

	static int resumeManifestCursor(int cursor, long expectedEpoch, long currentEpoch) {
		return cursor > 0 && expectedEpoch != currentEpoch ? 0 : cursor;
	}

	private static void sendPatchManifestPagePayload(net.minecraft.server.level.ServerPlayer player,
			PatchManifestPagePayload page) {
		if (player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
		FriendlyByteBuf buffer = PacketByteBufs.create();
		page.write(buffer);
		XaeroMapsync_r.LOGGER.debug(
				"map_sync event=manifest_page_sent trace_id={} player={} sync_id={} epoch={} cursor={} total={} count={} bytes={}",
				traceId(player), player.getGameProfile().getName(), page.syncId(), Long.toUnsignedString(page.epoch()), page.nextCursor(), page.totalCount(),
				page.manifests().size(), buffer.readableBytes());
		if (buffer.readableBytes() <= SharedMapConfig.maxPacketBytes()
				&& SharedMapServer.networkBudget().trySpend(player.getUUID(), buffer.readableBytes())) {
			ServerPlayNetworking.send(player, S2C_PATCH_MANIFEST_PAGE, buffer);
			return;
		}
		byte[] envelope = new byte[buffer.readableBytes() + 1];
		envelope[0] = TRANSFER_TYPE_PATCH_MANIFEST_PAGE;
		buffer.readBytes(envelope, 1, buffer.readableBytes());
		startTransfer(player, envelope, true);
	}

	private static void sendPatchDataIfAvailable(net.minecraft.server.level.ServerPlayer player, PatchRequestPayload request) {
		if (!SharedMapConfig.mapSyncEnabled() || SharedMapConfig.mapSyncShadowMode()) {
			sendPatchUnavailable(player, request, SharedMapConfig.mapSyncShadowMode() ? "shadow-mode" : "kill-switch");
			return;
		}
		String playerDimension = player.getLevel().dimension().location().toString();
		if (!playerDimension.equals(request.key().dimension())) {
			sendPatchUnavailable(player, request, "dimension-mismatch");
			return;
		}
		net.minecraft.server.MinecraftServer server = player.getServer();
		try {
			TILE_BATCH_WORKERS.execute(() -> {
				PatchDataPayload data = null;
				String failure = null;
				try {
					MapPatchManifest current = SharedMapServer.patches().manifest(request.key(), request.epoch()).orElse(null);
					if (current == null) {
						failure = "missing-patch";
					} else if (current.epoch() != request.epoch() || current.contentHash() != request.manifestHash()) {
						failure = "stale-manifest";
					} else {
						MapPatch patch = SharedMapServer.patches().load(current).orElse(null);
						if (patch == null) {
							failure = "missing-tile-body";
						} else {
							List<TileDataPayload> tiles = new ArrayList<>(patch.tiles().size());
							for (MapTile tile : patch.tiles()) {
								MapPatchManifest.TileReference reference = current.tiles().stream()
										.filter(item -> item.chunkX() == tile.chunkX() && item.chunkZ() == tile.chunkZ())
										.findFirst().orElseThrow();
								tiles.add(TileDataPayload.fromTile(tile, reference.revision(), SharedMapConfig.compression()));
							}
							data = new PatchDataPayload(current, tiles);
						}
					}
				} catch (RuntimeException exception) {
					failure = "encode-failed";
					XaeroMapsync_r.LOGGER.error(
							"map_sync event=patch_prepare_failed trace_id={} player={} patch_id={} epoch={}",
							traceId(player), player.getGameProfile().getName(), request.key().stableId(),
							Long.toUnsignedString(request.epoch()), exception);
				}
				PatchDataPayload prepared = data;
				String reason = failure;
				try {
					server.execute(() -> {
						if (prepared == null) sendPatchUnavailable(player, request, reason);
						else sendPatchData(player, prepared);
					});
				} catch (RejectedExecutionException ignored) {
					// Server stopped while the bounded patch body was prepared.
				}
			});
		} catch (RejectedExecutionException exception) {
			sendPatchUnavailable(player, request, "worker-queue-full");
		}
	}

	private static void sendPatchData(net.minecraft.server.level.ServerPlayer player, PatchDataPayload payload) {
		if (player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		byte[] envelope = new byte[buffer.readableBytes() + 1];
		envelope[0] = TRANSFER_TYPE_PATCH_DATA;
		buffer.readBytes(envelope, 1, buffer.readableBytes());
		if (envelope.length > TransferPartPayload.MAX_TRANSFER_BYTES) {
			MapPatchManifest manifest = payload.patch().manifest();
			XaeroMapsync_r.LOGGER.error(
					"map_sync event=patch_transfer_rejected trace_id={} player={} patch_id={} epoch={} bytes={} max_bytes={} reason=oversized",
					traceId(player), player.getGameProfile().getName(), manifest.key().stableId(),
					Long.toUnsignedString(manifest.epoch()), envelope.length, TransferPartPayload.MAX_TRANSFER_BYTES);
			sendPatchUnavailable(player,
					new PatchRequestPayload(manifest.key(), manifest.epoch(), manifest.contentHash()), "patch-too-large");
			return;
		}
		boolean lowPriority = payload.patch().manifest().key().sideLength() <= 2;
		XaeroMapsync_r.LOGGER.debug("map_sync event=patch_transfer_queued trace_id={} player={} patch_id={} epoch={} tiles={} bytes={} priority={}",
				traceId(player), player.getGameProfile().getName(), payload.patch().manifest().key().stableId(),
				Long.toUnsignedString(payload.patch().manifest().epoch()), payload.tiles().size(), envelope.length,
				lowPriority ? "low-hole-fill" : "normal-wave");
		startTransfer(player, envelope, true, lowPriority);
	}

	private static void sendPatchUnavailable(net.minecraft.server.level.ServerPlayer player, PatchRequestPayload request,
			String reason) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new PatchUnavailablePayload(request.key(), reason == null ? "unavailable" : reason).write(buffer);
		ServerPlayNetworking.send(player, S2C_PATCH_UNAVAILABLE, buffer);
		XaeroMapsync_r.LOGGER.warn("map_sync event=patch_unavailable trace_id={} player={} patch_id={} epoch={} reason={}",
				traceId(player), player.getGameProfile().getName(), request.key().stableId(),
				Long.toUnsignedString(request.epoch()), reason);
	}

	private static void sendMapIndexIfChanged(net.minecraft.server.level.ServerPlayer player, MapRootHashPayload request) {
		long rootHash = SharedMapServer.mapTiles().rootHash(request.dimension());
		if (rootHash == request.knownRootHash()) {
			XaeroMapsync_r.LOGGER.debug(
					"Map root unchanged for {} dimension={} rootHash={} syncId={} requestId={}",
					player.getGameProfile().getName(), request.dimension(),
					Long.toUnsignedString(rootHash), request.syncId(), request.requestId());
			sendMapNodePayload(player, request.dimension(), rootHash, request.syncId(), request.requestId(), false,
					List.of(), List.of());
			return;
		}
		List<MerkleNode> roots = SharedMapServer.mapTiles().merkleRoots(request.dimension());
		XaeroMapsync_r.LOGGER.debug(
				"Map root changed for {} dimension={} knownRoot={} serverRoot={} rootNodes={} syncId={} requestId={}",
				player.getGameProfile().getName(), request.dimension(),
				Long.toUnsignedString(request.knownRootHash()), Long.toUnsignedString(rootHash), roots.size(),
				request.syncId(), request.requestId());
		sendMapNodePayload(player, request.dimension(), rootHash, request.syncId(), request.requestId(), false, roots,
				leafEntries(roots));
	}

	private static void sendMapNodeResponse(net.minecraft.server.level.ServerPlayer player, MapNodeRequestPayload request) {
		try {
			String dimension = request.nodes().get(0).dimension();
			for (MerkleNodeAddress node : request.nodes()) {
				if (!dimension.equals(node.dimension())) throw new IllegalArgumentException("Mixed-dimension Merkle request");
			}
			List<MerkleNode> children = SharedMapServer.mapTiles().merkleChildren(request.nodes());
			XaeroMapsync_r.LOGGER.debug(
					"Sending map node response to {} dimension={} requestedNodes={} children={} syncId={} requestId={}",
					player.getGameProfile().getName(), dimension, request.nodes().size(), children.size(),
					request.syncId(), request.requestId());
			sendMapNodePayload(player, dimension, SharedMapServer.mapTiles().rootHash(dimension), request.syncId(),
					request.requestId(), true, children, leafEntries(children));
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
			long syncId, long requestId, boolean nodeRequestResponse,
			Collection<MerkleNode> nodes,
			Collection<MapTileIndexEntry> entries) {
		MapNodeResponsePayload payload = new MapNodeResponsePayload(dimension, rootHash, syncId, requestId,
				nodeRequestResponse, nodes, entries);
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		if (buffer.readableBytes() > SharedMapConfig.maxPacketBytes()) {
			XaeroMapsync_r.LOGGER.debug(
					"Map node payload exceeds packet budget for {} dimension={} bytes={} maxPacketBytes={}; starting transfer",
					player.getGameProfile().getName(), dimension, buffer.readableBytes(),
					SharedMapConfig.maxPacketBytes());
			byte[] envelope = new byte[buffer.readableBytes() + 1];
			envelope[0] = TRANSFER_TYPE_MAP_NODE_RESPONSE;
			buffer.readBytes(envelope, 1, buffer.readableBytes());
			startTransfer(player, envelope);
			return;
		}
		if (!SharedMapServer.networkBudget().trySpend(player.getUUID(), buffer.readableBytes())) {
			XaeroMapsync_r.LOGGER.debug(
					"Map node payload hit network budget for {} dimension={} bytes={}; starting transfer",
					player.getGameProfile().getName(), dimension, buffer.readableBytes());
			byte[] envelope = new byte[buffer.readableBytes() + 1];
			envelope[0] = TRANSFER_TYPE_MAP_NODE_RESPONSE;
			buffer.readBytes(envelope, 1, buffer.readableBytes());
			startTransfer(player, envelope);
			return;
		}
		XaeroMapsync_r.LOGGER.debug(
				"Sending map node payload directly to {} dimension={} rootHash={} syncId={} requestId={} nodes={} entries={} bytes={}",
				player.getGameProfile().getName(), dimension, Long.toUnsignedString(rootHash), syncId, requestId,
				nodes.size(), entries.size(), buffer.readableBytes());
		ServerPlayNetworking.send(player, S2C_MAP_NODE_RESPONSE, buffer);
	}

	private static void handleLocalTileReady(net.minecraft.server.level.ServerPlayer player,
			LocalTileReadyPayload hint) {
		if (!SharedMapConfig.mapSyncEnabled() || SharedMapConfig.mapSyncShadowMode()) return;
		if (LOCAL_TILE_HINT_LIMITER.acquire(player.getUUID(), hint, System.currentTimeMillis())
				!= LocalTileReadyHintLimiter.Result.ACCEPTED) {
			XaeroMapsync_r.LOGGER.debug("Rate-limited local tile ready hint from {} dimension={} chunk={} {} hash={}",
					player.getGameProfile().getName(), hint.dimension(), hint.chunkX(), hint.chunkZ(),
					Long.toUnsignedString(hint.contentHash()));
			return;
		}
		net.minecraft.server.level.ServerLevel level = player.getLevel();
		String currentDimension = level.dimension().location().toString();
		if (!currentDimension.equals(hint.dimension())) {
			XaeroMapsync_r.LOGGER.debug("Rejected local tile ready hint from {} because dimension changed hint={} current={}",
					player.getGameProfile().getName(), hint.dimension(), currentDimension);
			return;
		}
		net.minecraft.world.level.ChunkPos playerChunk = player.chunkPosition();
		int allowedDistance = localTileUploadDistanceLimit(player.getServer().getPlayerList().getViewDistance());
		long deltaX = Math.abs((long) hint.chunkX() - playerChunk.x);
		long deltaZ = Math.abs((long) hint.chunkZ() - playerChunk.z);
		if (Math.max(deltaX, deltaZ) > allowedDistance
				|| !level.getChunkSource().hasChunk(hint.chunkX(), hint.chunkZ())) {
			XaeroMapsync_r.LOGGER.debug(
					"Rejected local tile ready hint from {} dimension={} chunk={} {} delta={} {} allowedDistance={} chunkLoaded={}",
					player.getGameProfile().getName(), hint.dimension(), hint.chunkX(), hint.chunkZ(),
					deltaX, deltaZ, allowedDistance, level.getChunkSource().hasChunk(hint.chunkX(), hint.chunkZ()));
			return;
		}

		boolean firstObservation = SharedMapServer.exploredChunks().markExplored(
				hint.dimension(), hint.chunkX(), hint.chunkZ());
		boolean rendererRefreshNeeded = SharedMapServer.mapTiles()
				.find(hint.dimension(), hint.chunkX(), hint.chunkZ())
				.map(entry -> hint.contentHash() != 0L && entry.contentHash() != hint.contentHash())
				.orElse(true);
		// A body-less hint records ownership only. Automatic server rendering is
		// disabled by default and can be explicitly enabled for recovery diagnostics.
		if (firstObservation || rendererRefreshNeeded) {
			prioritizeServerRender(hint.dimension(), hint.chunkX(), hint.chunkZ());
		}
		XaeroMapsync_r.LOGGER.debug(
				"Accepted local tile ready hint from {} dimension={} chunk={} {} hash={} firstObservation={} rendererRefreshNeeded={}",
				player.getGameProfile().getName(), hint.dimension(), hint.chunkX(), hint.chunkZ(),
				Long.toUnsignedString(hint.contentHash()), firstObservation, rendererRefreshNeeded);
	}

	private static void handleLocalTileData(net.minecraft.server.level.ServerPlayer player, TileDataPayload upload) {
		MapTile tile = upload.tile();
		if (!SharedMapConfig.mapSyncEnabled() || SharedMapConfig.mapSyncShadowMode()) return;
		if (!SharedMapConfig.compression().equals(upload.compression())) {
			XaeroMapsync_r.LOGGER.warn("Rejected local tile upload from {} due to compression mismatch upload={} server={}",
					player.getGameProfile().getName(), upload.compression(), SharedMapConfig.compression());
			return;
		}
		net.minecraft.server.level.ServerLevel level = player.getLevel();
		if (!level.dimension().location().toString().equals(tile.dimension())) {
			XaeroMapsync_r.LOGGER.warn("map_sync event=client_tile_upload_rejected player={} dimension={} chunk_x={} chunk_z={} reason=dimension_mismatch",
					player.getGameProfile().getName(), tile.dimension(), tile.chunkX(), tile.chunkZ());
			return;
		}
		if (!tile.hasRenderableSurface()) {
			if (validLocalTileContext(player, level, tile))
				prioritizeServerRender(tile.dimension(), tile.chunkX(), tile.chunkZ());
			XaeroMapsync_r.LOGGER.warn("Rejected unrenderable local tile upload from {} dimension={} chunk={} {} hash={}",
					player.getGameProfile().getName(), tile.dimension(), tile.chunkX(), tile.chunkZ(),
					Long.toUnsignedString(tile.contentHash()));
			return;
		}
		LocalTileReadyPayload hint = new LocalTileReadyPayload(tile.dimension(), tile.chunkX(), tile.chunkZ(),
				tile.contentHash());
		if (LOCAL_TILE_HINT_LIMITER.acquire(player.getUUID(), hint, System.currentTimeMillis())
				!= LocalTileReadyHintLimiter.Result.ACCEPTED) {
			XaeroMapsync_r.LOGGER.debug("Rate-limited local tile body from {} dimension={} chunk={} {} hash={}",
					player.getGameProfile().getName(), tile.dimension(), tile.chunkX(), tile.chunkZ(),
					Long.toUnsignedString(tile.contentHash()));
			return;
		}
		MapTileIndexEntry current = SharedMapServer.mapTiles()
				.find(tile.dimension(), tile.chunkX(), tile.chunkZ()).orElse(null);
		MapTile currentBody = SharedMapServer.tileData()
				.find(tile.dimension(), tile.chunkX(), tile.chunkZ()).orElse(null);
		boolean nearby = validLocalTileContext(player, level, tile);
		LocalTileMergeDecision mergeDecision = localTileMergeDecision(nearby, current, currentBody, tile.contentHash());
		if (mergeDecision == LocalTileMergeDecision.KEEP_SERVER) {
			XaeroMapsync_r.LOGGER.debug(
					"map_sync event=client_tile_upload_merged player={} source=archive result=server_kept dimension={} chunk_x={} chunk_z={} incoming_hash={} server_hash={}",
					player.getGameProfile().getName(), tile.dimension(), tile.chunkX(), tile.chunkZ(),
					Long.toUnsignedString(tile.contentHash()), Long.toUnsignedString(currentBody.contentHash()));
			return;
		}
		SharedMapServer.exploredChunks().markExplored(tile.dimension(), tile.chunkX(), tile.chunkZ());
		long dirtyGeneration = SharedMapServer.dirtyChunks()
				.clientTileGeneration(tile.dimension(), tile.chunkX(), tile.chunkZ());
		if (mergeDecision == LocalTileMergeDecision.DUPLICATE) {
			SharedMapServer.dirtyChunks().confirmClientTile(
					tile.dimension(), tile.chunkX(), tile.chunkZ(), dirtyGeneration);
			XaeroMapsync_r.LOGGER.debug(
					"map_sync event=client_tile_upload_merged player={} source={} result=duplicate dimension={} chunk_x={} chunk_z={} hash={} revision={} dirty_generation={}",
					player.getGameProfile().getName(), nearby ? "nearby" : "archive", tile.dimension(),
					tile.chunkX(), tile.chunkZ(), Long.toUnsignedString(tile.contentHash()), current.revision(),
					dirtyGeneration);
			return;
		}

		byte[] preparedPayload = upload.surfacePayload();
		net.minecraft.server.MinecraftServer server = player.getServer();
		XaeroMapsync_r.LOGGER.debug(
				"map_sync event=client_tile_upload_staged player={} source={} dimension={} chunk_x={} chunk_z={} hash={} payload_bytes={} dirty_generation={} existing_revision={}",
				player.getGameProfile().getName(), nearby ? "nearby" : "archive", tile.dimension(),
				tile.chunkX(), tile.chunkZ(), Long.toUnsignedString(tile.contentHash()), preparedPayload.length, dirtyGeneration,
				current == null ? null : current.revision());
		boolean accepted = SharedMapServer.tileData().stageAsynchronously(tile, staged -> {
			if (staged.isEmpty()) {
				server.execute(() -> {
					XaeroMapsync_r.LOGGER.warn("Failed to stage local tile upload from {} dimension={} chunk={} {}; upload remains retryable",
							player.getGameProfile().getName(), tile.dimension(), tile.chunkX(), tile.chunkZ());
					if (nearby) prioritizeServerRender(tile.dimension(), tile.chunkX(), tile.chunkZ());
				});
				return;
			}
			boolean committed = SharedMapServer.dirtyChunks().commitClientTile(
					tile.dimension(), tile.chunkX(), tile.chunkZ(), dirtyGeneration,
					() -> SharedMapServer.tileData().commitStaged(staged.get()));
			if (!committed) SharedMapServer.tileData().discardStaged(staged.get());
			server.execute(() -> {
				if (!committed) {
					XaeroMapsync_r.LOGGER.debug("Discarded staged local tile upload from {} dimension={} chunk={} {} dirtyGeneration={} because dirty state changed",
							player.getGameProfile().getName(), tile.dimension(), tile.chunkX(), tile.chunkZ(),
							dirtyGeneration);
					if (nearby) prioritizeServerRender(tile.dimension(), tile.chunkX(), tile.chunkZ());
					return;
				}
				MapTile latestBody = SharedMapServer.tileData()
						.find(tile.dimension(), tile.chunkX(), tile.chunkZ()).orElse(null);
				if (latestBody == null || latestBody.contentHash() != tile.contentHash()) {
					XaeroMapsync_r.LOGGER.warn("Committed local tile upload from {} but latest body mismatch dimension={} chunk={} {} expectedHash={} actualHash={}",
							player.getGameProfile().getName(), tile.dimension(), tile.chunkX(), tile.chunkZ(),
							Long.toUnsignedString(tile.contentHash()),
							latestBody == null ? "missing" : Long.toUnsignedString(latestBody.contentHash()));
					return;
				}
				MapTileIndexEntry published = SharedMapServer.mapTiles().upsert(tile);
				XaeroMapsync_r.LOGGER.info(
						"map_sync event=client_tile_upload_published player={} source={} dimension={} chunk_x={} chunk_z={} hash={} revision={} accepted_clients={}",
						player.getGameProfile().getName(), nearby ? "nearby" : "archive", tile.dimension(), tile.chunkX(), tile.chunkZ(),
						Long.toUnsignedString(tile.contentHash()), published.revision(),
						SharedMapServer.acceptedClientCount());
				broadcastTileData(server, tile, published, preparedPayload);
			});
		});
		if (!accepted) {
			if (nearby) prioritizeServerRender(tile.dimension(), tile.chunkX(), tile.chunkZ());
			XaeroMapsync_r.LOGGER.warn("Rejected local tile upload from {} because staging queue is busy dimension={} chunk={} {}",
					player.getGameProfile().getName(), tile.dimension(), tile.chunkX(), tile.chunkZ());
		}
	}

	static boolean hasMatchingTileBody(MapTileIndexEntry current, MapTile body, long contentHash) {
		return current != null && current.contentHash() == contentHash
				&& body != null && body.contentHash() == contentHash;
	}

	static int localTileUploadDistanceLimit(int serverViewDistance) {
		return Math.max(2, serverViewDistance + LOCAL_TILE_HINT_DISTANCE_GRACE_CHUNKS);
	}

	private static boolean validLocalTileContext(net.minecraft.server.level.ServerPlayer player,
			net.minecraft.server.level.ServerLevel level, MapTile tile) {
		if (!level.dimension().location().toString().equals(tile.dimension())) return false;
		net.minecraft.world.level.ChunkPos playerChunk = player.chunkPosition();
		int allowedDistance = localTileUploadDistanceLimit(player.getServer().getPlayerList().getViewDistance());
		return Math.max(Math.abs((long) tile.chunkX() - playerChunk.x),
				Math.abs((long) tile.chunkZ() - playerChunk.z)) <= allowedDistance;
	}

	private static void sendTileDataIfAvailable(net.minecraft.server.level.ServerPlayer player, TileRequestPayload request) {
		XaeroMapsync_r.LOGGER.debug("Handling tile request from {} dimension={} chunk={} {} revision={}",
				player.getGameProfile().getName(), request.dimension(), request.chunkX(), request.chunkZ(),
				request.knownRevision());
		MapTile tile = SharedMapServer.tileData().find(request.dimension(), request.chunkX(), request.chunkZ()).orElse(null);
		byte[] preparedSurfacePayload = null;
		if (tile == null || !tile.hasRenderableSurface()) {
			if (!SharedMapServer.exploredChunks().isExplored(request.dimension(), request.chunkX(), request.chunkZ())) {
				sendTileUnavailable(player, request, "Tile is not explored");
				return;
			}
			prioritizeServerRender(request.dimension(), request.chunkX(), request.chunkZ());
			sendTileUnavailable(player, request, tile == null
			? "Tile is awaiting a client Xaero upload"
					: "Stored tile is unrenderable and must be uploaded again");
			return;
		}
		MapTileIndexEntry entry = SharedMapServer.mapTiles().upsert(tile);
		// An explicit request means the client has the index entry but not the tile body.
		// The index revision is therefore not proof that the body was already applied.
		try {
			TileDataPayload payload = preparedSurfacePayload == null
					? TileDataPayload.fromTile(tile, entry.revision(), SharedMapConfig.compression())
					: new TileDataPayload(tile, entry.revision(), SharedMapConfig.compression(), preparedSurfacePayload);
			XaeroMapsync_r.LOGGER.debug("Sending requested tile to {} dimension={} chunk={} {} revision={} hash={} payloadBytes={}",
					player.getGameProfile().getName(), tile.dimension(), tile.chunkX(), tile.chunkZ(),
					entry.revision(), Long.toUnsignedString(tile.contentHash()), payload.surfacePayload().length);
			sendTileData(player, payload, true);
		} catch (RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to encode requested tile for {} dimension={} chunk={} {}",
					player.getGameProfile().getName(), request.dimension(), request.chunkX(), request.chunkZ(),
					exception);
			sendTileUnavailable(player, request, "Stored tile cannot be encoded and must be uploaded again");
		}
	}

	private static void handleGapRecoveryRequest(net.minecraft.server.level.ServerPlayer requester,
			TileBatchRequestPayload payload) {
		if (!GAP_RECOVERY_REQUEST_LIMITER.acquire(requester.getUUID(), System.currentTimeMillis())) {
			XaeroMapsync_r.LOGGER.warn("map_sync event=gap_recovery_rejected player={} reason=rate_limited tiles={}",
					requester.getGameProfile().getName(), payload.requests().size());
			return;
		}
		String dimension = requester.getLevel().dimension().location().toString();
		List<net.minecraft.server.level.ServerPlayer> peers = requester.getServer().getPlayerList().getPlayers().stream()
				.filter(player -> player != requester)
				.filter(player -> SharedMapServer.hasAcceptedClient(player.getUUID()))
				.filter(player -> dimension.equals(player.getLevel().dimension().location().toString()))
				.limit(MAX_GAP_RECOVERY_PEERS)
				.toList();
		List<TileRequestPayload> probes = new ArrayList<>();
		long nowMillis = System.currentTimeMillis();
		for (TileRequestPayload request : payload.requests()) {
			if (!dimension.equals(request.dimension())) {
				sendTileUnavailable(requester, request, "Recovery dimension mismatch");
				continue;
			}
			MapTile stored = SharedMapServer.tileData()
					.find(request.dimension(), request.chunkX(), request.chunkZ()).orElse(null);
			if (stored != null && stored.hasRenderableSurface()) {
				sendTileDataIfAvailable(requester, request);
				continue;
			}
			if (peers.isEmpty()) {
				prioritizeServerRender(request.dimension(), request.chunkX(), request.chunkZ());
				sendTileUnavailable(requester, request, "No online client has a recoverable tile");
				continue;
			}
			cn.net.rms.xaeromapsync_r.server.GapRecoveryBroker.RequestResult result =
					SharedMapServer.gapRecovery().request(requester.getUUID(), request, nowMillis);
			if (result == cn.net.rms.xaeromapsync_r.server.GapRecoveryBroker.RequestResult.NEW) probes.add(request);
			else if (result == cn.net.rms.xaeromapsync_r.server.GapRecoveryBroker.RequestResult.REJECTED)
				sendTileUnavailable(requester, request, "Recovery queue is busy");
		}
		if (probes.isEmpty()) return;
		TileBatchRequestPayload probe = new TileBatchRequestPayload(probes);
		for (net.minecraft.server.level.ServerPlayer peer : peers) {
			FriendlyByteBuf buffer = PacketByteBufs.create();
			probe.write(buffer);
			ServerPlayNetworking.send(peer, S2C_GAP_RECOVERY_PROBE, buffer);
		}
		XaeroMapsync_r.LOGGER.info(
				"map_sync event=gap_recovery_probe requester={} tiles={} peers={} wait_ms={} priority=high",
				requester.getGameProfile().getName(), probes.size(), peers.size(),
				cn.net.rms.xaeromapsync_r.server.GapRecoveryBroker.PEER_WAIT_MILLIS);
	}

	public static void expireGapRecoveries(net.minecraft.server.MinecraftServer server, long nowMillis) {
		for (cn.net.rms.xaeromapsync_r.server.GapRecoveryBroker.WaitingRequest waiting
				: SharedMapServer.gapRecovery().expire(nowMillis, 128)) {
			net.minecraft.server.level.ServerPlayer requester = server.getPlayerList().getPlayer(waiting.requester());
			if (requester == null || !SharedMapServer.hasAcceptedClient(waiting.requester())) continue;
			TileRequestPayload request = waiting.request();
			MapTile stored = SharedMapServer.tileData()
					.find(request.dimension(), request.chunkX(), request.chunkZ()).orElse(null);
			if (stored != null && stored.hasRenderableSurface()) sendTileDataIfAvailable(requester, request);
			else {
				prioritizeServerRender(request.dimension(), request.chunkX(), request.chunkZ());
				sendTileUnavailable(requester, request, "Online clients do not have this tile");
			}
		}
	}

	private static void sendTileBatchDataIfAvailable(net.minecraft.server.level.ServerPlayer player,
			TileBatchRequestPayload request) {
		net.minecraft.server.MinecraftServer server = player.getServer();
		String compression = SharedMapConfig.compression();
		XaeroMapsync_r.LOGGER.debug("Handling tile batch request from {} count={} compression={}",
				player.getGameProfile().getName(), request.requests().size(), compression);
		try {
			TILE_BATCH_WORKERS.execute(() -> {
				List<PreparedTile> prepared = new ArrayList<>(request.requests().size());
				for (TileRequestPayload tileRequest : request.requests()) {
					MapTile tile = SharedMapServer.tileData()
							.find(tileRequest.dimension(), tileRequest.chunkX(), tileRequest.chunkZ()).orElse(null);
					if (tile == null || !tile.hasRenderableSurface()) {
						prepared.add(new PreparedTile(tileRequest, null, null));
						continue;
					}
					try {
						byte[] surface = CompressionCodec.encodeSurface(
								CompressionCodec.MapTileSurfaceData.fromTile(tile), compression);
						prepared.add(new PreparedTile(tileRequest, tile, surface));
					} catch (RuntimeException exception) {
						XaeroMapsync_r.LOGGER.warn("Failed to encode requested batch tile dimension={} chunk={} {}",
								tileRequest.dimension(), tileRequest.chunkX(), tileRequest.chunkZ(), exception);
						prepared.add(new PreparedTile(tileRequest, tile, null));
					}
				}
				try {
					server.execute(() -> sendPreparedTileBatch(player, prepared, compression));
				} catch (RejectedExecutionException ignored) {
					// The server stopped while this bounded background batch was being prepared.
				}
			});
		} catch (RejectedExecutionException exception) {
			XaeroMapsync_r.LOGGER.warn("Tile batch worker queue rejected request from {} count={}",
					player.getGameProfile().getName(), request.requests().size(), exception);
			for (TileRequestPayload tileRequest : request.requests()) {
				sendTileUnavailable(player, tileRequest, "Map tile transfer queue is busy; retrying shortly");
			}
		}
	}

	private static void sendPreparedTileBatch(net.minecraft.server.level.ServerPlayer player,
			List<PreparedTile> prepared, String compression) {
		if (player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
			XaeroMapsync_r.LOGGER.debug("Dropping prepared tile batch because player disconnected: {} prepared={}",
					player.getGameProfile().getName(), prepared.size());
			return;
		}
		List<TileDataPayload> available = new ArrayList<>(prepared.size());
		int unavailable = 0;
		int encodeFailures = 0;
		for (PreparedTile result : prepared) {
			if (result.tile == null) {
				unavailable++;
				if (SharedMapServer.exploredChunks().isExplored(result.request.dimension(),
						result.request.chunkX(), result.request.chunkZ())) {
					prioritizeServerRender(result.request.dimension(), result.request.chunkX(), result.request.chunkZ());
					sendTileUnavailable(player, result.request,
							"Tile is awaiting a client Xaero upload");
				} else {
					sendTileUnavailable(player, result.request, "Tile is not explored");
				}
				continue;
			}
			if (result.surfacePayload == null) {
				encodeFailures++;
				sendTileUnavailable(player, result.request, "Stored tile cannot be encoded and must be uploaded again");
				continue;
			}
			MapTileIndexEntry entry = SharedMapServer.mapTiles().upsert(result.tile);
			available.add(new TileDataPayload(result.tile, entry.revision(), compression, result.surfacePayload));
		}
		List<TileBatchDataPayload> batches = splitTileDataBatchesForPacketBudget(available, SharedMapConfig.maxPacketBytes());
		XaeroMapsync_r.LOGGER.debug(
				"Prepared tile batch response for {} requested={} available={} unavailable={} encodeFailures={} responseBatches={}",
				player.getGameProfile().getName(), prepared.size(), available.size(), unavailable,
				encodeFailures, batches.size());
		for (TileBatchDataPayload batch : batches) {
			sendTileDataBatch(player, batch);
		}
	}

	private record PreparedTile(TileRequestPayload request, MapTile tile, byte[] surfacePayload) {}

	static List<TileBatchDataPayload> splitTileDataBatchesForPacketBudget(List<TileDataPayload> tiles,
			int maxPacketBytes) {
		if (tiles == null || tiles.isEmpty()) return List.of();
		if (maxPacketBytes <= TILE_BATCH_ENVELOPE_BYTES) {
			throw new IllegalArgumentException("Maximum packet bytes must leave room for a tile batch envelope");
		}
		List<TileBatchDataPayload> batches = new ArrayList<>();
		List<TileDataPayload> current = new ArrayList<>(TileBatchDataPayload.MAX_TILES);
		int currentBytes = TILE_BATCH_ENVELOPE_BYTES;
		for (TileDataPayload tile : tiles) {
			int tileBytes = estimatedTileDataBytes(tile);
			if (!current.isEmpty()
					&& (current.size() >= TileBatchDataPayload.MAX_TILES
							|| currentBytes + tileBytes > maxPacketBytes)) {
				batches.add(new TileBatchDataPayload(current));
				current = new ArrayList<>(TileBatchDataPayload.MAX_TILES);
				currentBytes = TILE_BATCH_ENVELOPE_BYTES;
			}
			current.add(tile);
			currentBytes += tileBytes;
		}
		if (!current.isEmpty()) batches.add(new TileBatchDataPayload(current));
		return List.copyOf(batches);
	}

	private static int estimatedTileDataBytes(TileDataPayload tile) {
		return TILE_BATCH_TILE_ENVELOPE_BYTES
				+ tile.tile().dimension().getBytes(StandardCharsets.UTF_8).length
				+ tile.compression().getBytes(StandardCharsets.UTF_8).length
				+ tile.surfacePayload().length;
	}

	/** Pushes newly published tile bodies immediately; Merkle polling remains recovery only. */
	public static void broadcastTileData(net.minecraft.server.MinecraftServer server, MapTile tile, MapTileIndexEntry entry,
			byte[] preparedSurfacePayload) {
		SharedMapServer.gapRecovery().resolve(tile.dimension(), tile.chunkX(), tile.chunkZ());
		if (!tile.hasRenderableSurface()) {
			prioritizeServerRender(tile.dimension(), tile.chunkX(), tile.chunkZ());
			return;
		}
		TileDataPayload payload = preparedSurfacePayload == null
				? TileDataPayload.fromTile(tile, entry.revision(), SharedMapConfig.compression())
				: new TileDataPayload(tile, entry.revision(), SharedMapConfig.compression(), preparedSurfacePayload);
		for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!SharedMapServer.hasAcceptedClient(player.getUUID())) continue;
			if (!tile.dimension().equals(player.getLevel().dimension().location().toString())) continue;
			XaeroMapsync_r.LOGGER.debug("Broadcasting tile data to {} dimension={} chunk={} {} revision={} hash={}",
					player.getGameProfile().getName(), tile.dimension(), tile.chunkX(), tile.chunkZ(),
					entry.revision(), Long.toUnsignedString(tile.contentHash()));
			if (!sendTileData(player, payload, false)) {
				XaeroMapsync_r.LOGGER.debug("Deferred active tile push for {} at {} {} {}; Merkle recovery remains available",
						player.getGameProfile().getName(), tile.dimension(), tile.chunkX(), tile.chunkZ());
			}
		}
	}

	private static boolean sendTileData(net.minecraft.server.level.ServerPlayer player, TileDataPayload payload,
			boolean reportBackpressure) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		if (buffer.readableBytes() > SharedMapConfig.maxPacketBytes()
				|| !SharedMapServer.networkBudget().trySpend(player.getUUID(), buffer.readableBytes())) {
			XaeroMapsync_r.LOGGER.debug("Sending tile data to {} via transfer dimension={} chunk={} {} revision={} bytes={} maxPacketBytes={}",
					player.getGameProfile().getName(), payload.tile().dimension(), payload.tile().chunkX(),
					payload.tile().chunkZ(), payload.revision(), buffer.readableBytes(),
					SharedMapConfig.maxPacketBytes());
			byte[] envelope = new byte[buffer.readableBytes() + 1];
			envelope[0] = TRANSFER_TYPE_TILE_DATA;
			buffer.readBytes(envelope, 1, buffer.readableBytes());
			return startTransfer(player, envelope, reportBackpressure);
		}
		XaeroMapsync_r.LOGGER.debug("Sending tile data directly to {} dimension={} chunk={} {} revision={} bytes={}",
				player.getGameProfile().getName(), payload.tile().dimension(), payload.tile().chunkX(),
				payload.tile().chunkZ(), payload.revision(), buffer.readableBytes());
		ServerPlayNetworking.send(player, S2C_TILE_DATA, buffer);
		return true;
	}

	private static boolean sendTileDataBatch(net.minecraft.server.level.ServerPlayer player,
			TileBatchDataPayload payload) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		payload.write(buffer);
		if (buffer.readableBytes() > SharedMapConfig.maxPacketBytes()
				|| !SharedMapServer.networkBudget().trySpend(player.getUUID(), buffer.readableBytes())) {
			XaeroMapsync_r.LOGGER.debug("Sending tile data batch to {} via transfer count={} bytes={} maxPacketBytes={}",
					player.getGameProfile().getName(), payload.tiles().size(), buffer.readableBytes(),
					SharedMapConfig.maxPacketBytes());
			byte[] envelope = new byte[buffer.readableBytes() + 1];
			envelope[0] = TRANSFER_TYPE_TILE_DATA_BATCH;
			buffer.readBytes(envelope, 1, buffer.readableBytes());
			return startTransfer(player, envelope, true);
		}
		XaeroMapsync_r.LOGGER.debug("Sending tile data batch directly to {} count={} bytes={}",
				player.getGameProfile().getName(), payload.tiles().size(), buffer.readableBytes());
		ServerPlayNetworking.send(player, S2C_TILE_DATA_BATCH, buffer);
		return true;
	}

	private static void sendTileUnavailable(net.minecraft.server.level.ServerPlayer player, TileRequestPayload request,
			String reason) {
		FriendlyByteBuf buffer = PacketByteBufs.create();
		new TileUnavailablePayload(request.dimension(), request.chunkX(), request.chunkZ(), reason).write(buffer);
		XaeroMapsync_r.LOGGER.debug("Sending tile unavailable to {} dimension={} chunk={} {} revision={} reason={}",
				player.getGameProfile().getName(), request.dimension(), request.chunkX(), request.chunkZ(),
				request.knownRevision(), reason);
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
		} catch (RuntimeException exception) {
			// Invalid coordinates are already represented by the failed audit detail.
			XaeroMapsync_r.LOGGER.warn("Failed to resolve waypoint audit region action={} waypoint_id={}",
					action, waypoint == null ? null : waypoint.id(), exception);
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

	private static void prioritizeServerRender(String dimension, int chunkX, int chunkZ) {
		if (SharedMapConfig.serverMapRenderingEnabled())
			SharedMapServer.dirtyChunks().prioritizeDiscovered(dimension, chunkX, chunkZ);
	}

	static LocalTileMergeDecision localTileMergeDecision(boolean nearby, MapTileIndexEntry current,
			MapTile body, long incomingHash) {
		if (hasMatchingTileBody(current, body, incomingHash)) return LocalTileMergeDecision.DUPLICATE;
		if (!nearby && body != null && body.hasRenderableSurface()) return LocalTileMergeDecision.KEEP_SERVER;
		return LocalTileMergeDecision.ACCEPT;
	}

	private static java.util.UUID traceId(net.minecraft.server.level.ServerPlayer player) {
		return SharedMapServer.clientState(player.getUUID()).map(value -> value.traceId()).orElse(null);
	}

	private static void startTransfer(net.minecraft.server.level.ServerPlayer player, byte[] envelope) {
		startTransfer(player, envelope, true);
	}

	private static boolean startTransfer(net.minecraft.server.level.ServerPlayer player, byte[] envelope,
			boolean reportBackpressure) {
		return startTransfer(player, envelope, reportBackpressure, false);
	}

	private static boolean startTransfer(net.minecraft.server.level.ServerPlayer player, byte[] envelope,
			boolean reportBackpressure, boolean lowPriority) {
		try {
			SharedMapServer.transfers().start(player, envelope, lowPriority);
			XaeroMapsync_r.LOGGER.debug("Started fragmented transfer for {} bytes={} reportBackpressure={} priority={}",
					player.getGameProfile().getName(), envelope.length, reportBackpressure,
					lowPriority ? "low" : "normal");
			return true;
		} catch (IllegalStateException | IllegalArgumentException exception) {
			XaeroMapsync_r.LOGGER.warn(
					"map_sync event=transfer_rejected player={} bytes={} report_backpressure={} reason={}",
					player.getGameProfile().getName(), envelope.length, reportBackpressure, exception.getMessage());
			return false;
		}
	}

	@Environment(EnvType.CLIENT)
	private static final class ClientTransfers {
		private static final ThreadPoolExecutor DECODE_WORKERS = boundedDaemonWorkers(
				"xaero-mapsync-client-decode-", 4, 64);
		private static final ClientTransferManager MANAGER = new ClientTransferManager(
				ClientTransfers::sendAck, ClientTransfers::sendNack, ClientTransfers::handleCompleted);

		private static void sendAck(TransferAckPayload payload) {
			net.minecraft.client.Minecraft.getInstance().execute(() -> {
				FriendlyByteBuf buffer = PacketByteBufs.create();
				payload.write(buffer);
				ClientPlayNetworking.send(C2S_TRANSFER_ACK, buffer);
			});
		}

		private static void sendNack(TransferNackPayload payload) {
			net.minecraft.client.Minecraft.getInstance().execute(() -> {
				FriendlyByteBuf buffer = PacketByteBufs.create();
				payload.write(buffer);
				ClientPlayNetworking.send(C2S_TRANSFER_NACK, buffer);
			});
		}

		private static void handleCompleted(byte[] data, Consumer<Boolean> completion) {
			try {
				DECODE_WORKERS.execute(() -> {
					try {
						decodeCompleted(data);
						completion.accept(true);
					} catch (RuntimeException | LinkageError exception) {
						XaeroMapsync_r.LOGGER.warn("map_sync event=client_decode_failed bytes={}", data.length, exception);
						completion.accept(false);
					}
				});
			} catch (RejectedExecutionException exception) {
				XaeroMapsync_r.LOGGER.warn("map_sync event=client_decode_rejected bytes={} reason=queue_full", data.length);
				completion.accept(false);
			}
		}

		private static void decodeCompleted(byte[] data) {
			if (data.length < 1) throw new IllegalArgumentException("Transfer envelope is empty");
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
			int type = buffer.readUnsignedByte();
			if (type == TRANSFER_TYPE_MAP_NODE_RESPONSE) {
				MapNodeResponsePayload payload = MapNodeResponsePayload.read(buffer);
				net.minecraft.client.Minecraft.getInstance().execute(() -> SharedMapClient.handleMapNodeResponse(payload));
				return;
			}
			if (type == TRANSFER_TYPE_TILE_DATA) {
				TileDataPayload payload = TileDataPayload.read(buffer);
				net.minecraft.client.Minecraft.getInstance().execute(() -> SharedMapClient.handleTileData(payload));
				return;
			}
			if (type == TRANSFER_TYPE_TILE_DATA_BATCH) {
				TileBatchDataPayload payload = TileBatchDataPayload.read(buffer);
				net.minecraft.client.Minecraft.getInstance().execute(() -> SharedMapClient.handleTileDataBatch(payload));
				return;
			}
			if (type == TRANSFER_TYPE_PATCH_MANIFEST_PAGE) {
				PatchManifestPagePayload payload = PatchManifestPagePayload.read(buffer);
				net.minecraft.client.Minecraft.getInstance().execute(() -> SharedMapClient.handlePatchManifestPage(payload));
				return;
			}
			if (type == TRANSFER_TYPE_PATCH_DATA) {
				PatchDataPayload payload = PatchDataPayload.read(buffer);
				net.minecraft.client.Minecraft.getInstance().execute(() -> SharedMapClient.handlePatchData(payload));
				return;
			}
			throw new IllegalArgumentException("Unknown transfer envelope type: " + type);
		}
	}
}
