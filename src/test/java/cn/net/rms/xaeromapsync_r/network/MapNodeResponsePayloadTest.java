package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class MapNodeResponsePayloadTest {
	@Test
	void wireRoundTripDistinguishesRootAndNodeResponses() {
		MapNodeResponsePayload root = roundTrip(new MapNodeResponsePayload(
				"minecraft:overworld", 7L, 41L, 101L, false, List.of(), List.of()));
		MapNodeResponsePayload node = roundTrip(new MapNodeResponsePayload(
				"minecraft:overworld", 7L, 41L, 102L, true, List.of(), List.of()));

		assertFalse(root.nodeRequestResponse());
		assertTrue(node.nodeRequestResponse());
		assertEquals(41L, root.syncId());
		assertEquals(101L, root.requestId());
		assertEquals(41L, node.syncId());
		assertEquals(102L, node.requestId());
	}

	private static MapNodeResponsePayload roundTrip(MapNodeResponsePayload payload) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		payload.write(buffer);
		return MapNodeResponsePayload.read(buffer);
	}
}
