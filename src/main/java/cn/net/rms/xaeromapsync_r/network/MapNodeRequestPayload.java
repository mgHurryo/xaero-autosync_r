package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.map.MerkleNodeAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

public final class MapNodeRequestPayload {
	private static final int MAX_REQUESTS = 64;
	private static final int MAX_DIMENSION_LENGTH = 256;
	private final List<MerkleNodeAddress> nodes;

	public MapNodeRequestPayload(Collection<MerkleNodeAddress> nodes) {
		if (nodes.isEmpty() || nodes.size() > MAX_REQUESTS) {
			throw new IllegalArgumentException("Invalid Merkle node request count: " + nodes.size());
		}
		this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
	}

	public static MapNodeRequestPayload read(FriendlyByteBuf buffer) {
		int count = buffer.readVarInt();
		if (count <= 0 || count > MAX_REQUESTS) {
			throw new IllegalArgumentException("Invalid Merkle node request count: " + count);
		}
		List<MerkleNodeAddress> nodes = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			nodes.add(new MerkleNodeAddress(buffer.readUtf(MAX_DIMENSION_LENGTH), buffer.readVarInt(),
					buffer.readInt(), buffer.readInt()));
		}
		return new MapNodeRequestPayload(nodes);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeVarInt(nodes.size());
		for (MerkleNodeAddress node : nodes) {
			buffer.writeUtf(node.dimension(), MAX_DIMENSION_LENGTH);
			buffer.writeVarInt(node.level());
			buffer.writeInt(node.nodeX());
			buffer.writeInt(node.nodeZ());
		}
	}

	public List<MerkleNodeAddress> nodes() { return nodes; }
}
