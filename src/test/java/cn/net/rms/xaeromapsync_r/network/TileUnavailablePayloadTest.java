package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class TileUnavailablePayloadTest {
	@Test
	void codecPreservesRequestedTileAndReason() {
		TileUnavailablePayload original = new TileUnavailablePayload("minecraft:overworld", -12, 34, "not loaded");
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		original.write(buffer);
		TileUnavailablePayload decoded = TileUnavailablePayload.read(buffer);

		assertEquals("minecraft:overworld", decoded.dimension());
		assertEquals(-12, decoded.chunkX());
		assertEquals(34, decoded.chunkZ());
		assertEquals("not loaded", decoded.reason());
	}

	@Test
	void rejectsMissingIdentityOrReason() {
		assertThrows(IllegalArgumentException.class, () -> new TileUnavailablePayload("", 0, 0, "reason"));
		assertThrows(IllegalArgumentException.class, () -> new TileUnavailablePayload("minecraft:overworld", 0, 0, ""));
	}
}
