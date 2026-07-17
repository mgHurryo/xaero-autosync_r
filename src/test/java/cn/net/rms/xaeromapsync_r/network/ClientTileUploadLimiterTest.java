package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ClientTileUploadLimiterTest {
	private static final UUID PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000002");

	@Test
	void defaultUploadWindowSupportsElytraScaleClientTileBursts() {
		assertEquals(16_384, ClientTileUploadLimiter.DEFAULT_PACKETS_PER_WINDOW);
		assertEquals(256L * 1024L * 1024L, ClientTileUploadLimiter.DEFAULT_BYTES_PER_WINDOW);
	}

	@Test
	void limitsPacketsAndCompressedBytesBeforeResettingWindow() {
		ClientTileUploadLimiter limiter = new ClientTileUploadLimiter(2, 100L, 1_000L);

		assertTrue(limiter.acquire(PLAYER, 60, 0L));
		assertFalse(limiter.acquire(PLAYER, 50, 1L));
		assertTrue(limiter.acquire(PLAYER, 40, 2L));
		assertFalse(limiter.acquire(PLAYER, 1, 3L));
		assertTrue(limiter.acquire(PLAYER, 100, 1_000L));
		limiter.remove(PLAYER);
		assertTrue(limiter.acquire(PLAYER, 100, 1_001L));
	}

	@Test
	void rejectsEmptyPackets() {
		assertFalse(new ClientTileUploadLimiter(1, 1L, 1L).acquire(PLAYER, 0, 0L));
	}
}
