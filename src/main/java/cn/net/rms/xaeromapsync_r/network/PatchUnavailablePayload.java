package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import net.minecraft.network.FriendlyByteBuf;

public record PatchUnavailablePayload(MapPatchKey key, String reason) {
	private static final int MAX_DIMENSION_LENGTH = 256;
	private static final int MAX_REASON_LENGTH = 512;

	public PatchUnavailablePayload {
		if (key == null || reason == null) throw new IllegalArgumentException("Patch key and reason are required");
	}

	public static PatchUnavailablePayload read(FriendlyByteBuf buffer) {
		return new PatchUnavailablePayload(MapPatchKey.square(buffer.readUtf(MAX_DIMENSION_LENGTH), buffer.readInt(), buffer.readInt(), buffer.readVarInt()),
				buffer.readUtf(MAX_REASON_LENGTH));
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeUtf(key.dimension(), MAX_DIMENSION_LENGTH);
		buffer.writeInt(key.minChunkX());
		buffer.writeInt(key.minChunkZ());
		buffer.writeVarInt(key.sideLength());
		buffer.writeUtf(reason, MAX_REASON_LENGTH);
	}
}
