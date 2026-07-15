package cn.net.rms.xaeromapsync_r.xaero;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

final class XaeroWaypointIdentity {
	private static final String PREFIX = " [xms-";
	private static final String SUFFIX = "]";
	private static final int ENCODED_UUID_LENGTH = 22;
	private static final int MARKER_LENGTH = PREFIX.length() + ENCODED_UUID_LENGTH + SUFFIX.length();

	private XaeroWaypointIdentity() {
	}

	static String managedName(String displayName, UUID id) {
		return displayName + PREFIX + encode(id) + SUFFIX;
	}

	static Optional<UUID> parse(String name) {
		if (name == null || name.length() < MARKER_LENGTH) {
			return Optional.empty();
		}
		int markerStart = name.length() - MARKER_LENGTH;
		if (!name.startsWith(PREFIX, markerStart) || !name.endsWith(SUFFIX)) {
			return Optional.empty();
		}
		String encoded = name.substring(markerStart + PREFIX.length(), name.length() - SUFFIX.length());
		try {
			byte[] bytes = Base64.getUrlDecoder().decode(encoded);
			if (bytes.length != 16) {
				return Optional.empty();
			}
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			UUID id = new UUID(buffer.getLong(), buffer.getLong());
			return encode(id).equals(encoded) ? Optional.of(id) : Optional.empty();
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static String encode(UUID id) {
		ByteBuffer buffer = ByteBuffer.allocate(16);
		buffer.putLong(id.getMostSignificantBits());
		buffer.putLong(id.getLeastSignificantBits());
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
	}
}
