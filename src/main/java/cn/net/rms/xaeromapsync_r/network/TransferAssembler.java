package cn.net.rms.xaeromapsync_r.network;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;

public final class TransferAssembler {
	public static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

	public enum Status {
		EMPTY,
		RECEIVING,
		COMPLETE,
		CORRUPT,
		TIMED_OUT
	}

	public enum ReceiveResult {
		ACCEPTED,
		DUPLICATE,
		COMPLETE,
		CORRUPT
	}

	private final long timeoutMillis;
	private Status status = Status.EMPTY;
	private UUID transferId;
	private int partCount;
	private int totalLength;
	private long checksum;
	private byte[] assembledData;
	private BitSet receivedParts;
	private int receivedCount;
	private int highestContiguousPart = -1;
	private long lastActivityMillis;

	public TransferAssembler() {
		this(DEFAULT_TIMEOUT_MILLIS);
	}

	public TransferAssembler(long timeoutMillis) {
		if (timeoutMillis <= 0) {
			throw new IllegalArgumentException("Timeout must be positive");
		}
		this.timeoutMillis = timeoutMillis;
	}

	public ReceiveResult accept(TransferPartPayload part, long nowMillis) {
		if (part == null) {
			throw new IllegalArgumentException("Transfer part is required");
		}
		if (status == Status.CORRUPT || status == Status.TIMED_OUT) {
			throw new IllegalStateException("Transfer assembler is terminal: " + status);
		}
		if (status == Status.EMPTY) {
			initialize(part, nowMillis);
		} else {
			validateMetadata(part);
			validateClock(nowMillis);
		}

		int partIndex = part.partIndex();
		byte[] payload = part.payload();
		int offset = partIndex * TransferPartPayload.MAX_PART_BYTES;
		if (receivedParts.get(partIndex)) {
			if (!matchesExisting(payload, offset)) {
				markCorrupt();
				return ReceiveResult.CORRUPT;
			}
			return ReceiveResult.DUPLICATE;
		}

		System.arraycopy(payload, 0, assembledData, offset, payload.length);
		receivedParts.set(partIndex);
		receivedCount++;
		lastActivityMillis = nowMillis;
		while (highestContiguousPart + 1 < partCount && receivedParts.get(highestContiguousPart + 1)) {
			highestContiguousPart++;
		}

		if (receivedCount != partCount) {
			return ReceiveResult.ACCEPTED;
		}
		if (TransferFragmenter.crc32(assembledData) != checksum) {
			markCorrupt();
			return ReceiveResult.CORRUPT;
		}
		status = Status.COMPLETE;
		return ReceiveResult.COMPLETE;
	}

	public Status checkTimeout(long nowMillis) {
		if (status == Status.RECEIVING) {
			// The client tick can capture its timestamp just before the network thread records
			// a newer part. Skipping that stale timeout sample avoids a false clock rollback.
			if (nowMillis < lastActivityMillis) return status;
			if (nowMillis - lastActivityMillis >= timeoutMillis) {
				status = Status.TIMED_OUT;
				releaseBuffer();
			}
		}
		return status;
	}

	public TransferAckPayload acknowledgement() {
		if (status == Status.EMPTY) {
			throw new IllegalStateException("No transfer has been received");
		}
		return new TransferAckPayload(transferId, highestContiguousPart);
	}

	public List<Integer> missingPartIndexes() {
		if (status == Status.EMPTY) {
			return List.of();
		}
		List<Integer> missing = new ArrayList<>(partCount - receivedCount);
		for (int partIndex = 0; partIndex < partCount; partIndex++) {
			if (!receivedParts.get(partIndex)) {
				missing.add(partIndex);
			}
		}
		return List.copyOf(missing);
	}

	public byte[] assembledData() {
		if (status != Status.COMPLETE) {
			throw new IllegalStateException("Transfer is not complete: " + status);
		}
		return assembledData.clone();
	}

	public Status status() {
		return status;
	}

	public int receivedPartCount() {
		return receivedCount;
	}

	private void initialize(TransferPartPayload part, long nowMillis) {
		if (nowMillis < 0) {
			throw new IllegalArgumentException("Current time must not be negative");
		}
		transferId = part.transferId();
		partCount = part.partCount();
		totalLength = part.totalLength();
		checksum = part.checksum();
		assembledData = new byte[totalLength];
		receivedParts = new BitSet(partCount);
		lastActivityMillis = nowMillis;
		status = Status.RECEIVING;
	}

	private void validateMetadata(TransferPartPayload part) {
		if (!transferId.equals(part.transferId()) || partCount != part.partCount()
				|| totalLength != part.totalLength() || checksum != part.checksum()) {
			throw new IllegalArgumentException("Transfer part metadata does not match the active transfer");
		}
	}

	private void validateClock(long nowMillis) {
		if (nowMillis < lastActivityMillis) {
			throw new IllegalArgumentException("Current time moved backwards");
		}
	}

	private boolean matchesExisting(byte[] payload, int offset) {
		for (int index = 0; index < payload.length; index++) {
			if (assembledData[offset + index] != payload[index]) {
				return false;
			}
		}
		return true;
	}

	private void markCorrupt() {
		status = Status.CORRUPT;
		releaseBuffer();
	}

	private void releaseBuffer() {
		assembledData = null;
	}
}
