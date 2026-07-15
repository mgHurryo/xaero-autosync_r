package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class LocalTileReadyPayloadTest {
	@Test
	void wireRoundTripContainsOnlyBoundedHintFields() {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		new LocalTileReadyPayload("minecraft:overworld", -13, 29, 0x1234ABCDL).write(buffer);

		LocalTileReadyPayload decoded = LocalTileReadyPayload.read(buffer);
		assertEquals("minecraft:overworld", decoded.dimension());
		assertEquals(-13, decoded.chunkX());
		assertEquals(29, decoded.chunkZ());
		assertEquals(0x1234ABCDL, decoded.contentHash());
		assertEquals(0, buffer.readableBytes());
	}
}
