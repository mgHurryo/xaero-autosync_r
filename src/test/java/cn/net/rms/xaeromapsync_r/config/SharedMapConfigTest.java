package cn.net.rms.xaeromapsync_r.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class SharedMapConfigTest {
	@Test
	void invalidHotPathIntegersAreNormalizedAtLoadTime() {
		assertEquals(64, SharedMapConfig.normalizedPositiveInteger("64", 8));
		assertEquals(8, SharedMapConfig.normalizedPositiveInteger("broken", 8));
		assertEquals(8, SharedMapConfig.normalizedPositiveInteger("0", 8));
		assertEquals(8, SharedMapConfig.normalizedPositiveInteger("-1", 8));
	}
}
