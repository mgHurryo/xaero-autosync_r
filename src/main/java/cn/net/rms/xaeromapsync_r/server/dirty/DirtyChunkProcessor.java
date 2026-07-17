package cn.net.rms.xaeromapsync_r.server.dirty;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

public final class DirtyChunkProcessor {
	private static final int CLAIM_PAGE_SIZE = 64;
	private final DirtyChunkStore store;
	private final LoadedChunkProbe loadedChunkProbe;
	private final ChunkRecalculator recalculator;
	private long ticks;
	private long claimed;
	private long completed;
	private long deferred;
	private long failed;
	private long stale;

	public DirtyChunkProcessor(DirtyChunkStore store, LoadedChunkProbe loadedChunkProbe, ChunkRecalculator recalculator) {
		if (store == null || loadedChunkProbe == null || recalculator == null) {
			throw new IllegalArgumentException("Dirty chunk processor dependencies must not be null");
		}
		this.store = store;
		this.loadedChunkProbe = loadedChunkProbe;
		this.recalculator = recalculator;
	}

	public synchronized TickResult processTick(int budget) {
		return processTick(budget, budget);
	}

	public synchronized TickResult processTick(int renderBudget, int scanBudget) {
		return processTick(renderBudget, scanBudget, () -> true);
	}

	public synchronized TickResult processTick(int renderBudget, int scanBudget, BooleanSupplier canContinueRendering) {
		if (renderBudget < 0 || scanBudget < 0) {
			throw new IllegalArgumentException("Dirty chunk budgets must not be negative");
		}
		if (canContinueRendering == null) {
			throw new IllegalArgumentException("Dirty chunk render deadline is required");
		}
		if (renderBudget == 0 || scanBudget == 0) {
			return recordResult(renderBudget, List.of(), 0, 0, 0, 0);
		}
		int submittedThisTick = 0;
		int deferredThisTick = 0;
		int failedThisTick = 0;
		int staleThisTick = 0;
		int renderAttempts = 0;
		int claimedThisTick = 0;
		boolean continueProcessing = true;
		Set<String> visited = new HashSet<>();

		while (continueProcessing && claimedThisTick < scanBudget && renderAttempts < renderBudget
				&& canContinueRendering.getAsBoolean()) {
			int pageSize = Math.min(CLAIM_PAGE_SIZE, scanBudget - claimedThisTick);
			List<DirtyChunkStore.StableDirtyChunk> chunks = store.claimStableDirtyChunks(pageSize, pageSize);
			if (chunks.isEmpty()) break;
			claimedThisTick += chunks.size();
			int newChunksInPage = 0;
			for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
				DirtyChunkStore.StableDirtyChunk chunk = chunks.get(chunkIndex);
				String chunkKey = chunk.dimension() + "\u0000" + chunk.chunkX() + "\u0000" + chunk.chunkZ();
				if (!visited.add(chunkKey)) {
					if (store.defer(chunk)) deferredThisTick++;
					else staleThisTick++;
					continue;
				}
				newChunksInPage++;
				if (renderAttempts >= renderBudget || !canContinueRendering.getAsBoolean()) {
					for (int remaining = chunkIndex; remaining < chunks.size(); remaining++) {
						if (store.defer(chunks.get(remaining))) deferredThisTick++;
						else staleThisTick++;
					}
					continueProcessing = false;
					break;
				}
				boolean loaded;
				try {
					loaded = loadedChunkProbe.isLoaded(chunk);
				} catch (RuntimeException exception) {
					logFailure("check loaded state for", chunk, exception);
					if (finish(chunk, false, false) == CompletionResult.RETAINED) {
						failedThisTick++;
					} else {
						staleThisTick++;
					}
					continue;
				}
				if (!loaded) {
					if (store.defer(chunk)) {
						deferredThisTick++;
					} else {
						staleThisTick++;
					}
					continue;
				}

				renderAttempts++;
				boolean submitted;
				try {
					submitted = recalculator.recalculate(chunk);
				} catch (RuntimeException exception) {
					logFailure("recalculate", chunk, exception);
					submitted = false;
				}
				if (submitted) {
					submittedThisTick++;
				} else if (finish(chunk, false, false) == CompletionResult.RETAINED) {
					failedThisTick++;
				} else {
					staleThisTick++;
				}
			}
			if (newChunksInPage == 0) break;
		}

		return recordResult(renderBudget, claimedThisTick, submittedThisTick, deferredThisTick, failedThisTick, staleThisTick);
	}

	private TickResult recordResult(int renderBudget, List<DirtyChunkStore.StableDirtyChunk> chunks,
			int submittedThisTick, int deferredThisTick, int failedThisTick, int staleThisTick) {
		return recordResult(renderBudget, chunks.size(), submittedThisTick, deferredThisTick, failedThisTick, staleThisTick);
	}

	private TickResult recordResult(int renderBudget, int claimedThisTick,
			int submittedThisTick, int deferredThisTick, int failedThisTick, int staleThisTick) {
		ticks++;
		claimed += claimedThisTick;
		deferred += deferredThisTick;
		failed += failedThisTick;
		stale += staleThisTick;
		return new TickResult(renderBudget, claimedThisTick, submittedThisTick, deferredThisTick, failedThisTick, staleThisTick);
	}

	/** Completes an accepted recalculation after its durable tile write and index publication finish. */
	public synchronized CompletionResult completeRecalculation(DirtyChunkStore.StableDirtyChunk chunk, boolean successful) {
		return finish(chunk, successful, true);
	}

	private CompletionResult finish(DirtyChunkStore.StableDirtyChunk chunk, boolean successful, boolean recordStatistics) {
		CompletionResult result;
		if (successful && store.confirmProcessed(chunk)) {
			result = CompletionResult.COMPLETED;
		} else if (!successful && store.defer(chunk)) {
			result = CompletionResult.RETAINED;
		} else {
			result = CompletionResult.STALE;
		}
		if (recordStatistics) {
			if (result == CompletionResult.COMPLETED) completed++;
			else if (result == CompletionResult.RETAINED) failed++;
			else stale++;
		}
		return result;
	}

	public synchronized Statistics statistics() {
		return new Statistics(ticks, claimed, completed, deferred, failed, stale);
	}

	private static void logFailure(String operation, DirtyChunkStore.StableDirtyChunk chunk, RuntimeException exception) {
		XaeroMapsync_r.LOGGER.warn("Failed to {} dirty chunk {} {} {}",
				operation, chunk.dimension(), chunk.chunkX(), chunk.chunkZ(), exception);
	}

	@FunctionalInterface
	public interface LoadedChunkProbe {
		boolean isLoaded(DirtyChunkStore.StableDirtyChunk chunk);
	}

	@FunctionalInterface
	public interface ChunkRecalculator {
		boolean recalculate(DirtyChunkStore.StableDirtyChunk chunk);
	}

	public enum CompletionResult {
		COMPLETED,
		RETAINED,
		STALE
	}

	public record TickResult(int budget, int claimed, int submitted, int deferred, int failed, int stale) {
	}

	public record Statistics(long ticks, long claimed, long completed, long deferred, long failed, long stale) {
	}
}
