package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.map.MerkleNodeAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

public final class MapNodeRequestPayload {
	public static final int MAX_REQUESTS = 64;
	private static final int MAX_DIMENSION_LENGTH = 256;
	private final long syncId;
	private final long requestId;
	private final List<MerkleNodeAddress> nodes;

	public MapNodeRequestPayload(Collection<MerkleNodeAddress> nodes) {
		this(0L, 0L, nodes);
	}

	public MapNodeRequestPayload(long syncId, long requestId, Collection<MerkleNodeAddress> nodes) {
		if (nodes.isEmpty() || nodes.size() > MAX_REQUESTS) {
			throw new IllegalArgumentException("Invalid Merkle node request count: " + nodes.size());
		}
		this.syncId = syncId;
		this.requestId = requestId;
		this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
	}

	public static MapNodeRequestPayload read(FriendlyByteBuf buffer) {
		long syncId = buffer.readLong();
		long requestId = buffer.readLong();
		int count = buffer.readVarInt();
		if (count <= 0 || count > MAX_REQUESTS) {
			throw new IllegalArgumentException("Invalid Merkle node request count: " + count);
		}
		List<MerkleNodeAddress> nodes = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			nodes.add(new MerkleNodeAddress(buffer.readUtf(MAX_DIMENSION_LENGTH), buffer.readVarInt(),
					buffer.readInt(), buffer.readInt()));
		}
		return new MapNodeRequestPayload(syncId, requestId, nodes);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeLong(syncId);
		buffer.writeLong(requestId);
		buffer.writeVarInt(nodes.size());
		for (MerkleNodeAddress node : nodes) {
			buffer.writeUtf(node.dimension(), MAX_DIMENSION_LENGTH);
			buffer.writeVarInt(node.level());
			buffer.writeInt(node.nodeX());
			buffer.writeInt(node.nodeZ());
		}
	}

	public long syncId() { return syncId; }
	public long requestId() { return requestId; }
	public List<MerkleNodeAddress> nodes() { return nodes; }
}
