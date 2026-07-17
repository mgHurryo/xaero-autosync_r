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
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

public final class MapTileDebugRenderer {
	public static final int SURFACE_SAMPLER_VERSION = 4;
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
		String[] biomeKeys = new String[TILE_AREA];
		byte[] lightAbove = new byte[TILE_AREA];
		boolean[] glowing = new boolean[TILE_AREA];
		boolean[] cave = new boolean[TILE_AREA];
		List<List<MapTile.Overlay>> overlays = new ArrayList<>(TILE_AREA);
		Registry<Biome> biomeRegistry =
				level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
		BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

		for (int localZ = 0; localZ < TILE_SIDE; localZ++) {
			for (int localX = 0; localX < TILE_SIDE; localX++) {
				int worldX = (chunkX << 4) + localX;
				int worldZ = (chunkZ << 4) + localZ;
				int index = localZ * TILE_SIDE + localX;
				// ChunkAccess#getHeight already returns the highest matching block Y in 1.17.1.
				int surfaceY = surfaceY(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ));
				ColumnSurface surface = scanColumn(level, chunk, worldX, worldZ, localX, localZ, surfaceY, scanPos, lightPos);
				baseStateIds[index] = Block.getId(surface.baseState);
				baseHeights[index] = surface.baseHeight;
				topHeights[index] = surface.topHeight;
				scanPos.set(worldX, surface.topHeight, worldZ);
				biomeKeys[index] = biomeKey(biomeRegistry, level.getBiome(scanPos), scanPos);
				lightAbove[index] = surface.lightAbove;
				glowing[index] = isGlowing(surface.baseState);
				cave[index] = !level.dimensionType().hasSkyLight();
				overlays.add(surface.overlays);
			}
		}

		long hash = MapTileHasher.hashSurface(baseStateIds, baseHeights, topHeights, biomeKeys, lightAbove, glowing, cave,
				overlays);
		MapTile tile = new MapTile(level.dimension().location().toString(), chunkX, chunkZ, baseStateIds, baseHeights,
				topHeights, biomeKeys, lightAbove, glowing, cave, overlays, hash);
		XaeroMapsync_r.LOGGER.debug("Rendered surface tile {} {} {} hash={}", tile.dimension(), tile.chunkX(), tile.chunkZ(),
				tile.contentHash());
		return tile;
	}

	static int surfaceY(int heightmapY) {
		return heightmapY;
	}

	static String biomeKey(Registry<Biome> registry, Biome biome, BlockPos pos) {
		return registry.getResourceKey(biome)
				.orElseThrow(() -> new IllegalStateException("Biome is not registered at " + pos))
				.location().toString();
	}

	private static ColumnSurface scanColumn(ServerLevel level, ChunkAccess chunk, int worldX, int worldZ, int localX,
			int localZ, int surfaceY, BlockPos.MutableBlockPos scanPos, BlockPos.MutableBlockPos lightPos) {
		List<MapTile.Overlay> overlays = new ArrayList<>();
		int topHeight = Integer.MIN_VALUE;
		int minimumY = level.getMinBuildHeight();
		for (int y = surfaceY; y >= minimumY; y--) {
			scanPos.set(worldX, y, worldZ);
			BlockState state = chunk.getBlockState(scanPos);
			lightPos.set(worldX, y + 1, worldZ);
			byte sampledLight = (byte) level.getBrightness(LightLayer.BLOCK, lightPos);
			FluidState fluid = state.getFluidState();
			if (!fluid.isEmpty()) {
				BlockState fluidState = fluid.createLegacyBlock();
				if (isFluidOverlay(fluidState)) {
					if (topHeight == Integer.MIN_VALUE) topHeight = y;
					addOverlay(overlays, fluidState, sampledLight, fluidState.getLightBlock(level, scanPos));
				} else {
					if (topHeight == Integer.MIN_VALUE) topHeight = y;
					return new ColumnSurface(fluidState, y, topHeight, sampledLight, List.copyOf(overlays));
				}
				if (state.getBlock() instanceof LiquidBlock) continue;
			}
			if (isInvisible(state, level, scanPos)) continue;
			if (isOverlay(state)) {
				if (topHeight == Integer.MIN_VALUE) topHeight = y;
				addOverlay(overlays, state, sampledLight, state.getLightBlock(level, scanPos));
				continue;
			}
			if (topHeight == Integer.MIN_VALUE) topHeight = y;
			return new ColumnSurface(state, y, topHeight, sampledLight, List.copyOf(overlays));
		}

		int fallbackHeight = topHeight == Integer.MIN_VALUE ? minimumY : topHeight;
		lightPos.set(worldX, fallbackHeight + 1, worldZ);
		return new ColumnSurface(Blocks.AIR.defaultBlockState(), minimumY, fallbackHeight,
				(byte) level.getBrightness(LightLayer.BLOCK, lightPos), List.copyOf(overlays));
	}

	static boolean isFluidOverlay(BlockState state) {
		return state.getFluidState().getType() == Fluids.WATER
				|| state.getFluidState().getType() == Fluids.FLOWING_WATER;
	}

	static boolean isOverlay(BlockState state) {
		return state.getBlock() instanceof HalfTransparentBlock || state.getBlock() instanceof AbstractGlassBlock;
	}

	private static boolean isInvisible(BlockState state, ServerLevel level, BlockPos pos) {
		if (isAlwaysInvisible(state)) return true;
		return !isOverlay(state) && state.getCollisionShape(level, pos).isEmpty();
	}

	static boolean isAlwaysInvisible(BlockState state) {
		return state.isAir() || state.getBlock() == Blocks.GRASS || state.getBlock() instanceof TorchBlock
				|| state.getBlock() instanceof BaseFireBlock;
	}

	private static void addOverlay(List<MapTile.Overlay> overlays, BlockState state, byte lightAbove, int opacity) {
		int stateId = Block.getId(state);
		appendOverlay(overlays, new MapTile.Overlay(stateId, transparency(state), lightAbove, isGlowing(state), opacity));
	}

	static void appendOverlay(List<MapTile.Overlay> overlays, MapTile.Overlay overlay) {
		if (!overlays.isEmpty()) {
			int lastIndex = overlays.size() - 1;
			MapTile.Overlay previous = overlays.get(lastIndex);
			if (previous.blockStateId() == overlay.blockStateId()
					&& Float.compare(previous.transparency(), overlay.transparency()) == 0
					&& previous.glowing() == overlay.glowing()) {
				int opacity = Math.min(Short.MAX_VALUE, previous.opacity() + overlay.opacity());
				overlays.set(lastIndex, new MapTile.Overlay(previous.blockStateId(), previous.transparency(),
						previous.lightAbove(), previous.glowing(), opacity));
				return;
			}
		}
		if (overlays.size() < MapTile.MAX_OVERLAYS_PER_COLUMN) overlays.add(overlay);
	}

	static float transparency(BlockState state) {
		return transparency(state.getBlock() instanceof LiquidBlock, state.getBlock() instanceof IceBlock);
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
