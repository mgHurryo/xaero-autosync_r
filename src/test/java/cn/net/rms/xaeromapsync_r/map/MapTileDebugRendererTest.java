package cn.net.rms.xaeromapsync_r.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

final class MapTileDebugRendererTest {
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
	void repeatedWaterLayersRemainOrderedUntilFiveLayerLimit() {
		List<MapTile.Overlay> overlays = new ArrayList<>();
		MapTile.Overlay water = new MapTile.Overlay(7, 0.66F, (byte) 12, false);
		for (int depth = 0; depth < 6; depth++) MapTileDebugRenderer.appendOverlay(overlays, water);

		assertEquals(5, overlays.size());
		assertEquals(List.of(water, water, water, water, water), overlays);
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
