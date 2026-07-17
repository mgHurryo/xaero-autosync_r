package cn.net.rms.xaeromapsync_r.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Requests bounded retransmission of missing or corrupt transfer fragments. */
public record TransferNackPayload(UUID transferId, List<Integer> missingPartIndexes) {
	public TransferNackPayload {
		if (transferId == null || missingPartIndexes == null || missingPartIndexes.size() > TransferPartPayload.MAX_PART_COUNT) {
			throw new IllegalArgumentException("Invalid transfer NACK");
		}
		for (int index : missingPartIndexes) {
			if (index < 0 || index >= TransferPartPayload.MAX_PART_COUNT) throw new IllegalArgumentException("Invalid missing part index");
		}
		missingPartIndexes = List.copyOf(missingPartIndexes);
	}

	public static TransferNackPayload read(FriendlyByteBuf buffer) {
		UUID transferId = new UUID(buffer.readLong(), buffer.readLong());
		int count = buffer.readVarInt();
		if (count < 0 || count > TransferPartPayload.MAX_PART_COUNT) throw new IllegalArgumentException("Invalid NACK part count");
		List<Integer> missing = new ArrayList<>(count);
		for (int index = 0; index < count; index++) missing.add(buffer.readVarInt());
		return new TransferNackPayload(transferId, missing);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeLong(transferId.getMostSignificantBits());
		buffer.writeLong(transferId.getLeastSignificantBits());
		buffer.writeVarInt(missingPartIndexes.size());
		for (int index : missingPartIndexes) buffer.writeVarInt(index);
	}
}
