package cn.net.rms.xaeromapsync_r.server.dirty;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import java.util.List;

public final class DirtyChunkProcessor {
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
		List<DirtyChunkStore.StableDirtyChunk> chunks = store.claimStableDirtyChunks(budget);
		int completedThisTick = 0;
		int deferredThisTick = 0;
		int failedThisTick = 0;
		int staleThisTick = 0;

		for (DirtyChunkStore.StableDirtyChunk chunk : chunks) {
			boolean loaded;
			try {
				loaded = loadedChunkProbe.isLoaded(chunk);
			} catch (RuntimeException exception) {
				logFailure("check loaded state for", chunk, exception);
				if (store.defer(chunk)) {
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

			boolean successful;
			try {
				successful = recalculator.recalculate(chunk);
			} catch (RuntimeException exception) {
				logFailure("recalculate", chunk, exception);
				successful = false;
			}
			if (successful && store.confirmProcessed(chunk)) {
				completedThisTick++;
			} else if (store.defer(chunk)) {
				failedThisTick++;
			} else {
				staleThisTick++;
			}
		}

		ticks++;
		claimed += chunks.size();
		completed += completedThisTick;
		deferred += deferredThisTick;
		failed += failedThisTick;
		stale += staleThisTick;
		return new TickResult(budget, chunks.size(), completedThisTick, deferredThisTick, failedThisTick, staleThisTick);
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

	public record TickResult(int budget, int claimed, int completed, int deferred, int failed, int stale) {
	}

	public record Statistics(long ticks, long claimed, long completed, long deferred, long failed, long stale) {
	}
}
