package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TransferSessionTest {
	@Test
	void retriesOnlyUnacknowledgedPartsUntilLimitIsExhausted() {
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(),
				new byte[TransferPartPayload.MAX_PART_BYTES * 2 + 1]);
		TransferSession session = new TransferSession(parts, 10L, 2);

		assertEquals(3, session.start(0L).size());
		assertEquals(TransferSession.Status.ACTIVE,
				session.acknowledge(new TransferAckPayload(parts.get(0).transferId(), 0), 5L));
		assertEquals(TransferSession.Status.ACTIVE,
				session.acknowledge(new TransferAckPayload(parts.get(0).transferId(), 0), 9L));
		assertEquals(TransferSession.Status.TIMED_OUT, session.checkTimeout(15L));
		assertEquals(List.of(1, 2), indexes(session.retry(15L)));
		assertEquals(1, session.retryCount());

		assertEquals(TransferSession.Status.ACTIVE,
				session.acknowledge(new TransferAckPayload(parts.get(0).transferId(), 1), 20L));
		assertEquals(TransferSession.Status.TIMED_OUT, session.checkTimeout(30L));
		assertEquals(List.of(2), indexes(session.retry(30L)));
		assertEquals(2, session.retryCount());
		assertEquals(TransferSession.Status.RETRIES_EXHAUSTED, session.checkTimeout(40L));
		assertThrows(IllegalStateException.class, () -> session.retry(40L));
	}

	@Test
	void finalContiguousAckCompletesSession() {
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(), new byte[] {1});
		TransferSession session = new TransferSession(parts, 10L, 1);
		session.start(0L);

		assertEquals(TransferSession.Status.COMPLETED,
				session.acknowledge(new TransferAckPayload(parts.get(0).transferId(), 0), 1L));
		assertEquals(List.of(), session.unacknowledgedParts());
		assertEquals(TransferSession.Status.COMPLETED, session.checkTimeout(100L));
	}

	@Test
	void rejectsAckForAnotherTransferOrBeyondPartCount() {
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(), new byte[] {1});
		TransferSession session = new TransferSession(parts, 10L, 1);
		session.start(0L);

		assertThrows(IllegalArgumentException.class,
				() -> session.acknowledge(new TransferAckPayload(UUID.randomUUID(), 0), 1L));
		assertThrows(IllegalArgumentException.class,
				() -> session.acknowledge(new TransferAckPayload(parts.get(0).transferId(), 1), 1L));
	}

	@Test
	void restartsTimeoutWindowWhenWallClockMovesBackwards() {
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(), new byte[] {1});
		TransferSession session = new TransferSession(parts, 10L, 1);
		session.start(100L);

		assertEquals(TransferSession.Status.ACTIVE, session.checkTimeout(90L));
		assertEquals(TransferSession.Status.ACTIVE, session.checkTimeout(99L));
		assertEquals(TransferSession.Status.TIMED_OUT, session.checkTimeout(100L));
		assertThrows(IllegalArgumentException.class, () -> session.retry(-1L));
	}

	private static List<Integer> indexes(List<TransferPartPayload> parts) {
		return parts.stream().map(TransferPartPayload::partIndex).toList();
	}
}
