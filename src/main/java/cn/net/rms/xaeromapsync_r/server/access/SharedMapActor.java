package cn.net.rms.xaeromapsync_r.server.access;

import java.util.Objects;
import java.util.UUID;

/** Immutable server-side identity used for access decisions and audit records. */
public record SharedMapActor(UUID playerId, String playerName, String teamName, boolean operator) {
	public SharedMapActor {
		Objects.requireNonNull(playerName, "playerName");
		if (playerName.isBlank()) {
			throw new IllegalArgumentException("Player name must not be blank");
		}
		if (teamName != null && teamName.isBlank()) {
			teamName = null;
		}
	}
}
