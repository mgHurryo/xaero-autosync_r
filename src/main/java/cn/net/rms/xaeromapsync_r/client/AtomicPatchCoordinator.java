package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
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
	private static final int MAX_TILES_PER_COMMIT = 16;
	private static final int MAX_ATTEMPTS_PER_RETRY_WINDOW = 8;
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
		return enqueueVerifiedWave(List.of(patch));
	}

	/** Opens one verified download wave as one Xaero mutation per native region. */
	public synchronized boolean enqueueVerifiedWave(List<MapPatch> patches) {
		if (patches == null || patches.isEmpty()) return true;
		Map<String, List<MapPatch>> grouped = new LinkedHashMap<>();
		for (MapPatch patch : patches) {
			if (patch == null || pendingHashes.containsKey(patch.manifest().key())) return false;
			grouped.computeIfAbsent(regionKey(patch.manifest().key()), ignored -> new ArrayList<>()).add(patch);
		}
		if (pendingCount + patches.size() > MAX_PENDING_PATCHES) return false;
		for (Map.Entry<String, List<MapPatch>> group : grouped.entrySet()) {
			Transaction transaction = new Transaction(group.getValue());
			byRegion.computeIfAbsent(group.getKey(), ignored -> new ArrayDeque<>()).addLast(transaction);
			transition(transaction, Phase.VERIFIED, "wave-body-validated");
		}
		for (MapPatch patch : patches)
			pendingHashes.put(patch.manifest().key(), patch.manifest().contentHash());
		pendingCount += patches.size();
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
			try {
				process(transaction, nowMillis);
			} catch (RuntimeException exception) {
				MapPatch representative = transaction.patches.get(0);
				XaeroMapsync_r.LOGGER.error(
						"map_sync event=patch_process_failed patch_id={} phase={} tiles={}",
						representative.manifest().key().stableId(), transaction.phase, transaction.tiles.size(), exception);
				transition(transaction, Phase.FAILED, "runtime-exception");
			}
			if (transaction.phase == Phase.APPLIED || transaction.phase == Phase.FAILED) {
				queue.removeFirst();
				for (MapPatch patch : transaction.patches) pendingHashes.remove(patch.manifest().key());
				pendingCount -= transaction.patches.size();
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
					for (MapTile tile : transaction.tiles) {
						XaeroMapAdapter.LocalTileState state = adapter.localTileState(tile);
						if (state == XaeroMapAdapter.LocalTileState.READY) continue;
						if (state == XaeroMapAdapter.LocalTileState.GENERATING) {
							localGenerating = true;
							// A loaded local chunk always belongs to Xaero. The timeout only
							// releases the sync queue; it never authorizes a remote overwrite.
							continue;
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
					transaction.forcedRemote = localGenerating;
					transition(transaction, Phase.COMMITTING, localGenerating
							? "local-generation-timeout-local-authoritative"
							: remote.isEmpty() ? "local-authoritative" : "xaero-region-ready");
				}
				case WAIT_XAERO_IDLE -> {
					if (nowMillis < transaction.retryAtMillis) return;
					transaction.phase = Phase.PREPARED;
				}
				case COMMITTING -> {
					List<MapTile> commitSlice = transaction.nextCommitSlice();
					XaeroMapAdapter.ApplyResult result = commitSlice.isEmpty()
							? XaeroMapAdapter.ApplyResult.APPLIED
							: adapter.applyBatchResult(commitSlice);
					if (result == XaeroMapAdapter.ApplyResult.APPLIED) {
						transaction.committedTileCount += commitSlice.size();
						transaction.attempts = 0;
						if (transaction.committedTileCount < transaction.remoteTiles.size()) {
							// Each slice is bounded so one region cannot monopolize the client
							// tick. The next slice waits for Xaero's requested refresh to settle.
							transaction.retryAtMillis = nowMillis + BASE_RETRY_MILLIS;
							transition(transaction, Phase.WAIT_REFRESH, "commit-slice-complete");
							return;
						}
						// A loaded tile remains owned by Xaero after the bounded wait. Mark the
						// patch complete once its remote subset is committed; Xaero's eventual
						// local render is uploaded as a newer authoritative revision.
						for (MapPatch patch : transaction.patches) appliedSink.accept(patch);
						if (transaction.forcedRemote) forcedRemoteCommits++;
						transition(transaction, Phase.APPLIED, "atomic-commit-complete");
						return;
					}
					if (result == XaeroMapAdapter.ApplyResult.UNAVAILABLE) {
						transition(transaction, Phase.FAILED, "adapter-unavailable");
						return;
					}
					transaction.attempts++;
					if (transaction.attempts >= MAX_ATTEMPTS_PER_RETRY_WINDOW) {
						// Xaero refresh can legitimately remain pending for longer than one
						// retry window. Keep the verified transaction instead of discarding
						// it and starting a duplicate manifest/download loop.
						transaction.attempts = 0;
						transaction.retryAtMillis = nowMillis + retryDelay(MAX_ATTEMPTS_PER_RETRY_WINDOW);
						transition(transaction, Phase.WAIT_REFRESH, "retry-window-reset");
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
		MapPatch representative = transaction.patches.get(0);
		transitionSink.accept(new Transition(representative.manifest().key(), representative.manifest().epoch(),
				representative.manifest().contentHash(), previous, next, transaction.attempts, reason));
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
		private final List<MapPatch> patches;
		private final List<MapTile> tiles;
		private Phase phase = Phase.FETCHING;
		private List<MapTile> remoteTiles = List.of();
		private long retryAtMillis;
		private int attempts;
		private long localWaitStartedMillis = -1L;
		private int localWaitChecks;
		private boolean forcedRemote;
		private int committedTileCount;

		private Transaction(List<MapPatch> patches) {
			this.patches = List.copyOf(patches);
			this.tiles = patches.stream().flatMap(patch -> patch.tiles().stream()).toList();
		}

		private List<MapTile> nextCommitSlice() {
			int end = Math.min(remoteTiles.size(), committedTileCount + MAX_TILES_PER_COMMIT);
			return remoteTiles.subList(committedTileCount, end);
		}
	}
}
