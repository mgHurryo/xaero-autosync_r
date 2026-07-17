package cn.net.rms.xaeromapsync_r.network;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-client sliding-window limiter for peer recovery fan-out. */
final class GapRecoveryRequestLimiter {
	private static final int MAX_BATCHES_PER_WINDOW = 4;
	private static final int MAX_GLOBAL_BATCHES_PER_WINDOW = 32;
	private static final long WINDOW_MILLIS = 1_000L;
	private final Map<UUID, ArrayDeque<Long>> requests = new HashMap<>();
	private final ArrayDeque<Long> globalRequests = new ArrayDeque<>();

	synchronized boolean acquire(UUID playerId, long nowMillis) {
		prune(globalRequests, nowMillis);
		if (globalRequests.size() >= MAX_GLOBAL_BATCHES_PER_WINDOW) return false;
		ArrayDeque<Long> timestamps = requests.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
		prune(timestamps, nowMillis);
		if (timestamps.size() >= MAX_BATCHES_PER_WINDOW) return false;
		timestamps.addLast(nowMillis);
		globalRequests.addLast(nowMillis);
		return true;
	}

	synchronized void remove(UUID playerId) { requests.remove(playerId); }

	private static void prune(ArrayDeque<Long> timestamps, long nowMillis) {
		while (!timestamps.isEmpty() && nowMillis - timestamps.peekFirst() >= WINDOW_MILLIS)
			timestamps.removeFirst();
	}
}
