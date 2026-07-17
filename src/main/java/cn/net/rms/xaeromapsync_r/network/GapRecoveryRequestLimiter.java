package cn.net.rms.xaeromapsync_r.network;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-client sliding-window limiter for peer recovery fan-out. */
final class GapRecoveryRequestLimiter {
	private static final int MAX_BATCHES_PER_WINDOW = 4;
	private static final long WINDOW_MILLIS = 1_000L;
	private final Map<UUID, ArrayDeque<Long>> requests = new HashMap<>();

	synchronized boolean acquire(UUID playerId, long nowMillis) {
		ArrayDeque<Long> timestamps = requests.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
		while (!timestamps.isEmpty() && nowMillis - timestamps.peekFirst() >= WINDOW_MILLIS)
			timestamps.removeFirst();
		if (timestamps.size() >= MAX_BATCHES_PER_WINDOW) return false;
		timestamps.addLast(nowMillis);
		return true;
	}

	synchronized void remove(UUID playerId) { requests.remove(playerId); }
}
