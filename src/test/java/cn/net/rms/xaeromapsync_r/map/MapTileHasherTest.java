package cn.net.rms.xaeromapsync_r.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.net.rms.xaeromapsync_r.config.SharedMapProtocolDefaults;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MapTileHasherTest {
	@Test
	void modelAndProtocolAdvertiseMapFormatFive() {
		assertEquals(6, MapTile.FORMAT_VERSION);
		assertEquals(MapTile.FORMAT_VERSION, SharedMapProtocolDefaults.MAP_FORMAT_VERSION);
	}

	@Test
	void hashHeightsReturnsStableFnvVectors() {
		assertEquals(0xcbf29ce484222325L, MapTileHasher.hashHeights(new int[0]));
		assertEquals(0xaf63bd4c8601b7dfL, MapTileHasher.hashHeights(new int[] {0}));
		assertEquals(0xd0aa6218672cf5abL, MapTileHasher.hashHeights(new int[] {1, 2, 3}));
		assertEquals(0xbb7e1f806715ace9L, MapTileHasher.hashHeights(new int[] {0, 1, -2, 255}));
	}

	@Test
	void combineIsStableAndOrderSensitive() {
		assertEquals(0xd0aa6218672cf5abL, MapTileHasher.combine(1L, 2L, 3L));
		assertEquals(0x51d2b8263b9f0e64L, MapTileHasher.combine(0x1122334455667788L, -5L));
		assertNotEquals(MapTileHasher.combine(1L, 2L, 3L), MapTileHasher.combine(3L, 2L, 1L));
	}

	@Test
	void v5SurfaceHashIncludesOverlayOrderOpacityAndNativeFlags() {
		int[] values = new int[] {1};
		byte[] lights = new byte[] {7};
		boolean[] disabled = new boolean[] {false};
		List<List<MapTile.Overlay>> firstOrder = List.of(List.of(
				new MapTile.Overlay(4, 0.66F, (byte) 8, false),
				new MapTile.Overlay(5, 0.5F, (byte) 3, true)));
		List<List<MapTile.Overlay>> reversed = List.of(List.of(
				new MapTile.Overlay(5, 0.5F, (byte) 3, true),
				new MapTile.Overlay(4, 0.66F, (byte) 8, false)));

		long original = MapTileHasher.hashSurface(values, values, values, values, lights, disabled, disabled, firstOrder);
		long reordered = MapTileHasher.hashSurface(values, values, values, values, lights, disabled, disabled, reversed);
		boolean[] glowing = new boolean[] {true};
		long flagged = MapTileHasher.hashSurface(values, values, values, values, lights, glowing, disabled, firstOrder);
		List<List<MapTile.Overlay>> opaque = List.of(List.of(
				new MapTile.Overlay(4, 0.66F, (byte) 8, false, 3),
				new MapTile.Overlay(5, 0.5F, (byte) 3, true)));
		long opacityChanged = MapTileHasher.hashSurface(values, values, values, values, lights, disabled, disabled, opaque);

		assertNotEquals(original, reordered);
		assertNotEquals(original, flagged);
		assertNotEquals(original, opacityChanged);
	}
}
