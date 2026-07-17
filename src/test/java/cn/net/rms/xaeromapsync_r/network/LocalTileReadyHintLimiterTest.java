package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class LocalTileReadyHintLimiterTest {
	private static final UUID PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000001");

	@Test
	void defaultHintWindowSupportsElytraScaleLocalTileDiscovery() {
		assertEquals(16_384, LocalTileReadyHintLimiter.DEFAULT_MAX_HINTS_PER_WINDOW);
		assertEquals(1_000L, LocalTileReadyHintLimiter.DEFAULT_DUPLICATE_COOLDOWN_MILLIS);
	}

	@Test
	void rejectsDuplicatesAndReleasesThemAfterCooldown() {
		LocalTileReadyHintLimiter limiter = new LocalTileReadyHintLimiter(10, 1_000L, 500L);
		LocalTileReadyPayload hint = hint(1, 2, 3L);

		assertEquals(LocalTileReadyHintLimiter.Result.ACCEPTED, limiter.acquire(PLAYER, hint, 0L));
		assertEquals(LocalTileReadyHintLimiter.Result.DUPLICATE, limiter.acquire(PLAYER, hint, 100L));
		assertEquals(LocalTileReadyHintLimiter.Result.ACCEPTED, limiter.acquire(PLAYER, hint, 500L));
		limiter.remove(PLAYER);
		assertEquals(LocalTileReadyHintLimiter.Result.ACCEPTED, limiter.acquire(PLAYER, hint, 501L));
	}

	@Test
	void duplicateCheckHandlesMissingAndReversedTimestamps() {
		assertTrue(LocalTileReadyHintLimiter.isDuplicate(100L, 200L, 500L));
		assertFalse(LocalTileReadyHintLimiter.isDuplicate(null, 200L, 500L));
		assertFalse(LocalTileReadyHintLimiter.isDuplicate(300L, 200L, 500L));
	}

	@Test
	void boundsHintsPerPlayerAndResetsTheWindow() {
		LocalTileReadyHintLimiter limiter = new LocalTileReadyHintLimiter(2, 100L, 0L);

		assertEquals(LocalTileReadyHintLimiter.Result.ACCEPTED, limiter.acquire(PLAYER, hint(1, 1, 1L), 0L));
		assertEquals(LocalTileReadyHintLimiter.Result.ACCEPTED, limiter.acquire(PLAYER, hint(2, 2, 2L), 1L));
		assertEquals(LocalTileReadyHintLimiter.Result.RATE_LIMITED, limiter.acquire(PLAYER, hint(3, 3, 3L), 2L));
		assertEquals(LocalTileReadyHintLimiter.Result.ACCEPTED, limiter.acquire(PLAYER, hint(3, 3, 3L), 100L));
	}

	private static LocalTileReadyPayload hint(int chunkX, int chunkZ, long hash) {
		return new LocalTileReadyPayload("minecraft:overworld", chunkX, chunkZ, hash);
	}
}
