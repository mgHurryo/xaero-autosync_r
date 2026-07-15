package cn.net.rms.xaeromapsync_r.config;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class SharedMapConfig {
	private static final String FILE_NAME = XaeroMapsync_r.MOD_ID + ".properties";
	private static final Properties VALUES = new Properties();

	private SharedMapConfig() {
	}

	public static void register() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		load(path);
		save(path);
	}

	public static int protocolVersion() {
		return intValue("protocol.version", SharedMapProtocolDefaults.PROTOCOL_VERSION);
	}

	public static int mapFormatVersion() {
		return intValue("map.format.version", SharedMapProtocolDefaults.MAP_FORMAT_VERSION);
	}

	public static int maxPacketBytes() {
		return intValue("network.max_packet_bytes", SharedMapProtocolDefaults.MAX_PACKET_BYTES);
	}

	public static int maxTileRequestsPerSnapshot() {
		return intValue("network.max_tile_requests_per_snapshot", 16);
	}

	public static String compression() {
		return VALUES.getProperty("network.compression", SharedMapProtocolDefaults.COMPRESSION);
	}

	private static void load(Path path) {
		defaults().forEach((key, value) -> VALUES.setProperty((String) key, (String) value));
		if (!Files.exists(path)) {
			return;
		}
		try (InputStream input = Files.newInputStream(path)) {
			VALUES.load(input);
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to load shared map config at {}", path, exception);
		}
	}

	private static void save(Path path) {
		try {
			Files.createDirectories(path.getParent());
			try (OutputStream output = Files.newOutputStream(path)) {
				VALUES.store(output, "Xaero Map Sync configuration");
			}
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to save shared map config at {}", path, exception);
		}
	}

	private static int intValue(String key, int fallback) {
		try {
			return Integer.parseInt(VALUES.getProperty(key));
		} catch (NumberFormatException exception) {
			XaeroMapsync_r.LOGGER.warn("Invalid integer config value for {}, using {}", key, fallback);
			return fallback;
		}
	}

	private static Properties defaults() {
		Properties defaults = new Properties();
		defaults.setProperty("protocol.version", Integer.toString(SharedMapProtocolDefaults.PROTOCOL_VERSION));
		defaults.setProperty("map.format.version", Integer.toString(SharedMapProtocolDefaults.MAP_FORMAT_VERSION));
		defaults.setProperty("xaero.adapter.version", SharedMapProtocolDefaults.XAERO_ADAPTER_VERSION);
		defaults.setProperty("network.compression", SharedMapProtocolDefaults.COMPRESSION);
		defaults.setProperty("network.max_packet_bytes", Integer.toString(SharedMapProtocolDefaults.MAX_PACKET_BYTES));
		defaults.setProperty("network.max_tile_requests_per_snapshot", "16");
		defaults.setProperty("exploration.auto_view_distance", "true");
		defaults.setProperty("exploration.edge_chunk_margin", "1");
		defaults.setProperty("tasks.normal_tick_budget_ms", "2");
		defaults.setProperty("tasks.high_load_pause", "true");
		defaults.setProperty("waypoints.allow_player_upload", "true");
		defaults.setProperty("waypoints.max_per_player", "256");
		return defaults;
	}
}
