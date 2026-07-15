package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;

public final class ClientMapTileCache {
	private final Map<String, CachedTile> tiles = new LinkedHashMap<>();

	public synchronized boolean hasRevision(String dimension, int chunkX, int chunkZ, long revision) {
		CachedTile tile = tiles.get(key(dimension, chunkX, chunkZ));
		return tile != null && tile.revision >= revision;
	}

	public synchronized void put(MapTile tile, long revision) {
		tiles.put(key(tile.dimension(), tile.chunkX(), tile.chunkZ()), new CachedTile(tile, revision));
	}

	public synchronized int totalCount() {
		return tiles.size();
	}

	private static String key(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + ChunkPos.asLong(chunkX, chunkZ);
	}

	private static final class CachedTile {
		private final MapTile tile;
		private final long revision;

		private CachedTile(MapTile tile, long revision) {
			this.tile = tile;
			this.revision = revision;
		}
	}
}
