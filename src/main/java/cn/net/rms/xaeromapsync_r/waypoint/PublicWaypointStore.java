package cn.net.rms.xaeromapsync_r.waypoint;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class PublicWaypointStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Map<UUID, PublicWaypoint> waypoints = new LinkedHashMap<>();
	private long nextRevision = 1L;

	public synchronized void load(MinecraftServer server) {
		Path path = path(server);
		boolean migrated = false;
		waypoints.clear();
		nextRevision = 1L;
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			WaypointFile file = GSON.fromJson(reader, WaypointFile.class);
			if (file == null) {
				return;
			}
			nextRevision = Math.max(1L, file.nextRevision);
			if (file.waypoints != null) {
				for (PublicWaypoint waypoint : file.waypoints) {
					if (waypoint != null && waypoint.id() != null) {
						int normalizedColor = XaeroWaypointPalette.normalize(waypoint.color());
						if (normalizedColor != waypoint.color()) {
							XaeroMapsync_r.LOGGER.warn("Migrated invalid Xaero waypoint color {} to palette index {} for {}",
									waypoint.color(), normalizedColor, waypoint.id());
							waypoint = waypoint.withColor(normalizedColor);
							migrated = true;
						}
						waypoints.put(waypoint.id(), waypoint);
						nextRevision = Math.max(nextRevision, waypoint.revision() + 1L);
					}
				}
			}
			XaeroMapsync_r.LOGGER.info("Loaded {} public waypoints", waypoints.size());
		} catch (IOException | RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to load public waypoints at {}", path, exception);
			return;
		}
		if (migrated) {
			save(server);
		}
	}

	public synchronized void save(MinecraftServer server) {
		Path path = path(server);
		Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			WaypointFile file = new WaypointFile();
			file.nextRevision = nextRevision;
			file.waypoints = waypoints.values().toArray(new PublicWaypoint[0]);
			try (Writer writer = Files.newBufferedWriter(tempPath)) {
				GSON.toJson(file, writer);
			}
			Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to save public waypoints at {}", path, exception);
		}
	}

	public synchronized Collection<PublicWaypoint> snapshot() {
		return Collections.unmodifiableCollection(waypoints.values());
	}

	public synchronized Optional<PublicWaypoint> find(UUID waypointId) {
		return Optional.ofNullable(waypoints.get(waypointId));
	}

	public synchronized PublicWaypoint upsert(PublicWaypoint waypoint, long nowMillis) {
		waypoint.validate();
		PublicWaypoint current = waypoints.get(waypoint.id());
		long createdAtMillis = current == null ? nowMillis : current.createdAtMillis();
		PublicWaypoint stored = waypoint.withServerState(nextRevision(), false, createdAtMillis, nowMillis);
		waypoints.put(stored.id(), stored);
		return stored;
	}

	public synchronized PublicWaypoint delete(UUID waypointId, long nowMillis) {
		if (waypointId == null) {
			throw new IllegalArgumentException("Waypoint id is required");
		}
		PublicWaypoint current = waypoints.get(waypointId);
		if (current == null) {
			return null;
		}
		if (current.deleted()) {
			return current;
		}
		PublicWaypoint tombstone = current.tombstone(nextRevision(), nowMillis);
		waypoints.put(waypointId, tombstone);
		return tombstone;
	}

	public synchronized int activeCount() {
		int count = 0;
		for (PublicWaypoint waypoint : waypoints.values()) {
			if (!waypoint.deleted()) {
				count++;
			}
		}
		return count;
	}

	public synchronized int activeCount(UUID creatorId) {
		int count = 0;
		for (PublicWaypoint waypoint : waypoints.values()) {
			if (!waypoint.deleted() && creatorId.equals(waypoint.creatorId())) count++;
		}
		return count;
	}

	public synchronized int deletedCount() {
		return waypoints.size() - activeCount();
	}

	public synchronized long nextRevision() {
		return nextRevision++;
	}

	private static Path path(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("xaero-mapsync_r").resolve("public_waypoints.json");
	}

	private static final class WaypointFile {
		private long nextRevision;
		private PublicWaypoint[] waypoints;
	}
}
