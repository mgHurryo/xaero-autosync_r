package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.map.MerkleTreeBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

	public synchronized void setRootHash(long rootHash) {
		this.rootHash = rootHash;
	}

	public synchronized void upsert(MapTileIndexEntry entry) {
		String key = key(entry.dimension(), entry.chunkX(), entry.chunkZ());
		MapTileIndexEntry current = entries.get(key);
		if (current == null || entry.revision() >= current.revision()) entries.put(key, entry);
	}

	public synchronized long rootHash() {
		return rootHash;
	}

	public synchronized int totalCount() {
		return entries.size();
	}

	public synchronized Optional<MapTileIndexEntry> find(String dimension, int chunkX, int chunkZ) {
		return Optional.ofNullable(entries.get(key(dimension, chunkX, chunkZ)));
	}

	public synchronized long computedRootHash() {
		return MerkleTreeBuilder.rootHash(MerkleTreeBuilder.build(entries.values()));
	}

	public synchronized long computedRootHash(String dimension) {
		return MerkleTreeBuilder.rootHash(MerkleTreeBuilder.build(entries.values().stream()
				.filter(entry -> entry.dimension().equals(dimension)).toList()));
	}

	public synchronized boolean matchesRootHash(long expectedRootHash) {
		return computedRootHash() == expectedRootHash;
	}

	public synchronized boolean matchesRootHash(String dimension, long expectedRootHash) {
		return computedRootHash(dimension) == expectedRootHash;
	}

	public synchronized List<MapTileIndexEntry> missingFrom(ClientMapTileCache cache, int limit) {
		List<MapTileIndexEntry> missing = new ArrayList<>();
		for (MapTileIndexEntry entry : entries.values()) {
			if (!cache.hasRevision(entry.dimension(), entry.chunkX(), entry.chunkZ(), entry.revision())) {
				missing.add(entry);
			}
		}
		missing.sort(Comparator.comparing(MapTileIndexEntry::dimension)
				.thenComparingInt(entry -> Math.floorDiv(entry.chunkX(), 32))
				.thenComparingInt(entry -> Math.floorDiv(entry.chunkZ(), 32))
				.thenComparingInt(MapTileIndexEntry::chunkX)
				.thenComparingInt(MapTileIndexEntry::chunkZ));
		if (missing.size() > limit) return new ArrayList<>(missing.subList(0, limit));
		return missing;
	}

	private static String key(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + ChunkPos.asLong(chunkX, chunkZ);
	}
}
