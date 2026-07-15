package cn.net.rms.xaeromapsync_r.network;

import net.minecraft.network.FriendlyByteBuf;

public final class TileUnavailablePayload {
	private static final int MAX_DIMENSION_LENGTH = 256;
	private static final int MAX_REASON_LENGTH = 256;
	private final String dimension;
	private final int chunkX;
	private final int chunkZ;
	private final String reason;

	public TileUnavailablePayload(String dimension, int chunkX, int chunkZ, String reason) {
		if (dimension == null || dimension.isBlank()) {
			throw new IllegalArgumentException("Tile dimension is required");
		}
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("Tile unavailable reason is required");
		}
		this.dimension = dimension;
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		this.reason = reason;
	}

	public static TileUnavailablePayload read(FriendlyByteBuf buffer) {
		return new TileUnavailablePayload(
				buffer.readUtf(MAX_DIMENSION_LENGTH), buffer.readInt(), buffer.readInt(), buffer.readUtf(MAX_REASON_LENGTH));
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeUtf(dimension, MAX_DIMENSION_LENGTH);
		buffer.writeInt(chunkX);
		buffer.writeInt(chunkZ);
		buffer.writeUtf(reason, MAX_REASON_LENGTH);
	}

	public String dimension() { return dimension; }
	public int chunkX() { return chunkX; }
	public int chunkZ() { return chunkZ; }
	public String reason() { return reason; }
}
