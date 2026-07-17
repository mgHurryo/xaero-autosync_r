package cn.net.rms.xaeromapsync_r.server;

import cn.net.rms.xaeromapsync_r.network.TileRequestPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.level.ChunkPos;

/** Coalesces short-lived peer recovery requests for the same map tile. */
public final class GapRecoveryBroker {
	public static final long PEER_WAIT_MILLIS = 750L;
	private static final int MAX_PENDING_COORDINATES = 2_048;
	private final Map<Key, Pending> pending = new LinkedHashMap<>();

	public synchronized RequestResult request(UUID requester, TileRequestPayload request, long nowMillis) {
		Key key = Key.from(request);
		Pending existing = pending.get(key);
		if (existing != null) {
			existing.requesters.put(requester, request);
			return RequestResult.COALESCED;
		}
		if (pending.size() >= MAX_PENDING_COORDINATES) return RequestResult.REJECTED;
		Pending created = new Pending(nowMillis + PEER_WAIT_MILLIS);
		created.requesters.put(requester, request);
		pending.put(key, created);
		return RequestResult.NEW;
	}

	public synchronized List<WaitingRequest> resolve(String dimension, int chunkX, int chunkZ) {
		Pending resolved = pending.remove(new Key(dimension, ChunkPos.asLong(chunkX, chunkZ)));
		return resolved == null ? List.of() : resolved.requests();
	}

	public synchronized List<WaitingRequest> expire(long nowMillis, int limit) {
		List<WaitingRequest> expired = new ArrayList<>();
		var iterator = pending.entrySet().iterator();
		while (iterator.hasNext() && expired.size() < limit) {
			Map.Entry<Key, Pending> entry = iterator.next();
			if (nowMillis < entry.getValue().deadlineMillis) continue;
			expired.addAll(entry.getValue().requests());
			iterator.remove();
		}
		return List.copyOf(expired);
	}

	public synchronized void removeRequester(UUID requester) {
		pending.values().forEach(value -> value.requesters.remove(requester));
		pending.entrySet().removeIf(entry -> entry.getValue().requesters.isEmpty());
	}

	public synchronized void clear() { pending.clear(); }

	public record WaitingRequest(UUID requester, TileRequestPayload request) { }
	public enum RequestResult { NEW, COALESCED, REJECTED }
	private record Key(String dimension, long coordinate) {
		private static Key from(TileRequestPayload request) {
			return new Key(request.dimension(), ChunkPos.asLong(request.chunkX(), request.chunkZ()));
		}
	}
	private static final class Pending {
		private final long deadlineMillis;
		private final Map<UUID, TileRequestPayload> requesters = new LinkedHashMap<>();
		private Pending(long deadlineMillis) { this.deadlineMillis = deadlineMillis; }
		private List<WaitingRequest> requests() {
			return requesters.entrySet().stream().map(entry -> new WaitingRequest(entry.getKey(), entry.getValue())).toList();
		}
	}
}
