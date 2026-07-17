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
		migrateDefault("tasks.dirty_chunks_per_tick", "1", "2048");
		migrateDefault("tasks.dirty_chunks_per_tick", "16", "2048");
		migrateDefault("tasks.dirty_chunk_scan_per_tick", "64", "4096");
		migrateDefault("tasks.dirty_chunk_scan_per_tick", "512", "4096");
		migrateDefault("tasks.dirty_drain_budget_per_tick", "4", "2048");
		migrateDefault("tasks.dirty_drain_budget_per_tick", "16", "2048");
		migrateDefault("tasks.max_tile_renders_per_tick", "8", "2048");
		migrateDefault("tasks.max_tile_renders_per_tick", "16", "2048");
		migrateDefault("tasks.map_render_budget_ms", "10", "40");
		migrateDefault("tasks.map_render_budget_ms", "25", "40");
		migrateDefault("network.max_tile_requests_per_snapshot", "16", "256");
		migrateDefault("network.bytes_per_player_per_tick", "32768", "262144");
		migrateDefault("network.global_bytes_per_tick", "262144", "2097152");
		VALUES.setProperty("protocol.version", Integer.toString(SharedMapProtocolDefaults.PROTOCOL_VERSION));
		VALUES.setProperty("map.format.version", Integer.toString(SharedMapProtocolDefaults.MAP_FORMAT_VERSION));
		normalizeIntegerValues();
		save(path);
	}

	public static int protocolVersion() {
		return intValue("protocol.version", SharedMapProtocolDefaults.PROTOCOL_VERSION);
	}

	public static int mapFormatVersion() {
		return SharedMapProtocolDefaults.MAP_FORMAT_VERSION;
	}

	public static int maxPacketBytes() {
		return intValue("network.max_packet_bytes", SharedMapProtocolDefaults.MAX_PACKET_BYTES);
	}

	public static int maxTileRequestsPerSnapshot() {
		return intValue("network.max_tile_requests_per_snapshot", 256);
	}

	public static int bytesPerPlayerPerTick() {
		return positiveIntValue("network.bytes_per_player_per_tick", 262_144);
	}

	public static int globalBytesPerTick() {
		return positiveIntValue("network.global_bytes_per_tick", 2_097_152);
	}

	public static String compression() {
		return VALUES.getProperty("network.compression", SharedMapProtocolDefaults.COMPRESSION);
	}

	public static boolean mapSyncEnabled() { return booleanValue("map.sync.enabled", true); }
	public static boolean mapSyncShadowMode() { return booleanValue("map.sync.shadow_mode", false); }
	/** Server terrain sampling is opt-in; shared map bodies normally come from Xaero clients. */
	public static boolean serverMapRenderingEnabled() {
		return booleanValue("map.sync.server_render.enabled", false);
	}

	public static boolean highLoadPause() {
		return booleanValue("tasks.high_load_pause", true);
	}

	public static int highLoadMsptThreshold() {
		return intValue("tasks.high_load_mspt_threshold", 45);
	}

	public static int dirtyChunksPerTick() {
		return intValue("tasks.dirty_chunks_per_tick", 2048);
	}

	public static int dirtyChunkScanPerTick() {
		return positiveIntValue("tasks.dirty_chunk_scan_per_tick", 4096);
	}

	public static int dirtyDrainBudgetPerTick() {
		return intValue("tasks.dirty_drain_budget_per_tick", 2048);
	}

	public static int maxTileRendersPerTick() {
		return positiveIntValue("tasks.max_tile_renders_per_tick", 2048);
	}

	public static int mapRenderBudgetMillis() {
		return positiveIntValue("tasks.map_render_budget_ms", 40);
	}

	public static int stormBlockChangesThreshold() { return positiveIntValue("activity.storm_block_changes_per_tick", 4096); }
	public static int stormDirtyChunksThreshold() { return positiveIntValue("activity.storm_dirty_chunks_per_tick", 16); }
	public static int stormTntEntitiesThreshold() { return positiveIntValue("activity.storm_tnt_entities_per_tick", 64); }
	public static int stormExplosionsThreshold() { return positiveIntValue("activity.storm_explosions_per_tick", 8); }
	public static int stormPistonActionsThreshold() { return positiveIntValue("activity.storm_piston_actions_per_tick", 128); }
	public static int stormLightUpdatesThreshold() { return positiveIntValue("activity.storm_light_updates_per_tick", 2048); }
	public static int stormCooldownTicks() { return positiveIntValue("activity.storm_cooldown_ticks", 100); }
	public static int stableTicks() { return positiveIntValue("activity.stable_ticks", 200); }
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

	private static void migrateDefault(String key, String previousDefault, String currentDefault) {
		if (previousDefault.equals(VALUES.getProperty(key))) VALUES.setProperty(key, currentDefault);
	}

	private static int intValue(String key, int fallback) {
		try {
			return Integer.parseInt(VALUES.getProperty(key));
		} catch (NumberFormatException exception) {
			XaeroMapsync_r.LOGGER.warn("Invalid integer config value for {}, using {}", key, fallback);
			return fallback;
		}
	}

	private static int positiveIntValue(String key, int fallback) {
		int value = intValue(key, fallback);
		if (value > 0) {
			return value;
		}
		XaeroMapsync_r.LOGGER.warn("Non-positive config value for {}, using {}", key, fallback);
		return fallback;
	}

	private static boolean booleanValue(String key, boolean fallback) {
		String value = VALUES.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value);
	}

	private static void normalizeIntegerValues() {
		normalizeInteger("network.max_packet_bytes", SharedMapProtocolDefaults.MAX_PACKET_BYTES);
		normalizeInteger("network.max_tile_requests_per_snapshot", 256);
		normalizeInteger("network.bytes_per_player_per_tick", 262_144);
		normalizeInteger("network.global_bytes_per_tick", 2_097_152);
		normalizeInteger("tasks.high_load_mspt_threshold", 45);
		normalizeInteger("tasks.dirty_chunks_per_tick", 2_048);
		normalizeInteger("tasks.dirty_chunk_scan_per_tick", 4_096);
		normalizeInteger("tasks.dirty_drain_budget_per_tick", 2_048);
		normalizeInteger("tasks.max_tile_renders_per_tick", 2_048);
		normalizeInteger("tasks.map_render_budget_ms", 40);
		normalizeInteger("activity.storm_block_changes_per_tick", 4_096);
		normalizeInteger("activity.storm_dirty_chunks_per_tick", 16);
		normalizeInteger("activity.storm_tnt_entities_per_tick", 64);
		normalizeInteger("activity.storm_explosions_per_tick", 8);
		normalizeInteger("activity.storm_piston_actions_per_tick", 128);
		normalizeInteger("activity.storm_light_updates_per_tick", 2_048);
		normalizeInteger("activity.storm_cooldown_ticks", 100);
		normalizeInteger("activity.stable_ticks", 200);
		normalizeInteger("waypoints.max_per_player", 256);
		normalizeInteger("waypoints.max_total", 4_096);
	}

	private static void normalizeInteger(String key, int fallback) {
		String raw = VALUES.getProperty(key);
		int normalized = normalizedPositiveInteger(raw, fallback);
		if (raw != null && raw.equals(Integer.toString(normalized))) return;
		XaeroMapsync_r.LOGGER.warn("Invalid config value for {}, using {}", key, fallback);
		VALUES.setProperty(key, Integer.toString(normalized));
	}

	static int normalizedPositiveInteger(String raw, int fallback) {
		try {
			int value = Integer.parseInt(raw);
			return value > 0 ? value : fallback;
		} catch (NumberFormatException exception) {
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
		defaults.setProperty("network.max_tile_requests_per_snapshot", "256");
		defaults.setProperty("network.bytes_per_player_per_tick", "262144");
		defaults.setProperty("network.global_bytes_per_tick", "2097152");
		defaults.setProperty("map.sync.enabled", "true");
		defaults.setProperty("map.sync.shadow_mode", "false");
		defaults.setProperty("map.sync.server_render.enabled", "false");
		defaults.setProperty("exploration.auto_view_distance", "true");
		defaults.setProperty("exploration.edge_chunk_margin", "1");
		defaults.setProperty("tasks.normal_tick_budget_ms", "2");
		defaults.setProperty("tasks.high_load_pause", "true");
		defaults.setProperty("tasks.high_load_mspt_threshold", "45");
		defaults.setProperty("tasks.dirty_chunks_per_tick", "2048");
		defaults.setProperty("tasks.dirty_chunk_scan_per_tick", "4096");
		defaults.setProperty("tasks.dirty_drain_budget_per_tick", "2048");
		defaults.setProperty("tasks.max_tile_renders_per_tick", "2048");
		defaults.setProperty("tasks.map_render_budget_ms", "40");
		defaults.setProperty("activity.storm_block_changes_per_tick", "4096");
		defaults.setProperty("activity.storm_dirty_chunks_per_tick", "16");
		defaults.setProperty("activity.storm_tnt_entities_per_tick", "64");
		defaults.setProperty("activity.storm_explosions_per_tick", "8");
		defaults.setProperty("activity.storm_piston_actions_per_tick", "128");
		defaults.setProperty("activity.storm_light_updates_per_tick", "2048");
		defaults.setProperty("activity.storm_cooldown_ticks", "100");
		defaults.setProperty("activity.stable_ticks", "200");
		defaults.setProperty("waypoints.allow_player_upload", "true");
		defaults.setProperty("waypoints.max_per_player", "256");
		defaults.setProperty("waypoints.max_total", "4096");
		return defaults;
	}
}
