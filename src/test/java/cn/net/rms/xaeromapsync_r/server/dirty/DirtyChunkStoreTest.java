package cn.net.rms.xaeromapsync_r.server.dirty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class DirtyChunkStoreTest {
	@Test
	void claimsStableChunksWithinBudgetAndRemovesOnlyAfterConfirmation() {
		DirtyChunkStore store = stableStore(3);

		List<DirtyChunkStore.StableDirtyChunk> firstBatch = store.claimStableDirtyChunks(2);

		assertEquals(2, firstBatch.size());
		assertEquals(3, store.totalCount());
		assertEquals(2, store.statistics().inFlight());
		assertEquals(1, store.statistics().queuedStable());
		assertEquals(1, store.claimStableDirtyChunks(2).size());

		assertTrue(store.confirmProcessed(firstBatch.get(0)));
		assertEquals(2, store.totalCount());
		assertFalse(store.confirmProcessed(firstBatch.get(0)));
	}

	@Test
	void deferringRetainsChunkAndMakesItClaimableAgain() {
		DirtyChunkStore store = stableStore(1);
		DirtyChunkStore.StableDirtyChunk claim = store.claimStableDirtyChunks(1).get(0);

		assertTrue(store.defer(claim));

		assertEquals(1, store.totalCount());
		assertEquals(0, store.statistics().inFlight());
		assertEquals(1, store.claimStableDirtyChunks(1).size());
	}

	@Test
	void newDirtyChangeInvalidatesOutstandingClaim() {
		DirtyChunkStore store = stableStore(1);
		DirtyChunkStore.StableDirtyChunk claim = store.claimStableDirtyChunks(1).get(0);

		store.markDirty(claim.dimension(), new BlockPos(claim.chunkX() << 4, 64, claim.chunkZ() << 4));

		assertFalse(store.confirmProcessed(claim));
		assertEquals(1, store.totalCount());
		assertEquals(1, store.statistics().active());
		assertEquals(0, store.statistics().inFlight());
	}

	@Test
	void pausedAndZeroBudgetDoNotClaimAndNegativeBudgetIsRejected() {
		DirtyChunkStore store = stableStore(1);

		assertTrue(store.claimStableDirtyChunks(0).isEmpty());
		store.setPaused(true);
		assertTrue(store.claimStableDirtyChunks(1).isEmpty());
		assertThrows(IllegalArgumentException.class, () -> store.claimStableDirtyChunks(-1));
	}

	@Test
	void discoveredChunkIsImmediatelyStableAndIsNotQueuedTwice() {
		DirtyChunkStore store = new DirtyChunkStore(false);

		assertTrue(store.markDiscovered("minecraft:overworld", 3, -4));
		assertFalse(store.markDiscovered("minecraft:overworld", 3, -4));

		assertEquals(1, store.totalCount());
		assertEquals(1, store.statistics().stable());
		DirtyChunkStore.StableDirtyChunk claimed = store.claimStableDirtyChunks(1).get(0);
		assertEquals(3, claimed.chunkX());
		assertEquals(-4, claimed.chunkZ());
	}

	@Test
	void concurrentClaimersNeverReceiveTheSameStableChunk() throws Exception {
		int chunkCount = 40;
		DirtyChunkStore store = stableStore(chunkCount);
		ExecutorService executor = Executors.newFixedThreadPool(8);
		CountDownLatch start = new CountDownLatch(1);
		Set<String> claimedChunks = ConcurrentHashMap.newKeySet();
		AtomicInteger duplicateClaims = new AtomicInteger();

		for (int index = 0; index < 8; index++) {
			executor.submit(() -> {
				await(start);
				for (DirtyChunkStore.StableDirtyChunk chunk : store.claimStableDirtyChunks(chunkCount)) {
					String coordinate = chunk.dimension() + ":" + chunk.chunkX() + ":" + chunk.chunkZ();
					if (!claimedChunks.add(coordinate)) {
						duplicateClaims.incrementAndGet();
					}
				}
			});
		}
		start.countDown();
		executor.shutdown();
		assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

		assertEquals(0, duplicateClaims.get());
		assertEquals(chunkCount, claimedChunks.size());
		assertEquals(chunkCount, store.statistics().inFlight());
	}

	private static DirtyChunkStore stableStore(int count) {
		DirtyChunkStore store = new DirtyChunkStore(false);
		for (int index = 0; index < count; index++) {
			store.markDirty("minecraft:overworld", new BlockPos(index << 4, 64, 0));
		}
		for (int tick = 0; tick < 200; tick++) {
			store.advance();
		}
		return store;
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}
}
