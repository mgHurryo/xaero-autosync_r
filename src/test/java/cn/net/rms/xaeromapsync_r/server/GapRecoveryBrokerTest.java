package cn.net.rms.xaeromapsync_r.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.network.TileRequestPayload;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GapRecoveryBrokerTest {
	@Test
	void coalescesRequestersAndExpiresAfterTheShortPeerWindow() {
		GapRecoveryBroker broker = new GapRecoveryBroker();
		TileRequestPayload request = new TileRequestPayload("minecraft:overworld", 4, 5, 0L);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		assertEquals(GapRecoveryBroker.RequestResult.NEW, broker.request(first, request, 1_000L));
		assertEquals(GapRecoveryBroker.RequestResult.COALESCED, broker.request(second, request, 1_010L));
		assertTrue(broker.expire(1_749L, 10).isEmpty());
		assertEquals(2, broker.expire(1_750L, 10).size());
	}

	@Test
	void publishedTileResolvesEveryWaitingRequester() {
		GapRecoveryBroker broker = new GapRecoveryBroker();
		TileRequestPayload request = new TileRequestPayload("minecraft:overworld", -2, 7, 3L);
		broker.request(UUID.randomUUID(), request, 0L);

		assertEquals(1, broker.resolve(request.dimension(), request.chunkX(), request.chunkZ()).size());
		assertTrue(broker.expire(Long.MAX_VALUE, 10).isEmpty());
	}
}
