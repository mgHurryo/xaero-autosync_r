package cn.net.rms.xaeromapsync_r.xaero;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

final class XaeroWaypointIdentity {
	private static final String LOCK_PREFIX = "\u26BF ";
	private static final String LEGACY_LOCK_PREFIX = "\uD83D\uDD12 ";
	private static final char FORMAT_CODE = '\u00A7';
	private static final String FORMAT_RESET = "\u00A7r";
	private static final int FORMATTED_DIGIT_COUNT = 32;
	private static final int FORMATTED_MARKER_LENGTH = (FORMATTED_DIGIT_COUNT + 2) * 2;
	private static final String LEGACY_PREFIX = " [xms-";
	private static final String LEGACY_SUFFIX = "]";
	private static final int ENCODED_UUID_LENGTH = 22;
	private static final int LEGACY_MARKER_LENGTH = LEGACY_PREFIX.length() + ENCODED_UUID_LENGTH + LEGACY_SUFFIX.length();
	private static final char HIDDEN_PREFIX = '\u2063';
	private static final char HIDDEN_SUFFIX = '\u2064';
	private static final char HIDDEN_DIGIT_BASE = '\uFE00';
	private static final int HIDDEN_DIGIT_COUNT = 32;
	private static final int HIDDEN_MARKER_LENGTH = HIDDEN_DIGIT_COUNT + 2;

	private XaeroWaypointIdentity() {
	}

	static String managedName(String displayName, UUID id) {
		displayName = stripLock(displayName);
		StringBuilder result = new StringBuilder(displayName.length() + LOCK_PREFIX.length() + FORMATTED_MARKER_LENGTH);
		result.append(LOCK_PREFIX).append(displayName).append(FORMAT_RESET);
		for (byte value : bytes(id)) {
			result.append(FORMAT_CODE).append(Character.forDigit((value >>> 4) & 0xF, 16));
			result.append(FORMAT_CODE).append(Character.forDigit(value & 0xF, 16));
		}
		return result.append(FORMAT_RESET).toString();
	}

	static Optional<UUID> parse(String name) {
		Optional<UUID> formatted = parseFormatted(name);
		if (formatted.isPresent()) {
			return formatted;
		}
		Optional<UUID> hidden = parseHidden(name);
		return hidden.isPresent() ? hidden : parseLegacy(name);
	}

	private static Optional<UUID> parseFormatted(String name) {
		if (name == null || name.length() < FORMATTED_MARKER_LENGTH) {
			return Optional.empty();
		}
		int markerStart = name.length() - FORMATTED_MARKER_LENGTH;
		if (!name.startsWith(FORMAT_RESET, markerStart) || !name.endsWith(FORMAT_RESET)) {
			return Optional.empty();
		}
		byte[] bytes = new byte[16];
		int offset = markerStart + FORMAT_RESET.length();
		for (int index = 0; index < FORMATTED_DIGIT_COUNT; index += 2) {
			int high = formattedDigit(name, offset + index * 2);
			int low = formattedDigit(name, offset + (index + 1) * 2);
			if (high < 0 || low < 0) {
				return Optional.empty();
			}
			bytes[index / 2] = (byte) ((high << 4) | low);
		}
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		return Optional.of(new UUID(buffer.getLong(), buffer.getLong()));
	}

	private static int formattedDigit(String name, int offset) {
		return name.charAt(offset) == FORMAT_CODE ? Character.digit(name.charAt(offset + 1), 16) : -1;
	}

	private static Optional<UUID> parseHidden(String name) {
		if (name == null || name.length() < HIDDEN_MARKER_LENGTH
				|| name.charAt(name.length() - HIDDEN_MARKER_LENGTH) != HIDDEN_PREFIX
				|| name.charAt(name.length() - 1) != HIDDEN_SUFFIX) {
			return Optional.empty();
		}
		byte[] bytes = new byte[16];
		int offset = name.length() - HIDDEN_MARKER_LENGTH + 1;
		for (int index = 0; index < HIDDEN_DIGIT_COUNT; index += 2) {
			int high = name.charAt(offset + index) - HIDDEN_DIGIT_BASE;
			int low = name.charAt(offset + index + 1) - HIDDEN_DIGIT_BASE;
			if (high < 0 || high > 15 || low < 0 || low > 15) {
				return Optional.empty();
			}
			bytes[index / 2] = (byte) ((high << 4) | low);
		}
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		return Optional.of(new UUID(buffer.getLong(), buffer.getLong()));
	}

	private static Optional<UUID> parseLegacy(String name) {
		if (name == null || name.length() < LEGACY_MARKER_LENGTH) {
			return Optional.empty();
		}
		int markerStart = name.length() - LEGACY_MARKER_LENGTH;
		if (!name.startsWith(LEGACY_PREFIX, markerStart) || !name.endsWith(LEGACY_SUFFIX)) {
			return Optional.empty();
		}
		String encoded = name.substring(markerStart + LEGACY_PREFIX.length(), name.length() - LEGACY_SUFFIX.length());
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

	static boolean isManagedName(String name) {
		if (parseFormatted(name).isPresent() || parseHidden(name).isPresent()) {
			return true;
		}
		if (name == null || !name.endsWith(LEGACY_SUFFIX)) {
			return false;
		}
		int markerStart = name.lastIndexOf(LEGACY_PREFIX);
		return markerStart >= 0
				&& markerStart + LEGACY_PREFIX.length() < name.length() - LEGACY_SUFFIX.length();
	}

	static String displayName(String name) {
		String visibleName;
		if (parseFormatted(name).isPresent()) {
			visibleName = name.substring(0, name.length() - FORMATTED_MARKER_LENGTH);
		} else if (parseHidden(name).isPresent()) {
			visibleName = name.substring(0, name.length() - HIDDEN_MARKER_LENGTH);
		} else {
			visibleName = parseLegacy(name).isPresent()
					? name.substring(0, name.length() - LEGACY_MARKER_LENGTH)
					: name;
		}
		return stripLock(visibleName);
	}

	private static String stripLock(String name) {
		if (name == null) {
			return null;
		}
		if (name.startsWith(LOCK_PREFIX)) {
			return name.substring(LOCK_PREFIX.length());
		}
		return name.startsWith(LEGACY_LOCK_PREFIX) ? name.substring(LEGACY_LOCK_PREFIX.length()) : name;
	}

	private static String encode(UUID id) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(id));
	}

	private static byte[] bytes(UUID id) {
		ByteBuffer buffer = ByteBuffer.allocate(16);
		buffer.putLong(id.getMostSignificantBits());
		buffer.putLong(id.getLeastSignificantBits());
		return buffer.array();
	}
}
