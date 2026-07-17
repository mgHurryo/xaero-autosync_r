package cn.net.rms.xaeromapsync_r.waypoint;

import java.util.UUID;

public final class PublicWaypoint {
	public static final int MAX_NAME_LENGTH = 64;
	public static final int MAX_TEAM_NAME_LENGTH = 64;

	private UUID id;
	private UUID creatorId;
	private String creatorName;
	private String name;
	private String dimension;
	private double x;
	private double y;
	private double z;
	private String symbol;
	private int color;
	private String category;
	private WaypointVisibility visibility;
	private String teamName;
	private long revision;
	private boolean deleted;
	private long createdAtMillis;
	private long updatedAtMillis;

	private PublicWaypoint() {
	}

	public PublicWaypoint(UUID id, UUID creatorId, String creatorName, String name, String dimension, double x, double y, double z, String symbol, int color, String category, WaypointVisibility visibility, long revision, boolean deleted, long createdAtMillis, long updatedAtMillis) {
		this(id, creatorId, creatorName, name, dimension, x, y, z, symbol, color, category, visibility, null, revision, deleted, createdAtMillis, updatedAtMillis);
	}

	public PublicWaypoint(UUID id, UUID creatorId, String creatorName, String name, String dimension, double x, double y, double z, String symbol, int color, String category, WaypointVisibility visibility, String teamName, long revision, boolean deleted, long createdAtMillis, long updatedAtMillis) {
		this.id = id;
		this.creatorId = creatorId;
		this.creatorName = creatorName;
		this.name = name;
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		this.symbol = symbol;
		this.color = color;
		this.category = category;
		this.visibility = visibility;
		this.teamName = teamName;
		this.revision = revision;
		this.deleted = deleted;
		this.createdAtMillis = createdAtMillis;
		this.updatedAtMillis = updatedAtMillis;
	}

	public UUID id() {
		return id;
	}

	public UUID creatorId() {
		return creatorId;
	}

	public String creatorName() {
		return creatorName;
	}

	public String name() {
		return name;
	}

	public String dimension() {
		return dimension;
	}

	public double x() {
		return x;
	}

	public double y() {
		return y;
	}

	public double z() {
		return z;
	}

	public String symbol() {
		return symbol;
	}

	public int color() {
		return color;
	}

	public String category() {
		return category;
	}

	public WaypointVisibility visibility() {
		return visibility;
	}

	public String teamName() {
		return teamName;
	}

	public long revision() {
		return revision;
	}

	public boolean deleted() {
		return deleted;
	}

	public long createdAtMillis() {
		return createdAtMillis;
	}

	public long updatedAtMillis() {
		return updatedAtMillis;
	}

	public PublicWaypoint withServerState(long nextRevision, boolean deleted, long createdAtMillis, long updatedAtMillis) {
		return new PublicWaypoint(id, creatorId, creatorName, name, dimension, x, y, z, symbol, color, category, visibility, teamName, nextRevision, deleted, createdAtMillis, updatedAtMillis);
	}

	public PublicWaypoint tombstone(long nextRevision, long nowMillis) {
		return new PublicWaypoint(id, creatorId, creatorName, name, dimension, x, y, z, symbol, color, category, visibility, teamName, nextRevision, true, createdAtMillis, nowMillis);
	}

	public PublicWaypoint withOwnership(UUID ownerId, String ownerName, String ownerTeamName) {
		return new PublicWaypoint(id, ownerId, ownerName, name, dimension, x, y, z, symbol, color, category, visibility, ownerTeamName, revision, deleted, createdAtMillis, updatedAtMillis);
	}

	public PublicWaypoint withColor(int normalizedColor) {
		return new PublicWaypoint(id, creatorId, creatorName, name, dimension, x, y, z, symbol, normalizedColor, category,
				visibility, teamName, revision, deleted, createdAtMillis, updatedAtMillis);
	}

	public void validate() {
		if (id == null) {
			throw new IllegalArgumentException("Waypoint id is required");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Waypoint name is required");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			throw new IllegalArgumentException("Waypoint name must be at most " + MAX_NAME_LENGTH + " characters");
		}
		if (dimension == null || dimension.isBlank()) {
			throw new IllegalArgumentException("Waypoint dimension is required");
		}
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			throw new IllegalArgumentException("Waypoint coordinates must be finite");
		}
		if (visibility == null) {
			throw new IllegalArgumentException("Waypoint visibility is required");
		}
		if (teamName != null && (teamName.isBlank() || teamName.length() > MAX_TEAM_NAME_LENGTH)) {
			throw new IllegalArgumentException("Waypoint team name must contain 1-64 characters");
		}
	}
}
