package cn.net.rms.xaeromapsync_r.server.dirty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class DirtyChunkProcessorTest {
	@Test
	void acceptedRecalculationRemainsClaimedUntilDurableCompletion() {
		DirtyChunkStore store = stableStore(1);
		AtomicReference<DirtyChunkStore.StableDirtyChunk> submitted = new AtomicReference<>();
		DirtyChunkProcessor processor = new DirtyChunkProcessor(store, chunk -> true, chunk -> {
			submitted.set(chunk);
			return true;
		});

		DirtyChunkProcessor.TickResult result = processor.processTick(1);

		assertEquals(1, result.claimed());
		assertEquals(1, result.submitted());
		assertEquals(1, store.totalCount());
		assertEquals(1, store.statistics().inFlight());
		assertEquals(0, processor.statistics().completed());

		assertEquals(DirtyChunkProcessor.CompletionResult.COMPLETED,
				processor.completeRecalculation(submitted.get(), true));

		assertEquals(0, store.totalCount());
		assertEquals(1, processor.statistics().completed());
	}

	@Test
	void unloadedChunkIsDeferredWithoutCallingRecalculator() {
		DirtyChunkStore store = stableStore(1);
		AtomicInteger recalculations = new AtomicInteger();
		DirtyChunkProcessor processor = new DirtyChunkProcessor(store, chunk -> false, chunk -> {
			recalculations.incrementAndGet();
			return true;
		});

		DirtyChunkProcessor.TickResult result = processor.processTick(1);

		assertEquals(1, result.deferred());
		assertEquals(0, recalculations.get());
		assertEquals(1, store.totalCount());
		assertEquals(0, store.statistics().inFlight());
	}

	@Test
	void deferredUnloadedChunkDoesNotStarveLaterLoadedChunk() {
		DirtyChunkStore store = stableStore(2);
		List<Integer> recalculated = new ArrayList<>();
		AtomicReference<DirtyChunkStore.StableDirtyChunk> submitted = new AtomicReference<>();
		DirtyChunkProcessor processor = new DirtyChunkProcessor(
				store,
				chunk -> chunk.chunkX() == 1,
				chunk -> {
					recalculated.add(chunk.chunkX());
					submitted.set(chunk);
					return true;
				});

		assertEquals(1, processor.processTick(1).deferred());
		assertEquals(1, processor.processTick(1).submitted());
		processor.completeRecalculation(submitted.get(), true);

		assertEquals(List.of(1), recalculated);
		assertEquals(1, store.totalCount());
	}

	@Test
	void scanBudgetFindsLoadedChunkBehindLargeUnloadedBacklog() {
		DirtyChunkStore store = stableStore(64);
		List<Integer> recalculated = new ArrayList<>();
		AtomicReference<DirtyChunkStore.StableDirtyChunk> submitted = new AtomicReference<>();
		DirtyChunkProcessor processor = new DirtyChunkProcessor(
				store,
				chunk -> chunk.chunkX() == 63,
				chunk -> {
					recalculated.add(chunk.chunkX());
					submitted.set(chunk);
					return true;
				});

		DirtyChunkProcessor.TickResult result = processor.processTick(1, 64);

		assertEquals(64, result.claimed());
		assertEquals(1, result.submitted());
		assertEquals(63, result.deferred());
		assertEquals(List.of(63), recalculated);
		processor.completeRecalculation(submitted.get(), true);
		assertEquals(63, store.totalCount());
	}

	@Test
	void failedAndExceptionalRecalculationsRemainQueued() {
		DirtyChunkStore store = stableStore(2);
		DirtyChunkProcessor processor = new DirtyChunkProcessor(store, chunk -> true, chunk -> {
			if (chunk.chunkX() == 0) {
				return false;
			}
			throw new IllegalStateException("recalculation failed");
		});

		DirtyChunkProcessor.TickResult result = processor.processTick(2);

		assertEquals(2, result.failed());
		assertEquals(2, store.totalCount());
		assertEquals(2, store.statistics().queuedStable());
	}

	@Test
	void loadedProbeExceptionIsCountedAndRetained() {
		DirtyChunkStore store = stableStore(1);
		DirtyChunkProcessor processor = new DirtyChunkProcessor(store, chunk -> {
			throw new IllegalStateException("loaded probe failed");
		}, chunk -> true);

		DirtyChunkProcessor.TickResult result = processor.processTick(1);

		assertEquals(1, result.failed());
		assertEquals(1, store.totalCount());
		assertEquals(0, store.statistics().inFlight());
	}

	@Test
	void dirtyChangeDuringRecalculationMakesSuccessConfirmationStale() {
		DirtyChunkStore store = stableStore(1);
		AtomicReference<DirtyChunkStore.StableDirtyChunk> submitted = new AtomicReference<>();
		DirtyChunkProcessor processor = new DirtyChunkProcessor(store, chunk -> true, chunk -> {
			submitted.set(chunk);
			store.markDirty(chunk.dimension(), new BlockPos(chunk.chunkX() << 4, 64, chunk.chunkZ() << 4));
			return true;
		});

		DirtyChunkProcessor.TickResult result = processor.processTick(1);

		assertEquals(1, result.submitted());
		assertEquals(DirtyChunkProcessor.CompletionResult.STALE,
				processor.completeRecalculation(submitted.get(), true));
		assertEquals(1, store.totalCount());
		assertEquals(1, store.statistics().active());
		assertEquals(1, processor.statistics().stale());
	}

	@Test
	void asynchronousPersistenceFailureRetainsChunkForRetry() {
		DirtyChunkStore store = stableStore(1);
		AtomicReference<DirtyChunkStore.StableDirtyChunk> submitted = new AtomicReference<>();
		DirtyChunkProcessor processor = new DirtyChunkProcessor(store, chunk -> true, chunk -> {
			submitted.set(chunk);
			return true;
		});

		assertEquals(1, processor.processTick(1).submitted());
		assertEquals(DirtyChunkProcessor.CompletionResult.RETAINED,
				processor.completeRecalculation(submitted.get(), false));

		assertEquals(1, store.totalCount());
		assertEquals(0, store.statistics().inFlight());
		assertEquals(1, store.claimStableDirtyChunks(1).size());
		assertEquals(1, processor.statistics().failed());
	}

	@Test
	void rejectsNegativeBudgetAndMissingDependencies() {
		DirtyChunkStore store = stableStore(1);
		DirtyChunkProcessor processor = new DirtyChunkProcessor(store, chunk -> true, chunk -> true);

		assertThrows(IllegalArgumentException.class, () -> processor.processTick(-1));
		assertThrows(IllegalArgumentException.class, () -> processor.processTick(1, -1));
		assertThrows(IllegalArgumentException.class, () -> new DirtyChunkProcessor(null, chunk -> true, chunk -> true));
	}

	@Test
	void expiredDeadlineStopsRenderingBeforeTheFirstChunk() {
		DirtyChunkStore store = stableStore(2);
		DirtyChunkProcessor processor = new DirtyChunkProcessor(store, chunk -> true, chunk -> true);

		DirtyChunkProcessor.TickResult result = processor.processTick(2, 2, () -> false);

		assertEquals(0, result.submitted());
		assertEquals(2, result.deferred());
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
}
