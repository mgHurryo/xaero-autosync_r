package cn.net.rms.xaeromapsync_r.network;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

public final class TransferPartPayload {
	private static final int MAX_PART_BYTES = 16384;
	private final UUID transferId;
	private final int partIndex;
	private final int partCount;
	private final byte[] payload;

	public TransferPartPayload(UUID transferId, int partIndex, int partCount, byte[] payload) {
		if (transferId == null) {
			throw new IllegalArgumentException("Transfer id is required");
		}
		if (partIndex < 0 || partIndex >= partCount) {
			throw new IllegalArgumentException("Invalid transfer part index");
		}
		if (payload.length > MAX_PART_BYTES) {
			throw new IllegalArgumentException("Transfer part is too large: " + payload.length);
		}
		this.transferId = transferId;
		this.partIndex = partIndex;
		this.partCount = partCount;
		this.payload = payload.clone();
	}

	public static TransferPartPayload read(FriendlyByteBuf buffer) {
		UUID transferId = new UUID(buffer.readLong(), buffer.readLong());
		int partIndex = buffer.readVarInt();
		int partCount = buffer.readVarInt();
		int length = buffer.readVarInt();
		if (length < 0 || length > MAX_PART_BYTES) {
			throw new IllegalArgumentException("Invalid transfer part length: " + length);
		}
		byte[] payload = new byte[length];
		buffer.readBytes(payload);
		return new TransferPartPayload(transferId, partIndex, partCount, payload);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeLong(transferId.getMostSignificantBits());
		buffer.writeLong(transferId.getLeastSignificantBits());
		buffer.writeVarInt(partIndex);
		buffer.writeVarInt(partCount);
		buffer.writeVarInt(payload.length);
		buffer.writeBytes(payload);
	}
}
