package cn.net.rms.xaeromapsync_r.server;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexStore;
import cn.net.rms.xaeromapsync_r.network.ClientHelloPayload;
import cn.net.rms.xaeromapsync_r.server.command.SharedMapCommands;
import cn.net.rms.xaeromapsync_r.server.dirty.DirtyChunkStore;
import cn.net.rms.xaeromapsync_r.server.exploration.ExplorationTracker;
import cn.net.rms.xaeromapsync_r.server.exploration.ExploredChunkStore;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypointStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

public final class SharedMapServer {
	private static final Map<UUID, ServerClientState> CLIENTS = new ConcurrentHashMap<>();
	private static final PublicWaypointStore WAYPOINTS = new PublicWaypointStore();
	private static final ExploredChunkStore EXPLORED_CHUNKS = new ExploredChunkStore();
	private static final DirtyChunkStore DIRTY_CHUNKS = new DirtyChunkStore();
	private static final MapTileIndexStore MAP_TILES = new MapTileIndexStore();
	private static final NetworkBudgetTracker NETWORK_BUDGET = new NetworkBudgetTracker();

	private SharedMapServer() {
	}

	public static void register() {
		SharedMapCommands.register();
		ExplorationTracker.register();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			WAYPOINTS.load(server);
			EXPLORED_CHUNKS.load(server);
			DIRTY_CHUNKS.load(server);
			MAP_TILES.load(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			WAYPOINTS.save(server);
			EXPLORED_CHUNKS.save(server);
			DIRTY_CHUNKS.save(server);
			MAP_TILES.save(server);
			CLIENTS.clear();
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> CLIENTS.remove(handler.player.getUUID()));
	}

	public static void recordHandshake(ServerPlayer player, ClientHelloPayload hello, boolean accepted) {
		CLIENTS.put(player.getUUID(), new ServerClientState(player.getUUID(), player.getGameProfile().getName(), accepted));
		if (accepted) {
			XaeroMapsync_r.LOGGER.info("Shared map client accepted: {} protocol={} mapFormat={} xaeroAdapter={} compression={} maxPacketBytes={}",
					player.getGameProfile().getName(),
					hello.protocolVersion(),
					hello.mapFormatVersion(),
					hello.xaeroAdapterVersion(),
					hello.compression(),
					hello.maxPacketBytes());
			return;
		}
		XaeroMapsync_r.LOGGER.warn("Shared map client rejected: {} protocol={} mapFormat={}",
				player.getGameProfile().getName(),
				hello.protocolVersion(),
				hello.mapFormatVersion());
	}

	public static boolean hasAcceptedClient(UUID playerId) {
		ServerClientState state = CLIENTS.get(playerId);
		return state != null && state.accepted();
	}

	public static int acceptedClientCount() {
		int count = 0;
		for (ServerClientState state : CLIENTS.values()) {
			if (state.accepted()) {
				count++;
			}
		}
		return count;
	}

	public static PublicWaypointStore waypoints() {
		return WAYPOINTS;
	}

	public static ExploredChunkStore exploredChunks() {
		return EXPLORED_CHUNKS;
	}

	public static DirtyChunkStore dirtyChunks() {
		return DIRTY_CHUNKS;
	}

	public static MapTileIndexStore mapTiles() {
		return MAP_TILES;
	}

	public static NetworkBudgetTracker networkBudget() {
		return NETWORK_BUDGET;
	}
}
