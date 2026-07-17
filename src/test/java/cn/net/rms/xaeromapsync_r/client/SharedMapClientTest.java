package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.xaero.XaeroMapAdapter;
import java.util.Map;
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
		assertFalse(SharedMapClient.canCompleteMapRoot(true, 0, 0, 0, 0, 0, 0, 0, 0, 0));
	}

	@Test
	void completeRootRequiresEveryQueueAndRequestToBeIdle() {
		assertTrue(SharedMapClient.canCompleteMapRoot(false, 0, 0, 0, 0, 0, 0, 0, 0, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 1, 0, 0, 0, 0, 0, 0, 0, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 0, 0, 1, 0, 0, 0, 0, 0, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 0, 0, 0, 0, 0, 1, 0, 0, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 0, 0, 0, 0, 0, 0, 1, 0, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 0, 0, 0, 0, 0, 0, 0, 1, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 0, 0, 0, 0, 0, 0, 0, 0, 1));
	}

	@Test
	void tileRequestsUseHighWaterBackpressureWhileXaeroAppliesDownloadedTiles() {
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
	void localTileUploadsFollowClientRenderDistanceInsteadOfHistoricalMapRadius() {
		assertEquals(3, SharedMapClient.localTileUploadRadius(2));
		assertEquals(13, SharedMapClient.localTileUploadRadius(12));
	}

	@Test
	void staleTileResponsesReleaseTheirRequestSlotAndRequireTheLatestRevisionAgain() {
		assertTrue(SharedMapClient.isStaleTileResponse(12L, 11L));
		assertFalse(SharedMapClient.isStaleTileResponse(12L, 12L));
		assertFalse(SharedMapClient.isStaleTileResponse(null, 11L));
	}

	@Test
	void uploadHashIsCommittedOnlyAfterMatchingSuccessfulSend() {
		assertTrue(SharedMapClient.shouldCommitLocalTileUpload(12L, 12L, true));
		assertFalse(SharedMapClient.shouldCommitLocalTileUpload(12L, 12L, false));
		assertFalse(SharedMapClient.shouldCommitLocalTileUpload(13L, 12L, true));
		assertFalse(SharedMapClient.shouldCommitLocalTileUpload(null, 12L, true));
	}

	@Test
	void staleCacheCompletionCannotDecrementNewGenerationOrGoNegative() {
		assertEquals(4, SharedMapClient.cacheLookupCountAfterCompletion(4, 7L, 8L));
		assertEquals(3, SharedMapClient.cacheLookupCountAfterCompletion(4, 8L, 8L));
		assertEquals(0, SharedMapClient.cacheLookupCountAfterCompletion(0, 8L, 8L));
	}

	@Test
	void tileTargetAdmissionStopsAtTheConfiguredHardLimit() {
		assertTrue(SharedMapClient.canTrackTileTarget(8_191, 8_192));
		assertFalse(SharedMapClient.canTrackTileTarget(8_192, 8_192));
	}

	@Test
	void localMetadataCacheEvictsLeastRecentlyUsedEntries() {
		Map<Integer, Integer> cache = SharedMapClient.boundedAccessMap(2);
		cache.put(1, 1);
		cache.put(2, 2);
		assertEquals(1, cache.get(1));
		cache.put(3, 3);

		assertTrue(cache.containsKey(1));
		assertFalse(cache.containsKey(2));
		assertTrue(cache.containsKey(3));
		assertEquals(2, cache.size());
	}

	@Test
	void liveTileScanVisitsEveryCoordinateWithoutCenterStarvation() {
		int radius = 3;
		java.util.Set<Long> offsets = new java.util.HashSet<>();
		for (int cursor = 0; cursor < SharedMapClient.localTileScanCount(radius); cursor++) {
			long offset = SharedMapClient.localTileScanOffset(radius, cursor);
			int dx = (int) (offset >> 32);
			int dz = (int) offset;
			assertTrue(Math.abs(dx) <= radius);
			assertTrue(Math.abs(dz) <= radius);
			assertTrue(offsets.add(offset));
		}
		assertEquals(49, offsets.size());
		assertEquals(0L, SharedMapClient.localTileScanOffset(radius, 0));
	}

	@Test
	void archiveRegionOffsetsCoverNegativeRegionEdgesWithoutGaps() {
		XaeroMapAdapter.LocalRegion region = new XaeroMapAdapter.LocalRegion(-2, -1);
		assertEquals(-64, SharedMapClient.archiveChunkX(region, 0));
		assertEquals(-32, SharedMapClient.archiveChunkZ(region, 0));
		assertEquals(-33, SharedMapClient.archiveChunkX(region, 1023));
		assertEquals(-1, SharedMapClient.archiveChunkZ(region, 1023));
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
