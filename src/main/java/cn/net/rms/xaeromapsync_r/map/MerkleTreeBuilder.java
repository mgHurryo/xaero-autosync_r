package cn.net.rms.xaeromapsync_r.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MerkleTreeBuilder {
	private static final int GROUP_SIZE = 4;
	private static final int MAX_LEVELS = 6;

	private MerkleTreeBuilder() {
	}

	public static List<MerkleNode> build(Collection<MapTileIndexEntry> entries) {
		Map<NodeKey, MerkleNode> current = new LinkedHashMap<>();
		for (MapTileIndexEntry entry : entries) {
			NodeKey key = new NodeKey(entry.dimension(), 0, entry.chunkX(), entry.chunkZ());
			current.put(key, new MerkleNode(entry.dimension(), 0, entry.chunkX(), entry.chunkZ(), MapTileHasher.combine(entry.contentHash(), entry.revision()), 1));
		}
		List<MerkleNode> all = new ArrayList<>(current.values());
		for (int level = 1; level <= MAX_LEVELS && current.size() > 1; level++) {
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
				long hash = 0L;
				int childCount = 0;
				for (MerkleNode child : group.getValue()) {
					hash = MapTileHasher.combine(hash, child.hash());
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
