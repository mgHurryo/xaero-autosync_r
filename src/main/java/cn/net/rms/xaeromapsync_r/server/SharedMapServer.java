package cn.net.rms.xaeromapsync_r.server;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.map.MapTileIndexStore;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStore;
import cn.net.rms.xaeromapsync_r.network.ClientHelloPayload;
import cn.net.rms.xaeromapsync_r.server.command.SharedMapCommands;
import cn.net.rms.xaeromapsync_r.server.access.SharedMapAccessManager;
import cn.net.rms.xaeromapsync_r.server.access.SharedMapActors;
import cn.net.rms.xaeromapsync_r.server.dirty.DirtyChunkStore;
import cn.net.rms.xaeromapsync_r.server.exploration.ExplorationTracker;
import cn.net.rms.xaeromapsync_r.server.exploration.ExploredChunkStore;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypointStore;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.server.activity.RegionActivityStore;
import cn.net.rms.xaeromapsync_r.server.activity.RegionActivitySample;
import cn.net.rms.xaeromapsync_r.server.activity.RegionActivityState;
import cn.net.rms.xaeromapsync_r.server.activity.RegionActivityThresholds;
import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public final class SharedMapServer {
	private static final Map<UUID, ServerClientState> CLIENTS = new ConcurrentHashMap<>();
	private static final Map<UUID, Optional<String>> CLIENT_TEAMS = new ConcurrentHashMap<>();
	private static final PublicWaypointStore WAYPOINTS = new PublicWaypointStore();
	private static final ExploredChunkStore EXPLORED_CHUNKS = new ExploredChunkStore();
	private static final DirtyChunkStore DIRTY_CHUNKS = new DirtyChunkStore();
	private static final MapTileIndexStore MAP_TILES = new MapTileIndexStore();
	private static final MapTileDataStore TILE_DATA = new MapTileDataStore();
	private static final NetworkBudgetTracker NETWORK_BUDGET = new NetworkBudgetTracker();
	private static final SharedMapAccessManager ACCESS = new SharedMapAccessManager();
	private static final SharedMapPermissionPolicy PERMISSIONS = new SharedMapPermissionPolicy(ACCESS.regions());
	private static final RegionActivityThresholds ACTIVITY_THRESHOLDS = new RegionActivityThresholds(
			SharedMapConfig.stormBlockChangesThreshold(),
			SharedMapConfig.stormDirtyChunksThreshold(),
			SharedMapConfig.stormTntEntitiesThreshold(),
			SharedMapConfig.stormExplosionsThreshold(),
			SharedMapConfig.stormPistonActionsThreshold(),
			SharedMapConfig.stormLightUpdatesThreshold(),
			SharedMapConfig.stormCooldownTicks(),
			SharedMapConfig.stableTicks());
	private static final RegionActivityStore ACTIVITY = new RegionActivityStore(ACTIVITY_THRESHOLDS);
	private static final MapTaskScheduler MAP_TASKS = new MapTaskScheduler(DIRTY_CHUNKS, MAP_TILES, TILE_DATA, ACTIVITY);
	private static final Map<RegionKey, TickActivity> TICK_ACTIVITY = new LinkedHashMap<>();
	private static final Set<RegionKey> KNOWN_REGIONS = new LinkedHashSet<>();
	private static final ServerTransferManager TRANSFERS = new ServerTransferManager(NETWORK_BUDGET,
			cn.net.rms.xaeromapsync_r.network.SharedMapNetworking::sendTransferPart);
	private static int persistenceTicks;
	private static int teamVisibilityTicks;

	private SharedMapServer() {
	}

	public static void register() {
		NETWORK_BUDGET.setBytesPerPlayerPerTick(SharedMapConfig.bytesPerPlayerPerTick());
		NETWORK_BUDGET.setGlobalBytesPerTick(SharedMapConfig.globalBytesPerTick());
		SharedMapCommands.register();
		ExplorationTracker.register();
		MAP_TASKS.register();
		TRANSFERS.register();
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			flushRegionActivity();
			if (++teamVisibilityTicks >= 20) {
				teamVisibilityTicks = 0;
				refreshChangedTeamVisibility(server);
			}
			if (++persistenceTicks >= 1_200) {
				persistenceTicks = 0;
				WAYPOINTS.save(server);
				EXPLORED_CHUNKS.save(server);
				DIRTY_CHUNKS.save(server);
				MAP_TILES.save(server);
				ACCESS.save(server);
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TILE_DATA.start(server);
			WAYPOINTS.load(server);
			EXPLORED_CHUNKS.load(server);
			DIRTY_CHUNKS.load(server);
			MAP_TILES.load(server);
			int recoveredTiles = TILE_DATA.recoverIndex(MAP_TILES);
			if (recoveredTiles > 0) {
				XaeroMapsync_r.LOGGER.info("Recovered map tile index from {} cached tiles", recoveredTiles);
				MAP_TILES.save(server);
			}
			ACCESS.load(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			TILE_DATA.stop();
			WAYPOINTS.save(server);
			EXPLORED_CHUNKS.save(server);
			DIRTY_CHUNKS.save(server);
			MAP_TILES.save(server);
			ACCESS.save(server);
			CLIENTS.clear();
			CLIENT_TEAMS.clear();
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			CLIENTS.remove(handler.player.getUUID());
			CLIENT_TEAMS.remove(handler.player.getUUID());
		});
	}

	public static void recordHandshake(ServerPlayer player, ClientHelloPayload hello, boolean accepted) {
		CLIENTS.put(player.getUUID(), new ServerClientState(player.getUUID(), player.getGameProfile().getName(), accepted));
		if (accepted) {
			CLIENT_TEAMS.put(player.getUUID(), currentTeam(player));
			XaeroMapsync_r.LOGGER.info("Shared map client accepted: {} protocol={} mapFormat={} xaeroAdapter={} compression={} maxPacketBytes={}",
					player.getGameProfile().getName(),
					hello.protocolVersion(),
					hello.mapFormatVersion(),
					hello.xaeroAdapterVersion(),
					hello.compression(),
					hello.maxPacketBytes());
			return;
		}
		CLIENT_TEAMS.remove(player.getUUID());
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

	private static void refreshChangedTeamVisibility(net.minecraft.server.MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!hasAcceptedClient(player.getUUID())) {
				continue;
			}
			Optional<String> currentTeam = currentTeam(player);
			Optional<String> previousTeam = CLIENT_TEAMS.put(player.getUUID(), currentTeam);
			if (previousTeam != null && !previousTeam.equals(currentTeam)) {
				cn.net.rms.xaeromapsync_r.network.SharedMapNetworking.refreshWaypointVisibility(player);
			}
		}
	}

	private static Optional<String> currentTeam(ServerPlayer player) {
		return Optional.ofNullable(SharedMapActors.from(player).teamName());
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
	public static SharedMapAccessManager access() { return ACCESS; }

	public static synchronized void recordBlockChange(String dimension, BlockPos pos) {
		DIRTY_CHUNKS.markDirty(dimension, pos);
		TickActivity tick = activityAt(dimension, pos.getX(), pos.getZ());
		tick.blockChanges = incrementUpTo(tick.blockChanges, ACTIVITY_THRESHOLDS.blockChanges());
		tick.dirtyChunks.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
	}

	public static synchronized void recordTntEntity(String dimension, int blockX, int blockZ) {
		TickActivity tick = activityAt(dimension, blockX, blockZ);
		tick.tntEntities = incrementUpTo(tick.tntEntities, ACTIVITY_THRESHOLDS.tntEntities());
	}

	public static synchronized void recordExplosion(String dimension, int blockX, int blockZ) {
		TickActivity tick = activityAt(dimension, blockX, blockZ);
		tick.explosions = incrementUpTo(tick.explosions, ACTIVITY_THRESHOLDS.explosions());
	}

	public static synchronized void recordPistonAction(String dimension, int blockX, int blockZ) {
		TickActivity tick = activityAt(dimension, blockX, blockZ);
		tick.pistonActions = incrementUpTo(tick.pistonActions, ACTIVITY_THRESHOLDS.pistonActions());
	}

	public static synchronized void recordLightUpdate(String dimension, int blockX, int blockZ) {
		TickActivity tick = activityAt(dimension, blockX, blockZ);
		tick.lightUpdates = incrementUpTo(tick.lightUpdates, ACTIVITY_THRESHOLDS.lightUpdates());
	}

	/** Stable optional boundary for Carpet extensions; this mod has no Carpet compile-time dependency. */
	public static synchronized void recordUpdateSuppression(String dimension, int blockX, int blockZ) {
		RegionKey key = RegionKey.fromBlock(dimension, blockX, blockZ);
		KNOWN_REGIONS.add(key);
		ACTIVITY.markStorm(key);
	}

	private static synchronized void flushRegionActivity() {
		Iterator<RegionKey> regions = KNOWN_REGIONS.iterator();
		while (regions.hasNext()) {
			RegionKey key = regions.next();
			TickActivity tick = TICK_ACTIVITY.get(key);
			RegionActivitySample sample = tick == null ? RegionActivitySample.EMPTY : tick.sample();
			ACTIVITY.recordTick(key, sample);
			if (!sample.hasActivity() && ACTIVITY.get(key)
					.map(record -> record.state() == RegionActivityState.QUIET
							|| record.state() == RegionActivityState.STABLE)
					.orElse(true)) {
				regions.remove();
			}
		}
		TICK_ACTIVITY.clear();
	}

	private static TickActivity activityAt(String dimension, int blockX, int blockZ) {
		RegionKey key = RegionKey.fromBlock(dimension, blockX, blockZ);
		KNOWN_REGIONS.add(key);
		return TICK_ACTIVITY.computeIfAbsent(key, ignored -> new TickActivity());
	}

	private static int incrementUpTo(int current, int limit) {
		return current < limit ? current + 1 : limit;
	}

	public static RegionActivityStore activity() { return ACTIVITY; }

	private static final class TickActivity {
		private int blockChanges;
		private int tntEntities;
		private int explosions;
		private int pistonActions;
		private int lightUpdates;
		private final Set<Long> dirtyChunks = new LinkedHashSet<>();

		private RegionActivitySample sample() {
			return new RegionActivitySample(
					blockChanges,
					dirtyChunks.size(),
					tntEntities,
					explosions,
					pistonActions,
					lightUpdates);
		}
	}
}
