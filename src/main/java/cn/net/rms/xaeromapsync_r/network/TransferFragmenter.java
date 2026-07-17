package cn.net.rms.xaeromapsync_r.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

public final class TransferFragmenter {
	private TransferFragmenter() {
	}

	public static List<TransferPartPayload> fragment(UUID transferId, byte[] data) {
		if (transferId == null) {
			throw new IllegalArgumentException("Transfer id is required");
		}
		if (data == null) {
			throw new IllegalArgumentException("Transfer data is required");
		}
		if (data.length > TransferPartPayload.MAX_TRANSFER_BYTES) {
			throw new IllegalArgumentException("Transfer is too large: " + data.length);
		}

		int partCount = TransferPartPayload.partCountForLength(data.length);
		long checksum = crc32(data);
		List<TransferPartPayload> parts = new ArrayList<>(partCount);
		for (int partIndex = 0; partIndex < partCount; partIndex++) {
			int offset = partIndex * TransferPartPayload.MAX_PART_BYTES;
			int end = Math.min(data.length, offset + TransferPartPayload.MAX_PART_BYTES);
			parts.add(TransferPartPayload.fromOwnedPayload(transferId, partIndex, partCount, data.length, checksum,
					Arrays.copyOfRange(data, offset, end)));
		}
		return List.copyOf(parts);
	}

	static long crc32(byte[] data) {
		CRC32 crc32 = new CRC32();
		crc32.update(data);
		return crc32.getValue();
	}
}
