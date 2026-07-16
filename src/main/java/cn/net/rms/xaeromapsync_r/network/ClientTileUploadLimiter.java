package cn.net.rms.xaeromapsync_r.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bounds compressed upload work before a tile is decoded on the network thread. */
final class ClientTileUploadLimiter {
	static final int DEFAULT_PACKETS_PER_WINDOW = 512;
	static final long DEFAULT_BYTES_PER_WINDOW = 16L * 1024L * 1024L;
	static final long DEFAULT_WINDOW_MILLIS = 10_000L;
	private final int maxPackets;
	private final long maxBytes;
	private final long windowMillis;
	private final Map<UUID, Window> players = new HashMap<>();

	ClientTileUploadLimiter() {
		this(DEFAULT_PACKETS_PER_WINDOW, DEFAULT_BYTES_PER_WINDOW, DEFAULT_WINDOW_MILLIS);
	}

	ClientTileUploadLimiter(int maxPackets, long maxBytes, long windowMillis) {
		if (maxPackets <= 0 || maxBytes <= 0L || windowMillis <= 0L) {
			throw new IllegalArgumentException("Invalid client tile upload limits");
		}
		this.maxPackets = maxPackets;
		this.maxBytes = maxBytes;
		this.windowMillis = windowMillis;
	}

	synchronized boolean acquire(UUID playerId, int bytes, long nowMillis) {
		if (bytes <= 0) return false;
		Window window = players.computeIfAbsent(playerId, ignored -> new Window(nowMillis));
		if (nowMillis < window.startedAt || nowMillis - window.startedAt >= windowMillis) {
			window.startedAt = nowMillis;
			window.packets = 0;
			window.bytes = 0L;
		}
		if (window.packets >= maxPackets || bytes > maxBytes - window.bytes) return false;
		window.packets++;
		window.bytes += bytes;
		return true;
	}

	synchronized void remove(UUID playerId) {
		players.remove(playerId);
	}

	private static final class Window {
		private long startedAt;
		private int packets;
		private long bytes;

		private Window(long startedAt) {
			this.startedAt = startedAt;
		}
	}
}
