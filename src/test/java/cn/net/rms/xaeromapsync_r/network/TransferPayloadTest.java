package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class TransferPayloadTest {
	@Test
	void transferPartCodecPreservesBoundedMetadata() {
		UUID transferId = UUID.randomUUID();
		byte[] data = new byte[] {1, 2, 3};
		TransferPartPayload original = new TransferPartPayload(transferId, 0, 1, data.length, 0x1234abcdL, data);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		original.write(buffer);
		TransferPartPayload decoded = TransferPartPayload.read(buffer);

		assertEquals(transferId, decoded.transferId());
		assertEquals(0, decoded.partIndex());
		assertEquals(1, decoded.partCount());
		assertEquals(data.length, decoded.totalLength());
		assertEquals(0x1234abcdL, decoded.checksum());
		assertArrayEquals(data, decoded.payload());
	}

	@Test
	void transferAckCodecSupportsNoContiguousParts() {
		UUID transferId = UUID.randomUUID();
		TransferAckPayload original = new TransferAckPayload(transferId, -1);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		original.write(buffer);
		TransferAckPayload decoded = TransferAckPayload.read(buffer);

		assertEquals(transferId, decoded.transferId());
		assertEquals(-1, decoded.highestContiguousPart());
	}

	@Test
	void payloadValidationRejectsInvalidBounds() {
		UUID transferId = UUID.randomUUID();
		assertThrows(IllegalArgumentException.class,
				() -> new TransferPartPayload(transferId, 0, 2, 1, 0L, new byte[] {1}));
		assertThrows(IllegalArgumentException.class,
				() -> new TransferPartPayload(transferId, 0, 1, 1, 0x1_0000_0000L, new byte[] {1}));
		assertThrows(IllegalArgumentException.class,
				() -> new TransferAckPayload(transferId, TransferPartPayload.MAX_PART_COUNT));
	}

	@Test
	void payloadIsDefensivelyCopied() {
		byte[] source = new byte[] {4, 5};
		TransferPartPayload part = new TransferPartPayload(UUID.randomUUID(), 0, 1, 2, 0L, source);

		source[0] = 9;
		byte[] exposed = part.payload();
		exposed[1] = 9;

		assertArrayEquals(new byte[] {4, 5}, part.payload());
	}
}
