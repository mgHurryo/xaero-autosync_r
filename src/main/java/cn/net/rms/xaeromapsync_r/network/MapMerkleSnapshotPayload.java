package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.map.MerkleNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

public final class MapMerkleSnapshotPayload {
	private static final int MAX_NODES = 16384;
	private static final int MAX_DIMENSION_LENGTH = 256;
	private final List<MerkleNode> nodes;

	public MapMerkleSnapshotPayload(Collection<MerkleNode> nodes) {
		if (nodes.size() > MAX_NODES) {
			throw new IllegalArgumentException("Merkle snapshot contains too many nodes: " + nodes.size());
		}
		this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
	}

	public static MapMerkleSnapshotPayload read(FriendlyByteBuf buffer) {
		int count = buffer.readVarInt();
		if (count < 0 || count > MAX_NODES) {
			throw new IllegalArgumentException("Invalid merkle node count: " + count);
		}
		List<MerkleNode> nodes = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			nodes.add(new MerkleNode(
					buffer.readUtf(MAX_DIMENSION_LENGTH),
					buffer.readVarInt(),
					buffer.readInt(),
					buffer.readInt(),
					buffer.readLong(),
					buffer.readVarInt()));
		}
		return new MapMerkleSnapshotPayload(nodes);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeVarInt(nodes.size());
		for (MerkleNode node : nodes) {
			buffer.writeUtf(node.dimension(), MAX_DIMENSION_LENGTH);
			buffer.writeVarInt(node.level());
			buffer.writeInt(node.nodeX());
			buffer.writeInt(node.nodeZ());
			buffer.writeLong(node.hash());
			buffer.writeVarInt(node.childCount());
		}
	}

	public List<MerkleNode> nodes() {
		return nodes;
	}
}
