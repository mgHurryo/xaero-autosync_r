package cn.net.rms.xaeromapsync_r.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

final class MapTileHasherTest {
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
}
