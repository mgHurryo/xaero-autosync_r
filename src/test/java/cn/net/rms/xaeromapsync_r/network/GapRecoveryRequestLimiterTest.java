package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GapRecoveryRequestLimiterTest {
	@Test
	void boundsPeerFanOutAndRecoversAfterTheWindow() {
		GapRecoveryRequestLimiter limiter = new GapRecoveryRequestLimiter();
		UUID player = UUID.randomUUID();

		for (int index = 0; index < 4; index++) assertTrue(limiter.acquire(player, 1_000L + index));
		assertFalse(limiter.acquire(player, 1_004L));
		assertTrue(limiter.acquire(player, 2_000L));
	}
}
