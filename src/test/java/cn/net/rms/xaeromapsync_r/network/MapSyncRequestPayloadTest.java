package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.xaeromapsync_r.map.MerkleNodeAddress;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class MapSyncRequestPayloadTest {
	@Test
	void rootHashRoundTripPreservesCorrelationIds() {
		MapRootHashPayload decoded = roundTrip(new MapRootHashPayload(
				"minecraft:the_nether", -19L, 701L, 702L));

		assertEquals(701L, decoded.syncId());
		assertEquals(702L, decoded.requestId());
		assertEquals("minecraft:the_nether", decoded.dimension());
		assertEquals(-19L, decoded.knownRootHash());
	}

	@Test
	void nodeRequestRoundTripPreservesCorrelationIds() {
		MapNodeRequestPayload decoded = roundTrip(new MapNodeRequestPayload(801L, 802L,
				List.of(new MerkleNodeAddress("minecraft:overworld", 3, -7, 11))));

		assertEquals(801L, decoded.syncId());
		assertEquals(802L, decoded.requestId());
		assertEquals(1, decoded.nodes().size());
		assertEquals("minecraft:overworld", decoded.nodes().get(0).dimension());
		assertEquals(3, decoded.nodes().get(0).level());
		assertEquals(-7, decoded.nodes().get(0).nodeX());
		assertEquals(11, decoded.nodes().get(0).nodeZ());
	}

	private static MapRootHashPayload roundTrip(MapRootHashPayload payload) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		payload.write(buffer);
		return MapRootHashPayload.read(buffer);
	}

	private static MapNodeRequestPayload roundTrip(MapNodeRequestPayload payload) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		payload.write(buffer);
		return MapNodeRequestPayload.read(buffer);
	}
}
