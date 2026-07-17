package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TransferFragmenterTest {
	@Test
	void fragmentsAtTheFixedMaximumPartSize() {
		byte[] data = new byte[TransferPartPayload.MAX_PART_BYTES * 2 + 7];
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(), data);

		assertEquals(3, parts.size());
		assertEquals(TransferPartPayload.MAX_PART_BYTES, parts.get(0).payload().length);
		assertEquals(TransferPartPayload.MAX_PART_BYTES, parts.get(1).payload().length);
		assertEquals(7, parts.get(2).payload().length);
		assertEquals(data.length, parts.get(2).totalLength());
		assertEquals(TransferFragmenter.crc32(data), parts.get(2).checksum());
	}

	@Test
	void emptyTransferUsesOneEmptyPart() {
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(), new byte[0]);

		assertEquals(1, parts.size());
		assertEquals(0, parts.get(0).payload().length);
		assertEquals(0L, parts.get(0).checksum());
	}

	@Test
	void rejectsTransferAboveTheGlobalLimit() {
		byte[] oversized = new byte[TransferPartPayload.MAX_TRANSFER_BYTES + 1];

		assertThrows(IllegalArgumentException.class,
				() -> TransferFragmenter.fragment(UUID.randomUUID(), oversized));
	}
}
