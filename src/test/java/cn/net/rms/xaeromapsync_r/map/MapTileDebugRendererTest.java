package cn.net.rms.xaeromapsync_r.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.Test;

final class MapTileDebugRendererTest {
	@Test
	void heightmapSurfaceIsNotMovedDownIntoDirt() {
		assertEquals(64, MapTileDebugRenderer.surfaceY(64));
	}

	@Test
	void biomeKeyUsesTheDynamicRegistryAndRejectsUnknownBiomes() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		Registry<Biome> registry = RegistryAccess.builtin().registryOrThrow(Registry.BIOME_REGISTRY);
		Biome plains = Objects.requireNonNull(registry.get(Biomes.PLAINS));

		assertEquals("minecraft:plains", MapTileDebugRenderer.biomeKey(registry, plains, BlockPos.ZERO));
		BlockPos unknownPosition = new BlockPos(3, 64, 4);
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> MapTileDebugRenderer.biomeKey(registry, null, unknownPosition));
		assertTrue(failure.getMessage().contains(unknownPosition.toString()));
	}

	@Test
	void transparencyMatchesXaeroOverlayWeights() {
		assertEquals(0.66F, MapTileDebugRenderer.transparency(true, false));
		assertEquals(0.83F, MapTileDebugRenderer.transparency(false, true));
		assertEquals(0.5F, MapTileDebugRenderer.transparency(false, false));
	}

	@Test
	void glowingUsesBlockStateLightEmission() {
		assertTrue(MapTileDebugRenderer.isGlowing(1));
		assertTrue(MapTileDebugRenderer.isGlowing(15));
		assertFalse(MapTileDebugRenderer.isGlowing(0));
	}

	@Test
	void repeatedWaterLayersMergeAndAccumulateXaeroOpacity() {
		List<MapTile.Overlay> overlays = new ArrayList<>();
		MapTile.Overlay water = new MapTile.Overlay(7, 0.66F, (byte) 12, false, 1);
		for (int depth = 0; depth < 6; depth++) MapTileDebugRenderer.appendOverlay(overlays, water);

		assertEquals(1, overlays.size());
		assertEquals(6, overlays.get(0).opacity());
	}

	@Test
	void xaeroSurfaceClassificationKeepsGrassLeavesAndLavaAsBases() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		assertTrue(MapTileDebugRenderer.isAlwaysInvisible(Blocks.GRASS.defaultBlockState()));
		assertFalse(MapTileDebugRenderer.isAlwaysInvisible(Blocks.GRASS_BLOCK.defaultBlockState()));
		assertTrue(MapTileDebugRenderer.isOverlay(Blocks.GLASS.defaultBlockState()));
		assertTrue(MapTileDebugRenderer.isOverlay(Blocks.ICE.defaultBlockState()));
		assertFalse(MapTileDebugRenderer.isOverlay(Blocks.OAK_LEAVES.defaultBlockState()));
		assertTrue(MapTileDebugRenderer.isFluidOverlay(Blocks.WATER.defaultBlockState()));
		assertFalse(MapTileDebugRenderer.isFluidOverlay(Blocks.LAVA.defaultBlockState()));
	}

	@Test
	void viewOffsetsCoverSquareAndAreNearestFirst() {
		List<ChunkPos> offsets = MapTileDebugRenderer.viewOffsets(2);
		assertEquals(25, offsets.size());
		assertEquals(new ChunkPos(0, 0), offsets.get(0));
		int previousDistance = -1;
		for (ChunkPos offset : offsets) {
			int distance = offset.x * offset.x + offset.z * offset.z;
			assertTrue(distance >= previousDistance);
			previousDistance = distance;
		}
		assertThrows(IllegalArgumentException.class, () -> MapTileDebugRenderer.viewOffsets(-1));
	}
}
