package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.map.MerkleNode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ClientMerkleCache {
	private final Map<String, MerkleNode> nodes = new LinkedHashMap<>();

	public synchronized void replace(Collection<MerkleNode> snapshot) {
		nodes.clear();
		for (MerkleNode node : snapshot) {
			nodes.put(key(node), node);
		}
	}

	public synchronized int totalCount() {
		return nodes.size();
	}

	public synchronized Optional<MerkleNode> find(MerkleNode node) {
		return Optional.ofNullable(nodes.get(key(node)));
	}

	public synchronized void put(MerkleNode node) {
		nodes.put(key(node), node);
	}

	private static String key(MerkleNode node) {
		return node.dimension() + ":" + node.level() + ":" + node.nodeX() + ":" + node.nodeZ();
	}
}
