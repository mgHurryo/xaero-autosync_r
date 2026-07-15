package cn.net.rms.xaeromapsync_r.network;

import net.minecraft.network.FriendlyByteBuf;

public final class MapRootHashPayload {
	private final long knownRootHash;
	private final String dimension;

	public MapRootHashPayload(String dimension, long knownRootHash) {
		if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Map root dimension is required");
		this.dimension = dimension;
		this.knownRootHash = knownRootHash;
	}

	public static MapRootHashPayload read(FriendlyByteBuf buffer) {
		return new MapRootHashPayload(buffer.readUtf(256), buffer.readLong());
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeUtf(dimension, 256);
		buffer.writeLong(knownRootHash);
	}

	public long knownRootHash() {
		return knownRootHash;
	}

	public String dimension() { return dimension; }
}
