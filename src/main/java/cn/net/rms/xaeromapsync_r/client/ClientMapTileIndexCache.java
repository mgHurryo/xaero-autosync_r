package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.map.MerkleTreeBuilder;
import cn.net.rms.xaeromapsync_r.map.MerkleNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.level.ChunkPos;

public final class ClientMapTileIndexCache {
	private final Map<String, MapTileIndexEntry> entries = new LinkedHashMap<>();
	private Map<String, Long> cachedDimensionRootHashes = Map.of();
	private long cachedComputedRootHash;
	private boolean computedRootHashesDirty = true;
	private long rootHash;

	public synchronized void replace(long rootHash, Collection<MapTileIndexEntry> snapshot) {
		this.rootHash = rootHash;
		entries.clear();
		for (MapTileIndexEntry entry : snapshot) {
			entries.put(key(entry.dimension(), entry.chunkX(), entry.chunkZ()), entry);
		}
		invalidateComputedRootHashes();
	}

	public synchronized void setRootHash(long rootHash) {
		this.rootHash = rootHash;
	}

	public synchronized void upsert(MapTileIndexEntry entry) {
		String key = key(entry.dimension(), entry.chunkX(), entry.chunkZ());
		MapTileIndexEntry current = entries.get(key);
		if (current == null || entry.revision() >= current.revision()) {
			entries.put(key, entry);
			if (current == null || current.contentHash() != entry.contentHash()) invalidateComputedRootHashes();
		}
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
		rebuildComputedRootHashesIfNeeded();
		return cachedComputedRootHash;
	}

	public synchronized long computedRootHash(String dimension) {
		rebuildComputedRootHashesIfNeeded();
		return cachedDimensionRootHashes.getOrDefault(dimension, 0L);
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

	private void rebuildComputedRootHashesIfNeeded() {
		if (!computedRootHashesDirty) return;
		List<MerkleNode> snapshot = MerkleTreeBuilder.build(entries.values());
		cachedComputedRootHash = MerkleTreeBuilder.rootHash(snapshot);
		Map<String, Long> dimensionRootHashes = new HashMap<>();
		for (MerkleNode root : MerkleTreeBuilder.roots(snapshot)) {
			dimensionRootHashes.put(root.dimension(), MerkleTreeBuilder.rootHash(List.of(root)));
		}
		cachedDimensionRootHashes = Map.copyOf(dimensionRootHashes);
		computedRootHashesDirty = false;
	}

	private void invalidateComputedRootHashes() {
		cachedDimensionRootHashes = Map.of();
		cachedComputedRootHash = 0L;
		computedRootHashesDirty = true;
	}
}
