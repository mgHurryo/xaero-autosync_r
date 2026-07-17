package cn.net.rms.xaeromapsync_r.server.exploration;

import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.server.SharedMapServer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

public final class ExplorationTracker {
	private static final int SCAN_INTERVAL_TICKS = 20;
	private static final Map<Integer, List<ChunkOffset>> OFFSETS_BY_RADIUS = new HashMap<>();
	private static int ticksUntilScan = SCAN_INTERVAL_TICKS;

	private ExplorationTracker() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ExplorationTracker::tick);
	}

	private static void tick(MinecraftServer server) {
		if (!SharedMapConfig.serverMapRenderingEnabled()) return;
		ticksUntilScan--;
		if (ticksUntilScan > 0) {
			return;
		}
		ticksUntilScan = SCAN_INTERVAL_TICKS;
		int radius = Math.max(0, server.getPlayerList().getViewDistance() - 1);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			scanPlayer(player, radius);
		}
	}

	private static void scanPlayer(ServerPlayer player, int radius) {
		ServerLevel level = player.getLevel();
		ChunkPos center = player.chunkPosition();
		String dimension = level.dimension().location().toString();
		for (ChunkOffset offset : offsets(radius)) {
			int chunkX = center.x + offset.x;
			int chunkZ = center.z + offset.z;
			if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
				continue;
			}
			boolean firstObservation = SharedMapServer.exploredChunks().markExplored(dimension, chunkX, chunkZ);
			if (firstObservation || SharedMapServer.mapTiles().find(dimension, chunkX, chunkZ).isEmpty()) {
				SharedMapServer.dirtyChunks().markDiscovered(dimension, chunkX, chunkZ,
						SharedMapServer.hasAcceptedClient(player.getUUID()));
			}
		}
	}

	private static List<ChunkOffset> offsets(int radius) {
		return OFFSETS_BY_RADIUS.computeIfAbsent(radius, ExplorationTracker::createOffsets);
	}

	private static List<ChunkOffset> createOffsets(int radius) {
		List<ChunkOffset> offsets = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				offsets.add(new ChunkOffset(dx, dz));
			}
		}
		offsets.sort(Comparator.comparingInt(offset -> offset.x * offset.x + offset.z * offset.z));
		return List.copyOf(offsets);
	}

	private static final class ChunkOffset {
		private final int x;
		private final int z;

		private ChunkOffset(int x, int z) {
			this.x = x;
			this.z = z;
		}
	}
}
