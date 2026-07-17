package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.map.MapPatch;
import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.xaero.XaeroMapAdapter;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
	private final XaeroMapAdapter adapter;
	private final Consumer<MapPatch> appliedSink;
	private final Consumer<Transition> transitionSink;
	private final Map<String, ArrayDeque<Transaction>> byRegion = new LinkedHashMap<>();
	private final Map<MapPatchKey, Long> pendingHashes = new HashMap<>();
	private int pendingCount;

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
			Transaction transaction = queue.peekFirst();
			process(transaction, nowMillis);
			if (transaction.phase == Phase.APPLIED || transaction.phase == Phase.FAILED) {
				queue.removeFirst();
				pendingHashes.remove(transaction.patch.manifest().key());
				pendingCount--;
				if (queue.isEmpty()) byRegion.remove(region);
				completed++;
			}
		}
		return completed;
	}

	private void process(Transaction transaction, long nowMillis) {
		for (int transitions = 0; transitions < 4; transitions++) {
			switch (transaction.phase) {
				case VERIFIED -> transition(transaction, Phase.PREPARED, "verified-to-prepared");
				case PREPARED -> {
					List<MapTile> remote = new ArrayList<>(MapPatchKey.TILE_COUNT);
					for (MapTile tile : transaction.patch.tiles()) {
						XaeroMapAdapter.LocalTileState state = adapter.localTileState(tile);
						if (state == XaeroMapAdapter.LocalTileState.GENERATING) {
							transaction.retryAtMillis = nowMillis + retryDelay(transaction.attempts);
							transition(transaction, Phase.WAIT_XAERO_IDLE, "local-generating");
							return;
						}
						if (state != XaeroMapAdapter.LocalTileState.READY) remote.add(tile);
					}
					transaction.remoteTiles = List.copyOf(remote);
					transition(transaction, Phase.COMMITTING, remote.isEmpty() ? "local-authoritative" : "xaero-region-ready");
				}
				case WAIT_XAERO_IDLE -> {
					if (nowMillis < transaction.retryAtMillis) return;
					transition(transaction, Phase.PREPARED, "local-generation-recheck");
				}
				case COMMITTING -> {
					XaeroMapAdapter.ApplyResult result = transaction.remoteTiles.isEmpty()
							? XaeroMapAdapter.ApplyResult.APPLIED
							: adapter.applyBatchResult(transaction.remoteTiles);
					if (result == XaeroMapAdapter.ApplyResult.APPLIED) {
						appliedSink.accept(transaction.patch);
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

	private static String regionKey(MapPatchKey key) {
		return key.dimension() + ":" + key.xaeroRegionX() + ":" + key.xaeroRegionZ();
	}

	public synchronized int pendingCount() { return pendingCount; }
	public synchronized int activeRegionCount() { return byRegion.size(); }
	public synchronized void clear() {
		byRegion.clear();
		pendingHashes.clear();
		pendingCount = 0;
	}

	public record Transition(MapPatchKey key, long epoch, long patchHash, Phase previous, Phase next, int attempts,
			String reason) { }

	private static final class Transaction {
		private final MapPatch patch;
		private Phase phase = Phase.FETCHING;
		private List<MapTile> remoteTiles = List.of();
		private long retryAtMillis;
		private int attempts;

		private Transaction(MapPatch patch) { this.patch = patch; }
	}
}
