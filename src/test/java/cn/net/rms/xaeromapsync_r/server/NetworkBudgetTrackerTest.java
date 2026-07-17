package cn.net.rms.xaeromapsync_r.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class NetworkBudgetTrackerTest {
	@Test
	void enforcesPlayerAndGlobalBudgetsWithoutCountingRejectedBytes() {
		NetworkBudgetTracker tracker = new NetworkBudgetTracker();
		tracker.setBytesPerPlayerPerTick(100);
		tracker.setGlobalBytesPerTick(150);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		assertTrue(tracker.trySpend(first, 100));
		assertFalse(tracker.trySpend(first, 1));
		assertTrue(tracker.trySpend(second, 50));
		assertFalse(tracker.trySpend(second, 1));
		assertEquals(150L, tracker.totalBytes());
		assertEquals(2L, tracker.rejectedBytes());
	}

	@Test
	void rejectsInvalidArguments() {
		NetworkBudgetTracker tracker = new NetworkBudgetTracker();
		assertThrows(IllegalArgumentException.class, () -> tracker.trySpend(null, 1));
		assertThrows(IllegalArgumentException.class, () -> tracker.trySpend(UUID.randomUUID(), -1));
		assertThrows(IllegalArgumentException.class, () -> tracker.setGlobalBytesPerTick(0));
	}

	@Test
	void lowPriorityTrafficOnlyUsesTheLowerHalfOfIdleBudgets() {
		assertTrue(NetworkBudgetTracker.belowLowWatermark(0, 0, 0, 0,
				50, 100, 200));
		assertFalse(NetworkBudgetTracker.belowLowWatermark(51, 0, 0, 0,
				1, 100, 200));
		assertFalse(NetworkBudgetTracker.belowLowWatermark(0, 101, 0, 0,
				1, 100, 200));
		assertFalse(NetworkBudgetTracker.belowLowWatermark(0, 0, 50, 0,
				1, 100, 200));
		assertFalse(NetworkBudgetTracker.belowLowWatermark(0, 0, 0, 100,
				1, 100, 200));
	}
}
