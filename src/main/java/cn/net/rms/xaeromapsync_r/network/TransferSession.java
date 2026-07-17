package cn.net.rms.xaeromapsync_r.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TransferSession {
	public static final int MAX_RETRIES = 32;

	public enum Status {
		READY,
		ACTIVE,
		TIMED_OUT,
		COMPLETED,
		RETRIES_EXHAUSTED
	}

	private final UUID transferId;
	private final List<TransferPartPayload> parts;
	private final long timeoutMillis;
	private final int maxRetries;
	private Status status = Status.READY;
	private int highestAcknowledgedPart = -1;
	private int retryCount;
	private long lastProgressMillis;

	public TransferSession(List<TransferPartPayload> parts, long timeoutMillis, int maxRetries) {
		if (parts == null || parts.isEmpty()) {
			throw new IllegalArgumentException("Transfer parts are required");
		}
		if (timeoutMillis <= 0) {
			throw new IllegalArgumentException("Timeout must be positive");
		}
		if (maxRetries < 0 || maxRetries > MAX_RETRIES) {
			throw new IllegalArgumentException("Invalid maximum retries: " + maxRetries);
		}
		this.parts = validateAndOrder(parts);
		this.transferId = this.parts.get(0).transferId();
		this.timeoutMillis = timeoutMillis;
		this.maxRetries = maxRetries;
	}

	public List<TransferPartPayload> start(long nowMillis) {
		if (status != Status.READY) {
			throw new IllegalStateException("Transfer session has already started");
		}
		if (nowMillis < 0) {
			throw new IllegalArgumentException("Current time must not be negative");
		}
		status = Status.ACTIVE;
		lastProgressMillis = nowMillis;
		return parts;
	}

	public Status acknowledge(TransferAckPayload acknowledgement, long nowMillis) {
		if (acknowledgement == null) {
			throw new IllegalArgumentException("Transfer acknowledgement is required");
		}
		if (status != Status.ACTIVE && status != Status.TIMED_OUT) {
			throw new IllegalStateException("Transfer session cannot accept acknowledgements: " + status);
		}
		validateClock(nowMillis);
		if (!transferId.equals(acknowledgement.transferId())) {
			throw new IllegalArgumentException("Acknowledgement transfer id does not match");
		}
		int acknowledgedPart = acknowledgement.highestContiguousPart();
		if (acknowledgedPart >= parts.size()) {
			throw new IllegalArgumentException("Acknowledgement exceeds transfer part count");
		}
		if (acknowledgedPart > highestAcknowledgedPart) {
			highestAcknowledgedPart = acknowledgedPart;
			lastProgressMillis = nowMillis;
			status = acknowledgedPart == parts.size() - 1 ? Status.COMPLETED : Status.ACTIVE;
		}
		return status;
	}

	public Status checkTimeout(long nowMillis) {
		if (status == Status.ACTIVE) {
			validateClock(nowMillis);
			if (nowMillis - lastProgressMillis >= timeoutMillis) {
				status = retryCount >= maxRetries ? Status.RETRIES_EXHAUSTED : Status.TIMED_OUT;
			}
		}
		return status;
	}

	public List<TransferPartPayload> retry(long nowMillis) {
		if (status != Status.TIMED_OUT) {
			throw new IllegalStateException("Transfer session is not waiting for a retry: " + status);
		}
		validateClock(nowMillis);
		retryCount++;
		lastProgressMillis = nowMillis;
		status = Status.ACTIVE;
		return unacknowledgedParts();
	}

	/** Sending a new part is transfer progress even before its acknowledgement arrives. */
	public void markPartSent(long nowMillis) {
		if (status != Status.ACTIVE) {
			throw new IllegalStateException("Transfer session is not active: " + status);
		}
		validateClock(nowMillis);
		lastProgressMillis = nowMillis;
	}

	public List<TransferPartPayload> unacknowledgedParts() {
		if (status == Status.COMPLETED) {
			return List.of();
		}
		return List.copyOf(parts.subList(highestAcknowledgedPart + 1, parts.size()));
	}

	public Status status() {
		return status;
	}

	public int retryCount() {
		return retryCount;
	}

	public int highestAcknowledgedPart() {
		return highestAcknowledgedPart;
	}

	private static List<TransferPartPayload> validateAndOrder(List<TransferPartPayload> inputParts) {
		TransferPartPayload first = inputParts.get(0);
		if (first == null) {
			throw new IllegalArgumentException("Transfer part is required");
		}
		int partCount = first.partCount();
		if (inputParts.size() != partCount) {
			throw new IllegalArgumentException("Transfer session requires every part exactly once");
		}
		TransferPartPayload[] ordered = new TransferPartPayload[partCount];
		for (TransferPartPayload part : inputParts) {
			if (part == null) {
				throw new IllegalArgumentException("Transfer part is required");
			}
			if (!first.transferId().equals(part.transferId()) || first.partCount() != part.partCount()
					|| first.totalLength() != part.totalLength() || first.checksum() != part.checksum()) {
				throw new IllegalArgumentException("Transfer session parts have inconsistent metadata");
			}
			if (ordered[part.partIndex()] != null) {
				throw new IllegalArgumentException("Duplicate transfer part index: " + part.partIndex());
			}
			ordered[part.partIndex()] = part;
		}
		List<TransferPartPayload> result = new ArrayList<>(partCount);
		for (TransferPartPayload part : ordered) {
			result.add(part);
		}
		return List.copyOf(result);
	}

	private void validateClock(long nowMillis) {
		if (nowMillis < lastProgressMillis) {
			throw new IllegalArgumentException("Current time moved backwards");
		}
	}
}
