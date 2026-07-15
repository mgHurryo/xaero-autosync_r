package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;

public final class ClientMapTileIndexCache {
	private final Map<String, MapTileIndexEntry> entries = new LinkedHashMap<>();
	private long rootHash;

	public synchronized void replace(long rootHash, Collection<MapTileIndexEntry> snapshot) {
		this.rootHash = rootHash;
		entries.clear();
		for (MapTileIndexEntry entry : snapshot) {
			entries.put(key(entry.dimension(), entry.chunkX(), entry.chunkZ()), entry);
		}
	}

	public synchronized long rootHash() {
		return rootHash;
	}

	public synchronized int totalCount() {
		return entries.size();
	}

	public synchronized List<MapTileIndexEntry> missingFrom(ClientMapTileCache cache, int limit) {
		List<MapTileIndexEntry> missing = new ArrayList<>();
		for (MapTileIndexEntry entry : entries.values()) {
			if (!cache.hasRevision(entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision())) {
				missing.add(entry);
				if (missing.size() >= limit) {
					break;
				}
			}
		}
		return missing;
	}

	private static String key(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + ChunkPos.asLong(chunkX, chunkZ);
	}
}
