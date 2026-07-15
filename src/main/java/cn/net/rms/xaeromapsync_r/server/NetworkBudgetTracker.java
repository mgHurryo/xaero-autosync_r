package cn.net.rms.xaeromapsync_r.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class NetworkBudgetTracker {
	private static final int DEFAULT_BYTES_PER_TICK = 32768;
	private final Map<UUID, Integer> spentByPlayer = new ConcurrentHashMap<>();
	private volatile int bytesPerPlayerPerTick = DEFAULT_BYTES_PER_TICK;

	public NetworkBudgetTracker() {
		ServerTickEvents.END_SERVER_TICK.register(server -> spentByPlayer.clear());
	}

	public synchronized boolean trySpend(UUID playerId, int bytes) {
		int current = spentByPlayer.getOrDefault(playerId, 0);
		if (current + bytes > bytesPerPlayerPerTick) {
			return false;
		}
		spentByPlayer.put(playerId, current + bytes);
		return true;
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
}
