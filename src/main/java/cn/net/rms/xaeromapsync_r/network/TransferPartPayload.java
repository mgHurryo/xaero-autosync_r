package cn.net.rms.xaeromapsync_r.network;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

public final class TransferPartPayload {
	public static final int MAX_PART_BYTES = 16 * 1024;
	public static final int MAX_PART_COUNT = 512;
	public static final int MAX_TRANSFER_BYTES = MAX_PART_BYTES * MAX_PART_COUNT;

	private final UUID transferId;
	private final int partIndex;
	private final int partCount;
	private final int totalLength;
	private final long checksum;
	private final byte[] payload;

	public TransferPartPayload(UUID transferId, int partIndex, int partCount, int totalLength, long checksum,
			byte[] payload) {
		this(transferId, partIndex, partCount, totalLength, checksum, payload, true);
	}

	private TransferPartPayload(UUID transferId, int partIndex, int partCount, int totalLength, long checksum,
			byte[] payload, boolean copyPayload) {
		if (transferId == null) {
			throw new IllegalArgumentException("Transfer id is required");
		}
		if (payload == null) {
			throw new IllegalArgumentException("Transfer part payload is required");
		}
		if (totalLength < 0 || totalLength > MAX_TRANSFER_BYTES) {
			throw new IllegalArgumentException("Invalid transfer length: " + totalLength);
		}
		int expectedPartCount = partCountForLength(totalLength);
		if (partCount != expectedPartCount) {
			throw new IllegalArgumentException(
					"Invalid transfer part count: " + partCount + ", expected " + expectedPartCount);
		}
		if (partIndex < 0 || partIndex >= partCount) {
			throw new IllegalArgumentException("Invalid transfer part index: " + partIndex);
		}
		int expectedPartLength = partLength(totalLength, partIndex, partCount);
		if (payload.length != expectedPartLength) {
			throw new IllegalArgumentException(
					"Invalid transfer part length: " + payload.length + ", expected " + expectedPartLength);
		}
		if (checksum < 0 || checksum > 0xffffffffL) {
			throw new IllegalArgumentException("Invalid CRC32 checksum: " + checksum);
		}
		this.transferId = transferId;
		this.partIndex = partIndex;
		this.partCount = partCount;
		this.totalLength = totalLength;
		this.checksum = checksum;
		this.payload = copyPayload ? payload.clone() : payload;
	}

	static TransferPartPayload fromOwnedPayload(UUID transferId, int partIndex, int partCount, int totalLength,
			long checksum, byte[] payload) {
		return new TransferPartPayload(transferId, partIndex, partCount, totalLength, checksum, payload, false);
	}

	public static TransferPartPayload read(FriendlyByteBuf buffer) {
		UUID transferId = new UUID(buffer.readLong(), buffer.readLong());
		int partIndex = buffer.readVarInt();
		int partCount = buffer.readVarInt();
		int totalLength = buffer.readVarInt();
		long checksum = buffer.readLong();
		int length = buffer.readVarInt();
		if (length < 0 || length > MAX_PART_BYTES) {
			throw new IllegalArgumentException("Invalid transfer part length: " + length);
		}
		byte[] payload = new byte[length];
		buffer.readBytes(payload);
		return fromOwnedPayload(transferId, partIndex, partCount, totalLength, checksum, payload);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeLong(transferId.getMostSignificantBits());
		buffer.writeLong(transferId.getLeastSignificantBits());
		buffer.writeVarInt(partIndex);
		buffer.writeVarInt(partCount);
		buffer.writeVarInt(totalLength);
		buffer.writeLong(checksum);
		buffer.writeVarInt(payload.length);
		buffer.writeBytes(payload);
	}

	public UUID transferId() {
		return transferId;
	}

	public int partIndex() {
		return partIndex;
	}

	public int partCount() {
		return partCount;
	}

	public int totalLength() {
		return totalLength;
	}

	public long checksum() {
		return checksum;
	}

	public byte[] payload() {
		return payload.clone();
	}

	public int payloadLength() {
		return payload.length;
	}

	void copyPayloadTo(byte[] target, int offset) {
		System.arraycopy(payload, 0, target, offset, payload.length);
	}

	boolean payloadMatches(byte[] target, int offset) {
		for (int index = 0; index < payload.length; index++) {
			if (target[offset + index] != payload[index]) return false;
		}
		return true;
	}

	static int partCountForLength(int totalLength) {
		return Math.max(1, (totalLength + MAX_PART_BYTES - 1) / MAX_PART_BYTES);
	}

	private static int partLength(int totalLength, int partIndex, int partCount) {
		if (partIndex < partCount - 1) {
			return MAX_PART_BYTES;
		}
		return totalLength - partIndex * MAX_PART_BYTES;
	}
}
