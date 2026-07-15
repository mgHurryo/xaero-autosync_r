package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;
import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

public final class ClientMapTileCache {
	private final Map<String, CachedTile> tiles = new LinkedHashMap<>();
	private Path persistencePath;
	private boolean dirty;

	public synchronized boolean hasRevision(String dimension, int chunkX, int chunkZ, long revision) {
		CachedTile tile = tiles.get(key(dimension, chunkX, chunkZ));
		return tile != null && tile.revision >= revision;
	}

	public synchronized void put(MapTile tile, long revision) {
		tiles.put(key(tile.dimension(), tile.chunkX(), tile.chunkZ()), new CachedTile(tile, revision, tile.dimension(), tile.chunkX(), tile.chunkZ()));
		dirty = true;
	}

	public synchronized int totalCount() {
		return tiles.size();
	}

	public synchronized void load(Path path) {
		persistencePath = path;
		if (!Files.isRegularFile(path)) return;
		Properties values = new Properties();
		try (InputStream input = Files.newInputStream(path)) {
			values.load(input);
			for (String encodedKey : values.stringPropertyNames()) {
				String[] parts = encodedKey.split(",", 3);
				if (parts.length != 3) continue;
				String dimension = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
				int chunkX = Integer.parseInt(parts[1]);
				int chunkZ = Integer.parseInt(parts[2]);
				long revision = Long.parseLong(values.getProperty(encodedKey));
				tiles.put(key(dimension, chunkX, chunkZ), new CachedTile(null, revision, dimension, chunkX, chunkZ));
			}
		} catch (IOException | RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to load client tile revisions at {}", path, exception);
		}
	}

	public synchronized void saveIfDirty() {
		if (!dirty || persistencePath == null) return;
		Properties values = new Properties();
		for (CachedTile tile : tiles.values()) {
			String dimension = Base64.getUrlEncoder().withoutPadding().encodeToString(tile.dimension.getBytes(StandardCharsets.UTF_8));
			values.setProperty(dimension + "," + tile.chunkX + "," + tile.chunkZ, Long.toString(tile.revision));
		}
		Path temp = persistencePath.resolveSibling(persistencePath.getFileName() + ".tmp");
		try {
			Files.createDirectories(persistencePath.getParent());
			try (OutputStream output = Files.newOutputStream(temp)) { values.store(output, "Xaero Map Sync applied revisions"); }
			try {
				Files.move(temp, persistencePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
				Files.move(temp, persistencePath, StandardCopyOption.REPLACE_EXISTING);
			}
			dirty = false;
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to save client tile revisions at {}", persistencePath, exception);
		}
	}

	private static String key(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + ChunkPos.asLong(chunkX, chunkZ);
	}

	private static final class CachedTile {
		private final MapTile tile;
		private final long revision;
		private final String dimension;
		private final int chunkX;
		private final int chunkZ;

		private CachedTile(MapTile tile, long revision, String dimension, int chunkX, int chunkZ) {
			this.tile = tile;
			this.revision = revision;
			this.dimension = dimension;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
		}
	}
}
