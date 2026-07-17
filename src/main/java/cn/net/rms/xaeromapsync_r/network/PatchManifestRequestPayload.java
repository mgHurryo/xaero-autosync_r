package cn.net.rms.xaeromapsync_r.network;

import net.minecraft.network.FriendlyByteBuf;

public record PatchManifestRequestPayload(String dimension, long syncId, long expectedEpoch, int cursor,
		int centerChunkX, int centerChunkZ, double motionX, double motionZ) {
	private static final int MAX_DIMENSION_LENGTH = 256;

	public PatchManifestRequestPayload {
		if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Dimension is required");
		if (syncId <= 0L || cursor < 0 || !Double.isFinite(motionX) || !Double.isFinite(motionZ)) {
			throw new IllegalArgumentException("Invalid patch manifest request");
		}
	}

	public static PatchManifestRequestPayload read(FriendlyByteBuf buffer) {
		return new PatchManifestRequestPayload(buffer.readUtf(MAX_DIMENSION_LENGTH), buffer.readVarLong(), buffer.readLong(),
				buffer.readVarInt(), buffer.readInt(), buffer.readInt(), buffer.readDouble(), buffer.readDouble());
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeUtf(dimension, MAX_DIMENSION_LENGTH);
		buffer.writeVarLong(syncId);
		buffer.writeLong(expectedEpoch);
		buffer.writeVarInt(cursor);
		buffer.writeInt(centerChunkX);
		buffer.writeInt(centerChunkZ);
		buffer.writeDouble(motionX);
		buffer.writeDouble(motionZ);
	}
}
