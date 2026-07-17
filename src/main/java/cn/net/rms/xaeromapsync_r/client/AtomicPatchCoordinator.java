package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.map.MapPatch;
import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.xaero.XaeroMapAdapter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Serializes atomic patch commits so each Xaero 32x32 region has one active mutation. */
public final class AtomicPatchCoordinator {
	public enum Phase {
		MANIFEST,
		FETCHING,
		VERIFIED,
		PREPARED,
		WAIT_XAERO_IDLE,
		COMMITTING,
		WAIT_REFRESH,
		APPLIED,
		FAILED
	}

	private static final int MAX_PENDING_PATCHES = 2048;
	private static final int MAX_ATTEMPTS = 8;
	private static final long BASE_RETRY_MILLIS = 100L;
	private static final long MAX_LOCAL_GENERATION_WAIT_MILLIS = 2_000L;
	private static final long MAX_LOCAL_RETRY_MILLIS = 500L;
	private final XaeroMapAdapter adapter;
	private final Consumer<MapPatch> appliedSink;
	private final Consumer<Transition> transitionSink;
	private final Map<String, ArrayDeque<Transaction>> byRegion = new LinkedHashMap<>();
	private final Map<MapPatchKey, Long> pendingHashes = new HashMap<>();
	private int pendingCount;
	private long forcedRemoteCommits;
	private long localGenerationRechecks;

	public AtomicPatchCoordinator(XaeroMapAdapter adapter, Consumer<MapPatch> appliedSink,
			Consumer<Transition> transitionSink) {
		if (adapter == null || appliedSink == null || transitionSink == null) {
			throw new IllegalArgumentException("Patch coordinator dependencies are required");
		}
		this.adapter = adapter;
		this.appliedSink = appliedSink;
		this.transitionSink = transitionSink;
	}

	public synchronized boolean enqueueVerified(MapPatch patch) {
		long hash = patch.manifest().contentHash();
		Long pendingHash = pendingHashes.get(patch.manifest().key());
		if (pendingHash != null) return pendingHash == hash;
		if (pendingCount >= MAX_PENDING_PATCHES) return false;
		String region = regionKey(patch.manifest().key());
		Transaction transaction = new Transaction(patch);
		byRegion.computeIfAbsent(region, ignored -> new ArrayDeque<>()).addLast(transaction);
		pendingHashes.put(patch.manifest().key(), hash);
		pendingCount++;
		transition(transaction, Phase.VERIFIED, "body-validated");
		return true;
	}

	public synchronized int tick(long nowMillis, long deadlineNanos) {
		int completed = 0;
		List<String> regions = new ArrayList<>(byRegion.keySet());
		for (String region : regions) {
			if (System.nanoTime() >= deadlineNanos) break;
			ArrayDeque<Transaction> queue = byRegion.get(region);
			if (queue == null || queue.isEmpty()) continue;
			expireLocalGenerationWaits(queue, nowMillis);
			Transaction transaction = queue.peekFirst();
			process(transaction, nowMillis);
			if (transaction.phase == Phase.APPLIED || transaction.phase == Phase.FAILED) {
				queue.removeFirst();
				pendingHashes.remove(transaction.patch.manifest().key());
				pendingCount--;
				if (queue.isEmpty()) byRegion.remove(region);
				completed++;
			} else if (transaction.phase == Phase.WAIT_XAERO_IDLE && queue.size() > 1) {
				queue.removeFirst();
				queue.addLast(transaction);
			}
		}
		return completed;
	}

	private void expireLocalGenerationWaits(ArrayDeque<Transaction> queue, long nowMillis) {
		for (Transaction transaction : queue) {
			if (transaction.phase == Phase.WAIT_XAERO_IDLE
					&& nowMillis - transaction.localWaitStartedMillis >= MAX_LOCAL_GENERATION_WAIT_MILLIS) {
				// Ending the local wait is read-only. The transaction stays queued behind
				// WAIT_REFRESH and cannot mutate the Xaero region until it reaches the head.
				transaction.phase = Phase.PREPARED;
			}
		}
	}

	private void process(Transaction transaction, long nowMillis) {
		for (int transitions = 0; transitions < 4; transitions++) {
			switch (transaction.phase) {
				case VERIFIED -> transition(transaction, Phase.PREPARED, "verified-to-prepared");
				case PREPARED -> {
					List<MapTile> remote = new ArrayList<>(MapPatchKey.TILE_COUNT);
					boolean localWaitExpired = transaction.localWaitStartedMillis >= 0L
							&& nowMillis - transaction.localWaitStartedMillis >= MAX_LOCAL_GENERATION_WAIT_MILLIS;
					boolean localGenerating = false;
					for (MapTile tile : transaction.patch.tiles()) {
						XaeroMapAdapter.LocalTileState state = adapter.localTileState(tile);
						if (state == XaeroMapAdapter.LocalTileState.READY) continue;
						if (state == XaeroMapAdapter.LocalTileState.GENERATING) {
							localGenerating = true;
							if (!localWaitExpired) continue;
						}
						remote.add(tile);
					}
					if (localGenerating && !localWaitExpired) {
						if (transaction.localWaitStartedMillis < 0L) transaction.localWaitStartedMillis = nowMillis;
						transaction.localWaitChecks++;
						long remainingWaitMillis = MAX_LOCAL_GENERATION_WAIT_MILLIS
								- Math.max(0L, nowMillis - transaction.localWaitStartedMillis);
						transaction.retryAtMillis = nowMillis
								+ Math.min(localRetryDelay(transaction.localWaitChecks), Math.max(1L, remainingWaitMillis));
						if (transaction.localWaitChecks == 1) {
							transition(transaction, Phase.WAIT_XAERO_IDLE, "local-generating");
						} else {
							transaction.phase = Phase.WAIT_XAERO_IDLE;
							localGenerationRechecks++;
						}
						return;
					}
					transaction.remoteTiles = List.copyOf(remote);
					transaction.forcedRemote = localGenerating && localWaitExpired;
					transition(transaction, Phase.COMMITTING, transaction.forcedRemote
							? "local-generation-timeout" : remote.isEmpty() ? "local-authoritative" : "xaero-region-ready");
				}
				case WAIT_XAERO_IDLE -> {
					if (nowMillis < transaction.retryAtMillis) return;
					transaction.phase = Phase.PREPARED;
				}
				case COMMITTING -> {
					XaeroMapAdapter.ApplyResult result = transaction.remoteTiles.isEmpty()
							? XaeroMapAdapter.ApplyResult.APPLIED
							: adapter.applyBatchResult(transaction.remoteTiles);
					if (result == XaeroMapAdapter.ApplyResult.APPLIED) {
						appliedSink.accept(transaction.patch);
						if (transaction.forcedRemote) forcedRemoteCommits++;
						transition(transaction, Phase.APPLIED, "atomic-commit-complete");
						return;
					}
					if (result == XaeroMapAdapter.ApplyResult.UNAVAILABLE || ++transaction.attempts >= MAX_ATTEMPTS) {
						transition(transaction, Phase.FAILED, result == XaeroMapAdapter.ApplyResult.UNAVAILABLE
								? "adapter-unavailable" : "retry-exhausted");
						return;
					}
					transaction.retryAtMillis = nowMillis + retryDelay(transaction.attempts);
					transition(transaction, Phase.WAIT_REFRESH, "xaero-region-busy");
					return;
				}
				case WAIT_REFRESH -> {
					if (nowMillis < transaction.retryAtMillis) return;
					transition(transaction, Phase.COMMITTING, "refresh-recheck");
				}
				case APPLIED, FAILED, MANIFEST, FETCHING -> { return; }
			}
		}
	}

	private void transition(Transaction transaction, Phase next, String reason) {
		Phase previous = transaction.phase;
		transaction.phase = next;
		transitionSink.accept(new Transition(transaction.patch.manifest().key(), transaction.patch.manifest().epoch(),
				transaction.patch.manifest().contentHash(), previous, next, transaction.attempts, reason));
	}

	private static long retryDelay(int attempts) {
		return Math.min(5_000L, BASE_RETRY_MILLIS << Math.min(Math.max(attempts, 0), 5));
	}

	private static long localRetryDelay(int checks) {
		long delay = BASE_RETRY_MILLIS << Math.min(Math.max(checks - 1, 0), 3);
		return Math.min(MAX_LOCAL_RETRY_MILLIS, delay);
	}

	private static String regionKey(MapPatchKey key) {
		return key.dimension() + ":" + key.xaeroRegionX() + ":" + key.xaeroRegionZ();
	}

	public synchronized int pendingCount() { return pendingCount; }
	public synchronized int activeRegionCount() { return byRegion.size(); }
	public synchronized boolean hasPending(MapPatchKey key) { return pendingHashes.containsKey(key); }
	public synchronized Statistics statistics(long nowMillis) {
		EnumMap<Phase, Integer> phaseCounts = new EnumMap<>(Phase.class);
		for (Phase phase : Phase.values()) phaseCounts.put(phase, 0);
		int localWaiting = 0;
		long oldestLocalWaitMillis = 0L;
		for (ArrayDeque<Transaction> queue : byRegion.values()) {
			for (Transaction transaction : queue) {
				phaseCounts.merge(transaction.phase, 1, Integer::sum);
				if (transaction.phase == Phase.WAIT_XAERO_IDLE) {
					localWaiting++;
					oldestLocalWaitMillis = Math.max(oldestLocalWaitMillis,
							Math.max(0L, nowMillis - transaction.localWaitStartedMillis));
				}
			}
		}
		return new Statistics(pendingCount, byRegion.size(), localWaiting, oldestLocalWaitMillis,
				forcedRemoteCommits, localGenerationRechecks, Map.copyOf(phaseCounts));
	}
	public synchronized void clear() {
		byRegion.clear();
		pendingHashes.clear();
		pendingCount = 0;
		forcedRemoteCommits = 0L;
		localGenerationRechecks = 0L;
	}

	public record Transition(MapPatchKey key, long epoch, long patchHash, Phase previous, Phase next, int attempts,
			String reason) { }
	public record Statistics(int pending, int activeRegions, int localWaiting, long oldestLocalWaitMillis,
			long forcedRemoteCommits, long localGenerationRechecks, Map<Phase, Integer> phaseCounts) { }

	private static final class Transaction {
		private final MapPatch patch;
		private Phase phase = Phase.FETCHING;
		private List<MapTile> remoteTiles = List.of();
		private long retryAtMillis;
		private int attempts;
		private long localWaitStartedMillis = -1L;
		private int localWaitChecks;
		private boolean forcedRemote;

		private Transaction(MapPatch patch) { this.patch = patch; }
	}
}
