package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class TransferNackPayloadTest {
	@Test
	void roundTripsMissingFragmentIndexes() {
		TransferNackPayload payload = new TransferNackPayload(UUID.randomUUID(), List.of(1, 3, 5));
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		payload.write(buffer);
		TransferNackPayload decoded = TransferNackPayload.read(buffer);
		assertEquals(payload, decoded);
	}
}
