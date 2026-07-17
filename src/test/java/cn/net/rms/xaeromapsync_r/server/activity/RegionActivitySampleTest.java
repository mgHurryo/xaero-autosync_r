package cn.net.rms.xaeromapsync_r.server.activity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RegionActivitySampleTest {
	@Test
	void detectsAnySpecializedActivity() {
		assertFalse(RegionActivitySample.EMPTY.hasActivity());
		assertTrue(new RegionActivitySample(0, 0, 1, 0, 0, 0).hasActivity());
		assertTrue(new RegionActivitySample(0, 0, 0, 1, 0, 0).hasActivity());
		assertTrue(new RegionActivitySample(0, 0, 0, 0, 1, 0).hasActivity());
		assertTrue(new RegionActivitySample(0, 0, 0, 0, 0, 1).hasActivity());
	}

	@Test
	void rejectsNegativeSpecializedCounts() {
		assertThrows(IllegalArgumentException.class, () -> new RegionActivitySample(0, 0, -1, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new RegionActivitySample(0, 0, 0, -1, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new RegionActivitySample(0, 0, 0, 0, -1, 0));
		assertThrows(IllegalArgumentException.class, () -> new RegionActivitySample(0, 0, 0, 0, 0, -1));
	}
}
