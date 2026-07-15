package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TransferAssemblerTest {
	@Test
	void acceptsOutOfOrderAndDuplicatePartsWithContiguousAcks() {
		byte[] data = patternedBytes(TransferPartPayload.MAX_PART_BYTES * 2 + 7);
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(), data);
		TransferAssembler assembler = new TransferAssembler(100L);

		assertEquals(TransferAssembler.ReceiveResult.ACCEPTED, assembler.accept(parts.get(2), 1L));
		assertEquals(-1, assembler.acknowledgement().highestContiguousPart());
		assertEquals(List.of(0, 1), assembler.missingPartIndexes());

		assertEquals(TransferAssembler.ReceiveResult.ACCEPTED, assembler.accept(parts.get(0), 2L));
		assertEquals(0, assembler.acknowledgement().highestContiguousPart());
		assertEquals(TransferAssembler.ReceiveResult.DUPLICATE, assembler.accept(parts.get(0), 3L));

		assertEquals(TransferAssembler.ReceiveResult.COMPLETE, assembler.accept(parts.get(1), 4L));
		assertEquals(2, assembler.acknowledgement().highestContiguousPart());
		assertEquals(List.of(), assembler.missingPartIndexes());
		assertArrayEquals(data, assembler.assembledData());
		assertEquals(TransferAssembler.ReceiveResult.DUPLICATE, assembler.accept(parts.get(2), 5L));
	}

	@Test
	void detectsWholeTransferChecksumMismatch() {
		byte[] data = patternedBytes(TransferPartPayload.MAX_PART_BYTES + 3);
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(), data);
		TransferPartPayload last = parts.get(1);
		byte[] corrupted = last.payload();
		corrupted[0] ^= 0x5a;
		TransferPartPayload corruptedLast = new TransferPartPayload(last.transferId(), last.partIndex(),
				last.partCount(), last.totalLength(), last.checksum(), corrupted);
		TransferAssembler assembler = new TransferAssembler();

		assembler.accept(parts.get(0), 0L);

		assertEquals(TransferAssembler.ReceiveResult.CORRUPT, assembler.accept(corruptedLast, 1L));
		assertEquals(TransferAssembler.Status.CORRUPT, assembler.status());
		assertThrows(IllegalStateException.class, assembler::assembledData);
	}

	@Test
	void conflictingDuplicateMarksTransferCorrupt() {
		byte[] data = patternedBytes(TransferPartPayload.MAX_PART_BYTES + 1);
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(), data);
		TransferPartPayload first = parts.get(0);
		byte[] conflictingBytes = first.payload();
		conflictingBytes[0] ^= 1;
		TransferPartPayload conflicting = new TransferPartPayload(first.transferId(), 0, first.partCount(),
				first.totalLength(), first.checksum(), conflictingBytes);
		TransferAssembler assembler = new TransferAssembler();

		assembler.accept(first, 0L);

		assertEquals(TransferAssembler.ReceiveResult.CORRUPT, assembler.accept(conflicting, 1L));
		assertEquals(TransferAssembler.Status.CORRUPT, assembler.status());
	}

	@Test
	void incompleteTransferTimesOutAndReportsMissingParts() {
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(),
				new byte[TransferPartPayload.MAX_PART_BYTES + 1]);
		TransferAssembler assembler = new TransferAssembler(10L);
		assembler.accept(parts.get(1), 5L);

		assertEquals(TransferAssembler.Status.RECEIVING, assembler.checkTimeout(14L));
		assertEquals(TransferAssembler.Status.TIMED_OUT, assembler.checkTimeout(15L));
		assertEquals(List.of(0), assembler.missingPartIndexes());
		assertThrows(IllegalStateException.class, () -> assembler.accept(parts.get(0), 16L));
	}

	private static byte[] patternedBytes(int length) {
		byte[] data = new byte[length];
		for (int index = 0; index < data.length; index++) {
			data[index] = (byte) (index * 31);
		}
		return data;
	}
}
