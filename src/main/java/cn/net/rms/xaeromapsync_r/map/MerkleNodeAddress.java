package cn.net.rms.xaeromapsync_r.map;

import java.util.Objects;

public final class MerkleNodeAddress {
	private final String dimension;
	private final int level;
	private final int nodeX;
	private final int nodeZ;

	public MerkleNodeAddress(String dimension, int level, int nodeX, int nodeZ) {
		this.dimension = Objects.requireNonNull(dimension, "dimension");
		if (level < 0 || level > 30) {
			throw new IllegalArgumentException("Invalid Merkle level: " + level);
		}
		this.level = level;
		this.nodeX = nodeX;
		this.nodeZ = nodeZ;
	}

	public static MerkleNodeAddress of(MerkleNode node) {
		return new MerkleNodeAddress(node.dimension(), node.level(), node.nodeX(), node.nodeZ());
	}

	public String dimension() { return dimension; }
	public int level() { return level; }
	public int nodeX() { return nodeX; }
	public int nodeZ() { return nodeZ; }
	public String key() { return dimension + ":" + level + ":" + nodeX + ":" + nodeZ; }
}
