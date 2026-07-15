package cn.net.rms.xaeromapsync_r.network;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

public final class TransferAckPayload {
	private final UUID transferId;
	private final int highestContiguousPart;

	public TransferAckPayload(UUID transferId, int highestContiguousPart) {
		if (transferId == null) {
			throw new IllegalArgumentException("Transfer id is required");
		}
		this.transferId = transferId;
		this.highestContiguousPart = highestContiguousPart;
	}

	public static TransferAckPayload read(FriendlyByteBuf buffer) {
		return new TransferAckPayload(new UUID(buffer.readLong(), buffer.readLong()), buffer.readVarInt());
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeLong(transferId.getMostSignificantBits());
		buffer.writeLong(transferId.getLeastSignificantBits());
		buffer.writeVarInt(highestContiguousPart);
	}
}
