package cn.net.rms.xaeromapsync_r.server;

import java.util.UUID;

public final class ServerClientState {
	private final UUID playerId;
	private final String playerName;
	private final boolean accepted;
	private final UUID traceId;
	private final long connectedAtMillis;

	public ServerClientState(UUID playerId, String playerName, boolean accepted) {
		this(playerId, playerName, accepted, UUID.randomUUID(), System.currentTimeMillis());
	}

	public ServerClientState(UUID playerId, String playerName, boolean accepted, UUID traceId, long connectedAtMillis) {
		this.playerId = playerId;
		this.playerName = playerName;
		this.accepted = accepted;
		this.traceId = traceId;
		this.connectedAtMillis = connectedAtMillis;
	}

	public UUID playerId() {
		return playerId;
	}

	public String playerName() {
		return playerName;
	}

	public boolean accepted() {
		return accepted;
	}

	public UUID traceId() { return traceId; }
	public long connectedAtMillis() { return connectedAtMillis; }
}
