package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

final class WaypointPayloadCodec {
	static final int MAX_DIMENSION_LENGTH = 256;
	static final int MAX_CREATOR_NAME_LENGTH = 64;
	static final int MAX_SYMBOL_LENGTH = 16;
	static final int MAX_CATEGORY_LENGTH = 64;
	static final int MAX_SNAPSHOT_WAYPOINTS = 4096;
	private static final WaypointVisibility[] VISIBILITY_VALUES = WaypointVisibility.values();

	private WaypointPayloadCodec() {
	}

	static PublicWaypoint readMutationWaypoint(FriendlyByteBuf buffer) {
		PublicWaypoint waypoint = readWaypointFields(buffer, 0L, false, 0L, 0L);
		waypoint.validate();
		return waypoint;
	}

	static void writeMutationWaypoint(FriendlyByteBuf buffer, PublicWaypoint waypoint) {
		waypoint.validate();
		writeWaypointFields(buffer, waypoint);
	}

	static PublicWaypoint readSnapshotWaypoint(FriendlyByteBuf buffer) {
		WaypointFields fields = readWaypointFields(buffer);
		PublicWaypoint waypoint = fields.toWaypoint(
				buffer.readVarLong(),
				buffer.readBoolean(),
				buffer.readLong(),
				buffer.readLong());
		waypoint.validate();
		return waypoint;
	}

	static void writeSnapshotWaypoint(FriendlyByteBuf buffer, PublicWaypoint waypoint) {
		waypoint.validate();
		writeWaypointFields(buffer, waypoint);
		buffer.writeVarLong(waypoint.revision());
		buffer.writeBoolean(waypoint.deleted());
		buffer.writeLong(waypoint.createdAtMillis());
		buffer.writeLong(waypoint.updatedAtMillis());
	}

	static void writeUuid(FriendlyByteBuf buffer, UUID id) {
		buffer.writeLong(id.getMostSignificantBits());
		buffer.writeLong(id.getLeastSignificantBits());
	}

	static UUID readUuid(FriendlyByteBuf buffer) {
		return new UUID(buffer.readLong(), buffer.readLong());
	}

	private static PublicWaypoint readWaypointFields(FriendlyByteBuf buffer, long revision, boolean deleted, long createdAtMillis, long updatedAtMillis) {
		return readWaypointFields(buffer).toWaypoint(revision, deleted, createdAtMillis, updatedAtMillis);
	}

	private static WaypointFields readWaypointFields(FriendlyByteBuf buffer) {
		return new WaypointFields(
				readUuid(buffer),
				readNullableUuid(buffer),
				readNullableString(buffer, MAX_CREATOR_NAME_LENGTH),
				buffer.readUtf(PublicWaypoint.MAX_NAME_LENGTH),
				buffer.readUtf(MAX_DIMENSION_LENGTH),
				buffer.readDouble(),
				buffer.readDouble(),
				buffer.readDouble(),
				readNullableString(buffer, MAX_SYMBOL_LENGTH),
				buffer.readInt(),
				readNullableString(buffer, MAX_CATEGORY_LENGTH),
				readVisibility(buffer));
	}

	private static void writeWaypointFields(FriendlyByteBuf buffer, PublicWaypoint waypoint) {
		writeUuid(buffer, waypoint.id());
		writeNullableUuid(buffer, waypoint.creatorId());
		writeNullableString(buffer, waypoint.creatorName(), MAX_CREATOR_NAME_LENGTH);
		buffer.writeUtf(waypoint.name(), PublicWaypoint.MAX_NAME_LENGTH);
		buffer.writeUtf(waypoint.dimension(), MAX_DIMENSION_LENGTH);
		buffer.writeDouble(waypoint.x());
		buffer.writeDouble(waypoint.y());
		buffer.writeDouble(waypoint.z());
		writeNullableString(buffer, waypoint.symbol(), MAX_SYMBOL_LENGTH);
		buffer.writeInt(waypoint.color());
		writeNullableString(buffer, waypoint.category(), MAX_CATEGORY_LENGTH);
		buffer.writeVarInt(waypoint.visibility().ordinal());
	}

	private static UUID readNullableUuid(FriendlyByteBuf buffer) {
		if (!buffer.readBoolean()) {
			return null;
		}
		return readUuid(buffer);
	}

	private static void writeNullableUuid(FriendlyByteBuf buffer, UUID id) {
		buffer.writeBoolean(id != null);
		if (id != null) {
			writeUuid(buffer, id);
		}
	}

	private static String readNullableString(FriendlyByteBuf buffer, int maxLength) {
		if (!buffer.readBoolean()) {
			return null;
		}
		return buffer.readUtf(maxLength);
	}

	private static void writeNullableString(FriendlyByteBuf buffer, String value, int maxLength) {
		buffer.writeBoolean(value != null);
		if (value != null) {
			buffer.writeUtf(value, maxLength);
		}
	}

	private static WaypointVisibility readVisibility(FriendlyByteBuf buffer) {
		int ordinal = buffer.readVarInt();
		if (ordinal < 0 || ordinal >= VISIBILITY_VALUES.length) {
			throw new IllegalArgumentException("Unknown waypoint visibility ordinal: " + ordinal);
		}
		return VISIBILITY_VALUES[ordinal];
	}

	private static final class WaypointFields {
		private final UUID id;
		private final UUID creatorId;
		private final String creatorName;
		private final String name;
		private final String dimension;
		private final double x;
		private final double y;
		private final double z;
		private final String symbol;
		private final int color;
		private final String category;
		private final WaypointVisibility visibility;

		private WaypointFields(UUID id, UUID creatorId, String creatorName, String name, String dimension, double x, double y, double z, String symbol, int color, String category, WaypointVisibility visibility) {
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
		}

		private PublicWaypoint toWaypoint(long revision, boolean deleted, long createdAtMillis, long updatedAtMillis) {
			return new PublicWaypoint(id, creatorId, creatorName, name, dimension, x, y, z, symbol, color, category, visibility, revision, deleted, createdAtMillis, updatedAtMillis);
		}
	}
}
