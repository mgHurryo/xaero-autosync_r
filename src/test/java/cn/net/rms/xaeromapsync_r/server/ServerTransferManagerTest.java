package cn.net.rms.xaeromapsync_r.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ServerTransferManagerTest {
	@Test
	void selectsLowestValidMissingPart() {
		assertEquals(1, ServerTransferManager.firstRetransmissionPart(List.of(3, 1, 2), 4));
	}

	@Test
	void restartsFromFirstPartWhenAllRequestedIndexesAreOutOfRange() {
		assertEquals(0, ServerTransferManager.firstRetransmissionPart(List.of(4, 5), 4));
	}
}
