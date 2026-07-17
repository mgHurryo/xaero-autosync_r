package cn.net.rms.xaeromapsync_r.server.access;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Append-only JSON Lines audit trail for access changes and waypoint mutations. */
public final class SharedMapAuditLog {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private Path path;

	public synchronized void start(Path path) {
		this.path = path;
	}

	public synchronized void record(
			SharedMapActor actor,
			String action,
			boolean success,
			RegionKey region,
			UUID waypointId,
			String detail) {
		AuditEntry entry = new AuditEntry(System.currentTimeMillis(), actor, action, success, region, waypointId, detail);
		XaeroMapsync_r.LOGGER.info("Shared map audit action={} success={} actor={} team={} region={} waypoint={} detail={}",
				action,
				success,
				actor.playerName(),
				actor.teamName(),
				region,
				waypointId,
				detail);
		if (path == null) {
			return;
		}
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(
					path,
					GSON.toJson(entry) + System.lineSeparator(),
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to append shared map audit log at {}", path, exception);
		}
	}

	private static final class AuditEntry {
		private final long timestampMillis;
		private final String action;
		private final boolean success;
		private final String actorId;
		private final String actorName;
		private final String actorTeam;
		private final boolean operator;
		private final String dimension;
		private final Integer regionX;
		private final Integer regionZ;
		private final String waypointId;
		private final String detail;

		private AuditEntry(long timestampMillis, SharedMapActor actor, String action, boolean success, RegionKey region, UUID waypointId, String detail) {
			this.timestampMillis = timestampMillis;
			this.action = action;
			this.success = success;
			this.actorId = actor.playerId() == null ? null : actor.playerId().toString();
			this.actorName = actor.playerName();
			this.actorTeam = actor.teamName();
			this.operator = actor.operator();
			this.dimension = region == null ? null : region.dimension();
			this.regionX = region == null ? null : region.regionX();
			this.regionZ = region == null ? null : region.regionZ();
			this.waypointId = waypointId == null ? null : waypointId.toString();
			this.detail = detail;
		}
	}
}
