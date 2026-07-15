package cn.net.rms.xaeromapsync_r.server;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexStore;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStore;
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
import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.server.activity.RegionActivityStore;
import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public final class SharedMapServer {
	private static final Map<UUID, ServerClientState> CLIENTS = new ConcurrentHashMap<>();
	private static final PublicWaypointStore WAYPOINTS = new PublicWaypointStore();
	private static final ExploredChunkStore EXPLORED_CHUNKS = new ExploredChunkStore();
	private static final DirtyChunkStore DIRTY_CHUNKS = new DirtyChunkStore();
	private static final MapTileIndexStore MAP_TILES = new MapTileIndexStore();
	private static final MapTileDataStore TILE_DATA = new MapTileDataStore();
	private static final NetworkBudgetTracker NETWORK_BUDGET = new NetworkBudgetTracker();
	private static final SharedMapPermissionPolicy PERMISSIONS = new SharedMapPermissionPolicy();
	private static final RegionActivityStore ACTIVITY = new RegionActivityStore(SharedMapConfig.stormBlockChangesThreshold(),
			SharedMapConfig.stormDirtyChunksThreshold(), SharedMapConfig.stormCooldownTicks(), SharedMapConfig.stableTicks());
	private static final MapTaskScheduler MAP_TASKS = new MapTaskScheduler(DIRTY_CHUNKS, MAP_TILES, TILE_DATA, ACTIVITY);
	private static final Map<RegionKey, TickActivity> TICK_ACTIVITY = new LinkedHashMap<>();
	private static final Set<RegionKey> KNOWN_REGIONS = new LinkedHashSet<>();
	private static final ServerTransferManager TRANSFERS = new ServerTransferManager(NETWORK_BUDGET,
			cn.net.rms.xaeromapsync_r.network.SharedMapNetworking::sendTransferPart);

	private SharedMapServer() {
	}

	public static void register() {
		SharedMapCommands.register();
		ExplorationTracker.register();
		MAP_TASKS.register();
		TRANSFERS.register();
		ServerTickEvents.END_SERVER_TICK.register(server -> flushRegionActivity());
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TILE_DATA.start(server);
			WAYPOINTS.load(server);
			EXPLORED_CHUNKS.load(server);
			DIRTY_CHUNKS.load(server);
			MAP_TILES.load(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			TILE_DATA.stop();
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

	public static MapTileDataStore tileData() { return TILE_DATA; }

	public static NetworkBudgetTracker networkBudget() {
		return NETWORK_BUDGET;
	}

	public static MapTaskScheduler mapTasks() {
		return MAP_TASKS;
	}

	public static ServerTransferManager transfers() { return TRANSFERS; }
	public static SharedMapPermissionPolicy permissions() { return PERMISSIONS; }

	public static synchronized void recordBlockChange(String dimension, BlockPos pos) {
		DIRTY_CHUNKS.markDirty(dimension, pos);
		RegionKey key = RegionKey.fromBlock(dimension, pos.getX(), pos.getZ());
		KNOWN_REGIONS.add(key);
		TickActivity tick = TICK_ACTIVITY.computeIfAbsent(key, ignored -> new TickActivity());
		tick.blockChanges = Math.min(Integer.MAX_VALUE, tick.blockChanges + 1);
		tick.dirtyChunks.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
	}

	private static synchronized void flushRegionActivity() {
		for (RegionKey key : KNOWN_REGIONS) {
			TickActivity tick = TICK_ACTIVITY.get(key);
			ACTIVITY.recordTick(key, tick == null ? 0 : tick.blockChanges, tick == null ? 0 : tick.dirtyChunks.size());
		}
		TICK_ACTIVITY.clear();
	}

	public static RegionActivityStore activity() { return ACTIVITY; }

	private static final class TickActivity {
		private int blockChanges;
		private final Set<Long> dirtyChunks = new LinkedHashSet<>();
	}
}
