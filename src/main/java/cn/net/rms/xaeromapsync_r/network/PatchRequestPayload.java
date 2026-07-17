package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import net.minecraft.network.FriendlyByteBuf;

public record PatchRequestPayload(MapPatchKey key, long epoch, long manifestHash) {
	private static final int MAX_DIMENSION_LENGTH = 256;

	public PatchRequestPayload {
		if (key == null) throw new IllegalArgumentException("Patch key is required");
	}

	public static PatchRequestPayload read(FriendlyByteBuf buffer) {
		return new PatchRequestPayload(new MapPatchKey(buffer.readUtf(MAX_DIMENSION_LENGTH), buffer.readInt(), buffer.readInt()),
				buffer.readLong(), buffer.readLong());
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeUtf(key.dimension(), MAX_DIMENSION_LENGTH);
		buffer.writeInt(key.patchX());
		buffer.writeInt(key.patchZ());
		buffer.writeLong(epoch);
		buffer.writeLong(manifestHash);
	}
}
