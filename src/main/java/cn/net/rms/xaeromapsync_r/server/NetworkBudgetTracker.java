package cn.net.rms.xaeromapsync_r.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class NetworkBudgetTracker {
	private static final int DEFAULT_BYTES_PER_TICK = 32768;
	private static final int DEFAULT_GLOBAL_BYTES_PER_TICK = 262144;
	private static final int SAMPLE_WINDOW = 1200;
	private final Map<UUID, Integer> spentByPlayer = new ConcurrentHashMap<>();
	private final int[] bytesPerTickSamples = new int[SAMPLE_WINDOW];
	private volatile int bytesPerPlayerPerTick = DEFAULT_BYTES_PER_TICK;
	private volatile int globalBytesPerTick = DEFAULT_GLOBAL_BYTES_PER_TICK;
	private int spentGlobal;
	private int lastCompletedTickBytes;
	private int sampleCursor;
	private int sampleCount;
	private long totalBytes;
	private long rejectedBytes;

	public NetworkBudgetTracker() {
		ServerTickEvents.START_SERVER_TICK.register(server -> beginTick());
	}

	public synchronized boolean trySpend(UUID playerId, int bytes) {
		if (playerId == null) throw new IllegalArgumentException("Player id is required");
		if (bytes < 0) throw new IllegalArgumentException("Network bytes must not be negative");
		int current = spentByPlayer.getOrDefault(playerId, 0);
		if ((long) current + bytes > bytesPerPlayerPerTick || (long) spentGlobal + bytes > globalBytesPerTick) {
			rejectedBytes += bytes;
			return false;
		}
		spentByPlayer.put(playerId, current + bytes);
		spentGlobal += bytes;
		totalBytes += bytes;
		return true;
	}

	private synchronized void beginTick() {
		lastCompletedTickBytes = spentGlobal;
		bytesPerTickSamples[sampleCursor] = spentGlobal;
		sampleCursor = (sampleCursor + 1) % SAMPLE_WINDOW;
		sampleCount = Math.min(SAMPLE_WINDOW, sampleCount + 1);
		spentGlobal = 0;
		spentByPlayer.clear();
	}

	public int bytesPerPlayerPerTick() {
		return bytesPerPlayerPerTick;
	}

	public void setBytesPerPlayerPerTick(int bytesPerPlayerPerTick) {
		if (bytesPerPlayerPerTick <= 0) {
			throw new IllegalArgumentException("Network budget must be positive");
		}
		this.bytesPerPlayerPerTick = bytesPerPlayerPerTick;
	}

	public int globalBytesPerTick() { return globalBytesPerTick; }

	public void setGlobalBytesPerTick(int globalBytesPerTick) {
		if (globalBytesPerTick <= 0) throw new IllegalArgumentException("Global network budget must be positive");
		this.globalBytesPerTick = globalBytesPerTick;
	}

	public synchronized long totalBytes() { return totalBytes; }
	public synchronized long rejectedBytes() { return rejectedBytes; }
	public synchronized int currentTickBytes() { return spentGlobal; }
	public synchronized int lastCompletedTickBytes() { return lastCompletedTickBytes; }

	public synchronized int p95BytesPerTick() {
		if (sampleCount == 0) return 0;
		int[] copy = java.util.Arrays.copyOf(bytesPerTickSamples, sampleCount);
		java.util.Arrays.sort(copy);
		return copy[(int) Math.ceil(copy.length * 0.95D) - 1];
	}
}
