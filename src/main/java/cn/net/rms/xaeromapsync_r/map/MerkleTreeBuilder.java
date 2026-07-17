package cn.net.rms.xaeromapsync_r.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MerkleTreeBuilder {
	private static final int GROUP_SIZE = 2;
	private static final int MAX_LEVELS = 30;

	private MerkleTreeBuilder() {
	}

	public static List<MerkleNode> build(Collection<MapTileIndexEntry> entries) {
		List<MapTileIndexEntry> sortedEntries = new ArrayList<>(entries);
		sortedEntries.sort(Comparator.comparing(MapTileIndexEntry::dimension)
				.thenComparingInt(MapTileIndexEntry::chunkX)
				.thenComparingInt(MapTileIndexEntry::chunkZ));
		Map<NodeKey, MerkleNode> current = new LinkedHashMap<>();
		for (MapTileIndexEntry entry : sortedEntries) {
			NodeKey key = new NodeKey(entry.dimension(), 0, entry.chunkX(), entry.chunkZ());
			current.put(key, new MerkleNode(entry.dimension(), 0, entry.chunkX(), entry.chunkZ(), entry.contentHash(), 1));
		}
		List<MerkleNode> all = new ArrayList<>(current.values());
		for (int level = 1; level <= MAX_LEVELS && hasReducibleDimension(current); level++) {
			Map<NodeKey, List<MerkleNode>> grouped = new LinkedHashMap<>();
			for (MerkleNode node : current.values()) {
				int parentX = Math.floorDiv(node.nodeX(), GROUP_SIZE);
				int parentZ = Math.floorDiv(node.nodeZ(), GROUP_SIZE);
				NodeKey key = new NodeKey(node.dimension(), level, parentX, parentZ);
				grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node);
			}
			current = new LinkedHashMap<>();
			for (Map.Entry<NodeKey, List<MerkleNode>> group : grouped.entrySet()) {
				group.getValue().sort(Comparator.comparing(MerkleNode::dimension)
						.thenComparingInt(MerkleNode::level)
						.thenComparingInt(MerkleNode::nodeX)
						.thenComparingInt(MerkleNode::nodeZ));
				long hash = MapTileHasher.combine(MapTileHasher.hashString(group.getKey().dimension), level,
						group.getKey().nodeX, group.getKey().nodeZ);
				int childCount = 0;
				for (MerkleNode child : group.getValue()) {
					int slotX = Math.floorMod(child.nodeX(), GROUP_SIZE);
					int slotZ = Math.floorMod(child.nodeZ(), GROUP_SIZE);
					hash = MapTileHasher.combine(hash, (long) slotZ * GROUP_SIZE + slotX, child.hash());
					childCount += child.childCount();
				}
				NodeKey key = group.getKey();
				MerkleNode parent = new MerkleNode(key.dimension, key.level, key.nodeX, key.nodeZ, hash, childCount);
				current.put(key, parent);
			}
			all.addAll(current.values());
		}
		return all;
	}

	private static boolean hasReducibleDimension(Map<NodeKey, MerkleNode> nodes) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (MerkleNode node : nodes.values()) {
			int count = counts.merge(node.dimension(), 1, Integer::sum);
			if (count > 1) {
				return true;
			}
		}
		return false;
	}

	public static long rootHash(Collection<MerkleNode> nodes) {
		List<MerkleNode> roots = roots(nodes);
		long hash = 0L;
		for (MerkleNode root : roots) {
			hash = MapTileHasher.combine(hash, MapTileHasher.hashString(root.dimension()), root.level(),
					root.nodeX(), root.nodeZ(), root.hash());
		}
		return hash;
	}

	public static List<MerkleNode> roots(Collection<MerkleNode> nodes) {
		Map<String, Integer> highestLevel = new LinkedHashMap<>();
		for (MerkleNode node : nodes) {
			highestLevel.merge(node.dimension(), node.level(), Math::max);
		}
		List<MerkleNode> roots = new ArrayList<>();
		for (MerkleNode node : nodes) {
			if (node.level() == highestLevel.getOrDefault(node.dimension(), -1)) {
				roots.add(node);
			}
		}
		roots.sort(Comparator.comparing(MerkleNode::dimension)
				.thenComparingInt(MerkleNode::nodeX)
				.thenComparingInt(MerkleNode::nodeZ));
		return roots;
	}

	private static final class NodeKey {
		private final String dimension;
		private final int level;
		private final int nodeX;
		private final int nodeZ;

		private NodeKey(String dimension, int level, int nodeX, int nodeZ) {
			this.dimension = dimension;
			this.level = level;
			this.nodeX = nodeX;
			this.nodeZ = nodeZ;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof NodeKey)) {
				return false;
			}
			NodeKey that = (NodeKey) other;
			return level == that.level && nodeX == that.nodeX && nodeZ == that.nodeZ && dimension.equals(that.dimension);
		}

		@Override
		public int hashCode() {
			int result = dimension.hashCode();
			result = 31 * result + level;
			result = 31 * result + nodeX;
			result = 31 * result + nodeZ;
			return result;
		}
	}
}
