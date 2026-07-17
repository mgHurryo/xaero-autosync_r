package cn.net.rms.xaeromapsync_r.network;

import net.minecraft.network.FriendlyByteBuf;

public final class LocalTileReadyPayload {
	private static final int MAX_DIMENSION_LENGTH = 256;
	private final String dimension;
	private final int chunkX;
	private final int chunkZ;
	private final long contentHash;

	public LocalTileReadyPayload(String dimension, int chunkX, int chunkZ, long contentHash) {
		if (dimension == null || dimension.isBlank()) {
			throw new IllegalArgumentException("Local tile dimension is required");
		}
		this.dimension = dimension;
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		this.contentHash = contentHash;
	}

	public static LocalTileReadyPayload read(FriendlyByteBuf buffer) {
		return new LocalTileReadyPayload(buffer.readUtf(MAX_DIMENSION_LENGTH), buffer.readInt(), buffer.readInt(),
				buffer.readLong());
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeUtf(dimension, MAX_DIMENSION_LENGTH);
		buffer.writeInt(chunkX);
		buffer.writeInt(chunkZ);
		buffer.writeLong(contentHash);
	}

	public String dimension() { return dimension; }
	public int chunkX() { return chunkX; }
	public int chunkZ() { return chunkZ; }
	public long contentHash() { return contentHash; }
}
