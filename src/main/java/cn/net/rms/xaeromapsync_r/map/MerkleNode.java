package cn.net.rms.xaeromapsync_r.map;

public final class MerkleNode {
	private final String dimension;
	private final int level;
	private final int nodeX;
	private final int nodeZ;
	private final long hash;
	private final int childCount;

	public MerkleNode(String dimension, int level, int nodeX, int nodeZ, long hash, int childCount) {
		this.dimension = dimension;
		this.level = level;
		this.nodeX = nodeX;
		this.nodeZ = nodeZ;
		this.hash = hash;
		this.childCount = childCount;
	}

	public String dimension() {
		return dimension;
	}

	public int level() {
		return level;
	}

	public int nodeX() {
		return nodeX;
	}

	public int nodeZ() {
		return nodeZ;
	}

	public long hash() {
		return hash;
	}

	public int childCount() {
		return childCount;
	}
}
