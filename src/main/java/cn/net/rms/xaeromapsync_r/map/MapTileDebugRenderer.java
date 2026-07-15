package cn.net.rms.xaeromapsync_r.map;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

public final class MapTileDebugRenderer {
	private static final int TILE_SIDE = 16;
	private static final int TILE_AREA = TILE_SIDE * TILE_SIDE;
	private static final float WATER_TRANSPARENCY = 0.66F;
	private static final float ICE_TRANSPARENCY = 0.83F;
	private static final float DEFAULT_TRANSPARENCY = 0.5F;

	private MapTileDebugRenderer() {
	}

	public static int renderLoadedPlayerChunks(MinecraftServer server) {
		int count = 0;
		for (LoadedChunk candidate : loadedChunksNearPlayers(server)) {
			if (renderIfLoaded(candidate.level, candidate.chunkX, candidate.chunkZ) != null) count++;
		}
		return count;
	}

	public static int renderAndIndexLoadedPlayerChunks(MinecraftServer server, MapTileIndexStore indexStore) {
		return renderAndIndexLoadedPlayerChunks(server, indexStore, null);
	}

	public static int renderAndIndexLoadedPlayerChunks(MinecraftServer server, MapTileIndexStore indexStore,
			MapTileDataStore dataStore) {
		int count = 0;
		for (LoadedChunk candidate : loadedChunksNearPlayers(server)) {
			MapTile tile = renderIfLoaded(candidate.level, candidate.chunkX, candidate.chunkZ);
			if (tile == null) continue;
			if (dataStore != null && !dataStore.putSynchronously(tile)) {
				XaeroMapsync_r.LOGGER.warn("Skipping map index update because tile persistence failed for {} {} {}",
						tile.dimension(), tile.chunkX(), tile.chunkZ());
				continue;
			}
			indexStore.upsert(tile);
			count++;
		}
		return count;
	}

	public static MapTile renderIfLoaded(ServerLevel level, int chunkX, int chunkZ) {
		if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) return null;
		ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
		if (chunk == null) return null;

		int[] baseStateIds = new int[TILE_AREA];
		int[] baseHeights = new int[TILE_AREA];
		int[] topHeights = new int[TILE_AREA];
		int[] biomeIds = new int[TILE_AREA];
		byte[] lightAbove = new byte[TILE_AREA];
		boolean[] glowing = new boolean[TILE_AREA];
		boolean[] cave = new boolean[TILE_AREA];
		List<List<MapTile.Overlay>> overlays = new ArrayList<>(TILE_AREA);
		Registry<net.minecraft.world.level.biome.Biome> biomeRegistry =
				level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
		BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

		for (int localZ = 0; localZ < TILE_SIDE; localZ++) {
			for (int localX = 0; localX < TILE_SIDE; localX++) {
				int worldX = (chunkX << 4) + localX;
				int worldZ = (chunkZ << 4) + localZ;
				int index = localZ * TILE_SIDE + localX;
				int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) - 1;
				ColumnSurface surface = scanColumn(level, chunk, worldX, worldZ, localX, localZ, surfaceY, scanPos, lightPos);
				baseStateIds[index] = Block.getId(surface.baseState);
				baseHeights[index] = surface.baseHeight;
				topHeights[index] = surface.topHeight;
				scanPos.set(worldX, surface.topHeight, worldZ);
				biomeIds[index] = biomeRegistry.getId(level.getBiome(scanPos));
				lightAbove[index] = surface.lightAbove;
				glowing[index] = isGlowing(surface.baseState);
				cave[index] = !level.dimensionType().hasSkyLight();
				overlays.add(surface.overlays);
			}
		}

		long hash = MapTileHasher.hashSurface(baseStateIds, baseHeights, topHeights, biomeIds, lightAbove, glowing, cave,
				overlays);
		MapTile tile = new MapTile(level.dimension().location().toString(), chunkX, chunkZ, baseStateIds, baseHeights,
				topHeights, biomeIds, lightAbove, glowing, cave, overlays, hash);
		XaeroMapsync_r.LOGGER.debug("Rendered surface tile {} {} {} hash={}", tile.dimension(), tile.chunkX(), tile.chunkZ(),
				tile.contentHash());
		return tile;
	}

	private static ColumnSurface scanColumn(ServerLevel level, ChunkAccess chunk, int worldX, int worldZ, int localX,
			int localZ, int surfaceY, BlockPos.MutableBlockPos scanPos, BlockPos.MutableBlockPos lightPos) {
		List<MapTile.Overlay> overlays = new ArrayList<>();
		int topHeight = Integer.MIN_VALUE;
		int minimumY = level.getMinBuildHeight();
		for (int y = surfaceY; y >= minimumY; y--) {
			scanPos.set(localX, y, localZ);
			BlockState state = chunk.getBlockState(scanPos);
			if (state.isAir()) continue;
			if (topHeight == Integer.MIN_VALUE) topHeight = y;
			lightPos.set(worldX, y + 1, worldZ);
			byte sampledLight = (byte) level.getBrightness(LightLayer.BLOCK, lightPos);
			FluidState fluid = state.getFluidState();
			if (!fluid.isEmpty()) {
				addOverlay(overlays, fluid.createLegacyBlock(), sampledLight);
				if (!(state.getBlock() instanceof LiquidBlock)) {
					return new ColumnSurface(state, y, topHeight, sampledLight, List.copyOf(overlays));
				}
				continue;
			}
			if (state.getBlock() instanceof HalfTransparentBlock) {
				addOverlay(overlays, state, sampledLight);
				continue;
			}
			return new ColumnSurface(state, y, topHeight, sampledLight, List.copyOf(overlays));
		}

		int fallbackHeight = topHeight == Integer.MIN_VALUE ? minimumY : topHeight;
		lightPos.set(worldX, fallbackHeight + 1, worldZ);
		return new ColumnSurface(Blocks.AIR.defaultBlockState(), minimumY, fallbackHeight,
				(byte) level.getBrightness(LightLayer.BLOCK, lightPos), List.copyOf(overlays));
	}

	private static void addOverlay(List<MapTile.Overlay> overlays, BlockState state, byte lightAbove) {
		int stateId = Block.getId(state);
		appendOverlay(overlays, new MapTile.Overlay(stateId, transparency(state), lightAbove, isGlowing(state)));
	}

	static void appendOverlay(List<MapTile.Overlay> overlays, MapTile.Overlay overlay) {
		if (overlays.size() < MapTile.MAX_OVERLAYS_PER_COLUMN) overlays.add(overlay);
	}

	static float transparency(BlockState state) {
		return transparency(!state.getFluidState().isEmpty(), state.getBlock() instanceof IceBlock);
	}

	static float transparency(boolean water, boolean ice) {
		if (water) return WATER_TRANSPARENCY;
		if (ice) return ICE_TRANSPARENCY;
		return DEFAULT_TRANSPARENCY;
	}

	static boolean isGlowing(BlockState state) {
		return isGlowing(state.getLightEmission());
	}

	static boolean isGlowing(int lightEmission) {
		return lightEmission >= 1;
	}

	static List<ChunkPos> viewOffsets(int radius) {
		if (radius < 0) throw new IllegalArgumentException("View radius cannot be negative");
		List<ChunkPos> offsets = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) offsets.add(new ChunkPos(dx, dz));
		}
		offsets.sort(Comparator.comparingInt((ChunkPos offset) -> offset.x * offset.x + offset.z * offset.z)
				.thenComparingInt(offset -> offset.x).thenComparingInt(offset -> offset.z));
		return List.copyOf(offsets);
	}

	private static List<LoadedChunk> loadedChunksNearPlayers(MinecraftServer server) {
		int radius = Math.max(0, server.getPlayerList().getViewDistance() - 1);
		Map<String, LoadedChunk> candidates = new HashMap<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerLevel level = player.getLevel();
			ChunkPos center = player.chunkPosition();
			String dimension = level.dimension().location().toString();
			for (ChunkPos offset : viewOffsets(radius)) {
				int chunkX = center.x + offset.x;
				int chunkZ = center.z + offset.z;
				if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) continue;
				String key = dimension + ":" + ChunkPos.asLong(chunkX, chunkZ);
				int distanceSquared = offset.x * offset.x + offset.z * offset.z;
				LoadedChunk previous = candidates.get(key);
				if (previous == null || distanceSquared < previous.distanceSquared) {
					candidates.put(key, new LoadedChunk(level, dimension, chunkX, chunkZ, distanceSquared));
				}
			}
		}
		List<LoadedChunk> ordered = new ArrayList<>(candidates.values());
		ordered.sort(Comparator.comparingInt((LoadedChunk candidate) -> candidate.distanceSquared)
				.thenComparing(candidate -> candidate.dimension)
				.thenComparingInt(candidate -> candidate.chunkX)
				.thenComparingInt(candidate -> candidate.chunkZ));
		return ordered;
	}

	private record ColumnSurface(BlockState baseState, int baseHeight, int topHeight, byte lightAbove,
			List<MapTile.Overlay> overlays) {}

	private record LoadedChunk(ServerLevel level, String dimension, int chunkX, int chunkZ, int distanceSquared) {}
}
