package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class SharedMapClientConfig {
	private static final String FILE_NAME = "xaero-mapsync_r-client.properties";
	private static final String MAP_SYNC_KEY = "mapSyncEnabled";
	private static final String PUBLIC_WAYPOINTS_KEY = "publicWaypointsEnabled";
	private static final String NOTIFICATIONS_KEY = "notificationsEnabled";
	private static final SharedMapClientConfig INSTANCE = new SharedMapClientConfig(
			FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));

	private final Path path;
	private boolean mapSyncEnabled = true;
	private boolean publicWaypointsEnabled = true;
	private boolean notificationsEnabled = true;

	private SharedMapClientConfig(Path path) {
		this.path = path;
	}

	public static SharedMapClientConfig get() {
		return INSTANCE;
	}

	public synchronized void load() {
		if (!Files.isRegularFile(path)) {
			save();
			return;
		}

		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(path)) {
			properties.load(input);
			mapSyncEnabled = readBoolean(properties, MAP_SYNC_KEY, true);
			publicWaypointsEnabled = readBoolean(properties, PUBLIC_WAYPOINTS_KEY, true);
			notificationsEnabled = readBoolean(properties, NOTIFICATIONS_KEY, true);
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to load client config at {}", path, exception);
		}
	}

	public synchronized boolean mapSyncEnabled() {
		return mapSyncEnabled;
	}

	public synchronized boolean publicWaypointsEnabled() {
		return publicWaypointsEnabled;
	}

	public synchronized boolean notificationsEnabled() {
		return notificationsEnabled;
	}

	public synchronized void setMapSyncEnabled(boolean enabled) {
		mapSyncEnabled = enabled;
		save();
	}

	public synchronized void setPublicWaypointsEnabled(boolean enabled) {
		publicWaypointsEnabled = enabled;
		save();
	}

	public synchronized void setNotificationsEnabled(boolean enabled) {
		notificationsEnabled = enabled;
		save();
	}

	private void save() {
		Properties properties = new Properties();
		properties.setProperty(MAP_SYNC_KEY, Boolean.toString(mapSyncEnabled));
		properties.setProperty(PUBLIC_WAYPOINTS_KEY, Boolean.toString(publicWaypointsEnabled));
		properties.setProperty(NOTIFICATIONS_KEY, Boolean.toString(notificationsEnabled));

		Path temporaryPath = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			try (OutputStream output = Files.newOutputStream(temporaryPath)) {
				properties.store(output, "Xaero Map Sync client settings");
			}
			try {
				Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to save client config at {}", path, exception);
			try {
				Files.deleteIfExists(temporaryPath);
			} catch (IOException cleanupException) {
				XaeroMapsync_r.LOGGER.debug("Failed to remove temporary client config at {}", temporaryPath, cleanupException);
			}
		}
	}

	private static boolean readBoolean(Properties properties, String key, boolean fallback) {
		String value = properties.getProperty(key);
		if (value == null) {
			return fallback;
		}
		if ("true".equalsIgnoreCase(value)) {
			return true;
		}
		if ("false".equalsIgnoreCase(value)) {
			return false;
		}
		XaeroMapsync_r.LOGGER.warn("Ignoring invalid boolean value for client config key {}", key);
		return fallback;
	}
}
