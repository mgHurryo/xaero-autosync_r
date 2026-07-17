package cn.net.rms.xaeromapsync_r.network;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class LocalTileReadyHintLimiter {
	static final int DEFAULT_MAX_HINTS_PER_WINDOW = 16_384;
	static final long DEFAULT_WINDOW_MILLIS = 10_000L;
	static final long DEFAULT_DUPLICATE_COOLDOWN_MILLIS = 1_000L;
	private static final int MAX_RECENT_HINTS_PER_PLAYER = 16_384;

	enum Result {
		ACCEPTED,
		RATE_LIMITED,
		DUPLICATE
	}

	private final int maxHintsPerWindow;
	private final long windowMillis;
	private final long duplicateCooldownMillis;
	private final Map<UUID, PlayerState> players = new HashMap<>();

	LocalTileReadyHintLimiter() {
		this(DEFAULT_MAX_HINTS_PER_WINDOW, DEFAULT_WINDOW_MILLIS, DEFAULT_DUPLICATE_COOLDOWN_MILLIS);
	}

	LocalTileReadyHintLimiter(int maxHintsPerWindow, long windowMillis, long duplicateCooldownMillis) {
		if (maxHintsPerWindow <= 0 || windowMillis <= 0L || duplicateCooldownMillis < 0L) {
			throw new IllegalArgumentException("Invalid local tile hint limiter settings");
		}
		this.maxHintsPerWindow = maxHintsPerWindow;
		this.windowMillis = windowMillis;
		this.duplicateCooldownMillis = duplicateCooldownMillis;
	}

	synchronized Result acquire(UUID playerId, LocalTileReadyPayload hint, long nowMillis) {
		PlayerState state = players.computeIfAbsent(playerId, ignored -> new PlayerState(nowMillis));
		if (nowMillis < state.windowStartedAt || nowMillis - state.windowStartedAt >= windowMillis) {
			state.windowStartedAt = nowMillis;
			state.hintsInWindow = 0;
		}
		if (state.hintsInWindow >= maxHintsPerWindow) {
			return Result.RATE_LIMITED;
		}
		state.hintsInWindow++;

		HintKey key = new HintKey(hint.dimension(), hint.chunkX(), hint.chunkZ(), hint.contentHash());
		Long previous = state.recentHints.get(key);
		if (isDuplicate(previous, nowMillis, duplicateCooldownMillis)) {
			return Result.DUPLICATE;
		}
		state.recentHints.remove(key);
		state.recentHints.put(key, nowMillis);
		pruneRecentHints(state, nowMillis);
		return Result.ACCEPTED;
	}

	static boolean isDuplicate(Long previous, long nowMillis, long cooldownMillis) {
		if (previous == null) return false;
		long previousMillis = previous.longValue();
		return nowMillis >= previousMillis && nowMillis - previousMillis < cooldownMillis;
	}

	synchronized void remove(UUID playerId) {
		players.remove(playerId);
	}

	private void pruneRecentHints(PlayerState state, long nowMillis) {
		Iterator<Map.Entry<HintKey, Long>> iterator = state.recentHints.entrySet().iterator();
		while (iterator.hasNext()) {
			long timestamp = iterator.next().getValue();
			if (state.recentHints.size() <= MAX_RECENT_HINTS_PER_PLAYER
					&& nowMillis >= timestamp && nowMillis - timestamp < duplicateCooldownMillis) {
				break;
			}
			iterator.remove();
		}
	}

	private static final class PlayerState {
		private long windowStartedAt;
		private int hintsInWindow;
		private final LinkedHashMap<HintKey, Long> recentHints = new LinkedHashMap<>();

		private PlayerState(long windowStartedAt) {
			this.windowStartedAt = windowStartedAt;
		}
	}

	private static final class HintKey {
		private final String dimension;
		private final int chunkX;
		private final int chunkZ;
		private final long contentHash;

		private HintKey(String dimension, int chunkX, int chunkZ, long contentHash) {
			this.dimension = dimension;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.contentHash = contentHash;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) return true;
			if (!(other instanceof HintKey)) return false;
			HintKey key = (HintKey) other;
			return chunkX == key.chunkX && chunkZ == key.chunkZ && contentHash == key.contentHash
					&& dimension.equals(key.dimension);
		}

		@Override
		public int hashCode() {
			return Objects.hash(dimension, chunkX, chunkZ, contentHash);
		}
	}
}
