package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.map.MapTileIndexEntry;
import cn.net.rms.xaeromapsync_r.map.MerkleNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

public final class MapNodeResponsePayload {
	private static final int MAX_NODES = 256;
	private static final int MAX_ENTRIES = 256;
	private static final int MAX_DIMENSION_LENGTH = 256;
	private final long syncId;
	private final long requestId;
	private final long rootHash;
	private final String dimension;
	private final boolean nodeRequestResponse;
	private final List<MerkleNode> nodes;
	private final List<MapTileIndexEntry> entries;

	public MapNodeResponsePayload(String dimension, long rootHash, boolean nodeRequestResponse,
			Collection<MerkleNode> nodes, Collection<MapTileIndexEntry> entries) {
		this(dimension, rootHash, 0L, 0L, nodeRequestResponse, nodes, entries);
	}

	public MapNodeResponsePayload(String dimension, long rootHash, long syncId, long requestId,
			boolean nodeRequestResponse, Collection<MerkleNode> nodes, Collection<MapTileIndexEntry> entries) {
		if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Merkle response dimension is required");
		if (nodes.size() > MAX_NODES || entries.size() > MAX_ENTRIES) {
			throw new IllegalArgumentException("Merkle response exceeds bounded size");
		}
		this.dimension = dimension;
		this.rootHash = rootHash;
		this.syncId = syncId;
		this.requestId = requestId;
		this.nodeRequestResponse = nodeRequestResponse;
		this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
		this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
	}

	public static MapNodeResponsePayload read(FriendlyByteBuf buffer) {
		long syncId = buffer.readLong();
		long requestId = buffer.readLong();
		String dimension = buffer.readUtf(MAX_DIMENSION_LENGTH);
		long rootHash = buffer.readLong();
		boolean nodeRequestResponse = buffer.readBoolean();
		int nodeCount = buffer.readVarInt();
		if (nodeCount < 0 || nodeCount > MAX_NODES) throw new IllegalArgumentException("Invalid Merkle node count: " + nodeCount);
		List<MerkleNode> nodes = new ArrayList<>(nodeCount);
		for (int index = 0; index < nodeCount; index++) {
			nodes.add(new MerkleNode(buffer.readUtf(MAX_DIMENSION_LENGTH), buffer.readVarInt(), buffer.readInt(),
					buffer.readInt(), buffer.readLong(), buffer.readVarInt()));
		}
		int entryCount = buffer.readVarInt();
		if (entryCount < 0 || entryCount > MAX_ENTRIES) throw new IllegalArgumentException("Invalid Merkle leaf count: " + entryCount);
		List<MapTileIndexEntry> entries = new ArrayList<>(entryCount);
		for (int index = 0; index < entryCount; index++) {
			entries.add(new MapTileIndexEntry(buffer.readUtf(MAX_DIMENSION_LENGTH), buffer.readInt(), buffer.readInt(),
					buffer.readLong(), buffer.readVarLong(), buffer.readLong()));
		}
		return new MapNodeResponsePayload(dimension, rootHash, syncId, requestId, nodeRequestResponse, nodes, entries);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeLong(syncId);
		buffer.writeLong(requestId);
		buffer.writeUtf(dimension, MAX_DIMENSION_LENGTH);
		buffer.writeLong(rootHash);
		buffer.writeBoolean(nodeRequestResponse);
		buffer.writeVarInt(nodes.size());
		for (MerkleNode node : nodes) {
			buffer.writeUtf(node.dimension(), MAX_DIMENSION_LENGTH);
			buffer.writeVarInt(node.level());
			buffer.writeInt(node.nodeX());
			buffer.writeInt(node.nodeZ());
			buffer.writeLong(node.hash());
			buffer.writeVarInt(node.childCount());
		}
		buffer.writeVarInt(entries.size());
		for (MapTileIndexEntry entry : entries) {
			buffer.writeUtf(entry.dimension(), MAX_DIMENSION_LENGTH);
			buffer.writeInt(entry.chunkX());
			buffer.writeInt(entry.chunkZ());
			buffer.writeLong(entry.contentHash());
			buffer.writeVarLong(entry.revision());
			buffer.writeLong(entry.updatedAtMillis());
		}
	}

	public long syncId() { return syncId; }
	public long requestId() { return requestId; }
	public long rootHash() { return rootHash; }
	public String dimension() { return dimension; }
	public boolean nodeRequestResponse() { return nodeRequestResponse; }
	public List<MerkleNode> nodes() { return nodes; }
	public List<MapTileIndexEntry> entries() { return entries; }
}
