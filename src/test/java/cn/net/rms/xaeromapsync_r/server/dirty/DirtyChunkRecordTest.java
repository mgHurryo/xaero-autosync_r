package cn.net.rms.xaeromapsync_r.server.dirty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class DirtyChunkRecordTest {
	@Test
	void newRecordStartsActiveAtInitialTick() {
		DirtyChunkRecord record = new DirtyChunkRecord("minecraft:overworld", 2, -3, 10L);

		assertEquals("minecraft:overworld", record.dimension());
		assertEquals(2, record.chunkX());
		assertEquals(-3, record.chunkZ());
		assertEquals(DirtyActivityState.ACTIVE, record.state());
		assertEquals(10L, record.firstDirtyTick());
		assertEquals(10L, record.lastDirtyTick());
		assertEquals(0, record.dirtyColumnCount());
	}

	@Test
	void markColumnTracksMaskedLocalColumnAndLastTick() {
		DirtyChunkRecord record = new DirtyChunkRecord("minecraft:overworld", 0, 0, 1L);

		record.markColumn(16, -1, 8L);

		assertEquals(DirtyActivityState.ACTIVE, record.state());
		assertEquals(8L, record.lastDirtyTick());
		assertEquals(1, record.dirtyColumnCount());
		assertArrayEquals(new long[] {0L, 0L, 0L, 1L << 48}, record.dirtyColumnsAsLongArray());
	}

	@Test
	void repeatedChangesEnterStormThenCooldownThenStable() {
		DirtyChunkRecord record = new DirtyChunkRecord("minecraft:overworld", 0, 0, 1L);

		for (int index = 0; index < 512; index++) {
			record.markColumn(index, index / 16, 50L);
		}

		assertEquals(DirtyActivityState.STORM, record.state());

		record.advance(149L);
		assertEquals(DirtyActivityState.STORM, record.state());

		record.advance(150L);
		assertEquals(DirtyActivityState.COOLDOWN, record.state());

		record.advance(250L);
		assertEquals(DirtyActivityState.STABLE, record.state());
	}

	@Test
	void activeRecordBecomesStableAfterQuietPeriod() {
		DirtyChunkRecord record = new DirtyChunkRecord("minecraft:overworld", 0, 0, 1L);
		record.markColumn(3, 4, 20L);

		record.advance(219L);
		assertEquals(DirtyActivityState.ACTIVE, record.state());

		record.advance(220L);
		assertEquals(DirtyActivityState.STABLE, record.state());
	}

	@Test
	void restoreReplacesStateTicksAndDirtyColumns() {
		DirtyChunkRecord record = new DirtyChunkRecord("minecraft:overworld", 0, 0, 1L);
		record.markColumn(1, 1, 2L);

		record.restore(DirtyActivityState.COOLDOWN, 5L, 9L, new long[] {3L});

		assertEquals(DirtyActivityState.COOLDOWN, record.state());
		assertEquals(5L, record.firstDirtyTick());
		assertEquals(9L, record.lastDirtyTick());
		assertEquals(2, record.dirtyColumnCount());
		assertArrayEquals(new long[] {3L}, record.dirtyColumnsAsLongArray());
	}
}
