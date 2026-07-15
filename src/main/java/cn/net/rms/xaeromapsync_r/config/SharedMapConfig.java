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

	public static boolean highLoadPause() {
		return booleanValue("tasks.high_load_pause", true);
	}

	public static int highLoadMsptThreshold() {
		return intValue("tasks.high_load_mspt_threshold", 45);
	}

	public static int dirtyChunksPerTick() {
		return intValue("tasks.dirty_chunks_per_tick", 1);
	}

	public static int dirtyDrainBudgetPerTick() {
		return intValue("tasks.dirty_drain_budget_per_tick", 4);
	}

	public static int stormBlockChangesThreshold() { return intValue("activity.storm_block_changes_per_tick", 4096); }
	public static int stormDirtyChunksThreshold() { return intValue("activity.storm_dirty_chunks_per_tick", 16); }
	public static int stormCooldownTicks() { return intValue("activity.storm_cooldown_ticks", 100); }
	public static int stableTicks() { return intValue("activity.stable_ticks", 200); }
	public static boolean allowPlayerWaypointUpload() { return booleanValue("waypoints.allow_player_upload", true); }
	public static int maxWaypointsPerPlayer() { return intValue("waypoints.max_per_player", 256); }
	public static int maxPublicWaypoints() { return intValue("waypoints.max_total", 4096); }

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

	private static boolean booleanValue(String key, boolean fallback) {
		String value = VALUES.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value);
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
		defaults.setProperty("tasks.high_load_mspt_threshold", "45");
		defaults.setProperty("tasks.dirty_chunks_per_tick", "1");
		defaults.setProperty("tasks.dirty_drain_budget_per_tick", "4");
		defaults.setProperty("activity.storm_block_changes_per_tick", "4096");
		defaults.setProperty("activity.storm_dirty_chunks_per_tick", "16");
		defaults.setProperty("activity.storm_cooldown_ticks", "100");
		defaults.setProperty("activity.stable_ticks", "200");
		defaults.setProperty("waypoints.allow_player_upload", "true");
		defaults.setProperty("waypoints.max_per_player", "256");
		defaults.setProperty("waypoints.max_total", "4096");
		return defaults;
	}
}
