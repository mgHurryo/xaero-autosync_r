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

	@Test
	void boundsGlobalFanOutAcrossRequesters() {
		GapRecoveryRequestLimiter limiter = new GapRecoveryRequestLimiter();
		for (int playerIndex = 0; playerIndex < 8; playerIndex++) {
			UUID player = UUID.randomUUID();
			for (int requestIndex = 0; requestIndex < 4; requestIndex++)
				assertTrue(limiter.acquire(player, 1_000L + requestIndex));
		}

		assertFalse(limiter.acquire(UUID.randomUUID(), 1_004L));
		assertTrue(limiter.acquire(UUID.randomUUID(), 2_000L));
	}
}
