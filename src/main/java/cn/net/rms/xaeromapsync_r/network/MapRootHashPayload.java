package cn.net.rms.xaeromapsync_r.network;

import net.minecraft.network.FriendlyByteBuf;

public final class MapRootHashPayload {
	private final long syncId;
	private final long requestId;
	private final long knownRootHash;
	private final String dimension;

	public MapRootHashPayload(String dimension, long knownRootHash) {
		this(dimension, knownRootHash, 0L, 0L);
	}

	public MapRootHashPayload(String dimension, long knownRootHash, long syncId, long requestId) {
		if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Map root dimension is required");
		this.dimension = dimension;
		this.knownRootHash = knownRootHash;
		this.syncId = syncId;
		this.requestId = requestId;
	}

	public static MapRootHashPayload read(FriendlyByteBuf buffer) {
		long syncId = buffer.readLong();
		long requestId = buffer.readLong();
		return new MapRootHashPayload(buffer.readUtf(256), buffer.readLong(), syncId, requestId);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeLong(syncId);
		buffer.writeLong(requestId);
		buffer.writeUtf(dimension, 256);
		buffer.writeLong(knownRootHash);
	}

	public long syncId() { return syncId; }
	public long requestId() { return requestId; }
	public long knownRootHash() {
		return knownRootHash;
	}

	public String dimension() { return dimension; }
}
