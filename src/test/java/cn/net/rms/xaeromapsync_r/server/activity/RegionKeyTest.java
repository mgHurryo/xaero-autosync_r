package cn.net.rms.xaeromapsync_r.server.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RegionKeyTest {
	@Test
	void mapsChunkBoundariesWithFloorDivision() {
		assertEquals(new RegionKey("minecraft:overworld", 0, 0), RegionKey.fromChunk("minecraft:overworld", 0, 7));
		assertEquals(new RegionKey("minecraft:overworld", 1, 1), RegionKey.fromChunk("minecraft:overworld", 8, 15));
		assertEquals(new RegionKey("minecraft:overworld", -1, -1), RegionKey.fromChunk("minecraft:overworld", -1, -8));
		assertEquals(new RegionKey("minecraft:overworld", -2, -2), RegionKey.fromChunk("minecraft:overworld", -9, -16));
	}

	@Test
	void mapsBlockBoundariesWithFloorDivision() {
		assertEquals(new RegionKey("minecraft:the_nether", 0, 0), RegionKey.fromBlock("minecraft:the_nether", 127, 0));
		assertEquals(new RegionKey("minecraft:the_nether", 1, 1), RegionKey.fromBlock("minecraft:the_nether", 128, 255));
		assertEquals(new RegionKey("minecraft:the_nether", -1, -1), RegionKey.fromBlock("minecraft:the_nether", -1, -128));
		assertEquals(new RegionKey("minecraft:the_nether", -2, -2), RegionKey.fromBlock("minecraft:the_nether", -129, -256));
	}

	@Test
	void rejectsMissingOrBlankDimension() {
		assertThrows(NullPointerException.class, () -> new RegionKey(null, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new RegionKey("  ", 0, 0));
	}
}
