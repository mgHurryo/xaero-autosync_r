package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.xaero.XaeroMapAdapter;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SharedMapClientTest {
	@Test
	void onlyCurrentPendingMerkleResponseCanAdvanceSync() {
		Set<Long> pending = Set.of(41L, 42L);
		assertTrue(SharedMapClient.isExpectedMapResponse(7L, pending, 7L, 41L));
		assertFalse(SharedMapClient.isExpectedMapResponse(7L, pending, 6L, 41L));
		assertFalse(SharedMapClient.isExpectedMapResponse(7L, pending, 7L, 99L));
		assertFalse(SharedMapClient.isExpectedMapResponse(0L, pending, 0L, 41L));
	}
	@Test
	void unavailableTilePreventsRootCompletionAfterQueuesDrain() {
		assertFalse(SharedMapClient.canCompleteMapRoot(true, 0, 0, 0, 0, 0, 0, 0, 0));
	}

	@Test
	void completeRootRequiresEveryQueueAndRequestToBeIdle() {
		assertTrue(SharedMapClient.canCompleteMapRoot(false, 0, 0, 0, 0, 0, 0, 0, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 1, 0, 0, 0, 0, 0, 0, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 0, 0, 1, 0, 0, 0, 0, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 0, 0, 0, 0, 0, 1, 0, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 0, 0, 0, 0, 0, 0, 1, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 0, 0, 0, 0, 0, 0, 0, 1));
	}

	@Test
	void tileRequestsUseBackpressureWhileXaeroAppliesDownloadedTiles() {
		assertTrue(SharedMapClient.canRequestTile(15, 32, 16, 64));
		assertFalse(SharedMapClient.canRequestTile(16, 0, 16, 64));
		assertFalse(SharedMapClient.canRequestTile(8, 56, 16, 64));
	}

	@Test
	void unchangedLeavesAreRecheckedForMissingLocalRevisions() {
		assertTrue(SharedMapClient.shouldInspectMerkleNode(0, false));
		assertFalse(SharedMapClient.shouldInspectMerkleNode(1, false));
		assertTrue(SharedMapClient.shouldInspectMerkleNode(1, true));
	}

	@Test
	void watchdogKeepsTilesWaitingForXaeroAndResetsDeadNetworkRequests() {
		assertFalse(SharedMapClient.shouldResetMapSync(false, 60_000L));
		assertFalse(SharedMapClient.shouldResetMapSync(true, 29_999L));
		assertTrue(SharedMapClient.shouldResetMapSync(true, 30_000L));
	}

	@Test
	void tileApplyRetriesBackOffWithoutGrowingUnbounded() {
		assertEquals(250L, SharedMapClient.tileApplyRetryMillis(0));
		assertEquals(2_000L, SharedMapClient.tileApplyRetryMillis(3));
		assertEquals(5_000L, SharedMapClient.tileApplyRetryMillis(20));
	}

	@Test
	void localXaeroGenerationIsNeverOverwrittenByRemoteFallback() {
		assertTrue(SharedMapClient.shouldWaitForLocalTile(XaeroMapAdapter.LocalTileState.GENERATING, 0));
		assertTrue(SharedMapClient.shouldWaitForLocalTile(XaeroMapAdapter.LocalTileState.GENERATING, 10_000));
		assertFalse(SharedMapClient.shouldWaitForLocalTile(XaeroMapAdapter.LocalTileState.REMOTE, 0));
		assertFalse(SharedMapClient.shouldWaitForLocalTile(XaeroMapAdapter.LocalTileState.READY, 0));
	}

	@Test
	void newerPushSupersedesDeferredTileButOlderPayloadDoesNot() {
		assertTrue(SharedMapClient.shouldReplacePendingRevision(10L, 11L));
		assertFalse(SharedMapClient.shouldReplacePendingRevision(11L, 11L));
		assertFalse(SharedMapClient.shouldReplacePendingRevision(12L, 11L));
	}

	@Test
	void appliedRevisionOnlyDiscardsOlderOrEqualPendingTiles() {
		assertTrue(SharedMapClient.shouldDiscardPendingRevision(10L, 10L));
		assertTrue(SharedMapClient.shouldDiscardPendingRevision(9L, 10L));
		assertFalse(SharedMapClient.shouldDiscardPendingRevision(11L, 10L));
	}
}
