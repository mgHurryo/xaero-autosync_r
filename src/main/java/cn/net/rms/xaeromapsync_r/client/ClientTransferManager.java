package cn.net.rms.xaeromapsync_r.client;

import cn.net.rms.xaeromapsync_r.network.TransferAckPayload;
import cn.net.rms.xaeromapsync_r.network.TransferAssembler;
import cn.net.rms.xaeromapsync_r.network.TransferPartPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;

public final class ClientTransferManager {
	private static final int MAX_ACTIVE_TRANSFERS = 24;
	private final Map<UUID, TransferAssembler> assemblers = new LinkedHashMap<>();
	private final Consumer<TransferAckPayload> acknowledgementSender;
	private final Consumer<cn.net.rms.xaeromapsync_r.network.TransferNackPayload> negativeAcknowledgementSender;
	private final CompletionHandler completionHandler;
	private final java.util.Set<UUID> decoding = new java.util.HashSet<>();

	@FunctionalInterface
	public interface CompletionHandler {
		void accept(byte[] data, Consumer<Boolean> completion);
	}

	public ClientTransferManager(Consumer<TransferAckPayload> acknowledgementSender, Consumer<byte[]> completionHandler) {
		this(acknowledgementSender, ignored -> { }, (data, completion) -> {
			completionHandler.accept(data);
			completion.accept(true);
		});
	}

	public ClientTransferManager(Consumer<TransferAckPayload> acknowledgementSender,
			Consumer<cn.net.rms.xaeromapsync_r.network.TransferNackPayload> negativeAcknowledgementSender,
			CompletionHandler completionHandler) {
		this.acknowledgementSender = acknowledgementSender;
		this.negativeAcknowledgementSender = negativeAcknowledgementSender;
		this.completionHandler = completionHandler;
	}

	public synchronized void accept(TransferPartPayload part) {
		TransferAssembler assembler = assemblers.get(part.transferId());
		if (assembler == null) {
			if (assemblers.size() >= MAX_ACTIVE_TRANSFERS) {
				UUID oldest = assemblers.keySet().iterator().next();
				assemblers.remove(oldest);
				// An empty NACK explicitly cancels a transfer. Without it the server
				// keeps retrying an assembler the client no longer owns.
				negativeAcknowledgementSender.accept(
						new cn.net.rms.xaeromapsync_r.network.TransferNackPayload(oldest, List.of()));
			}
			assembler = new TransferAssembler();
			assemblers.put(part.transferId(), assembler);
		}
		TransferAssembler.ReceiveResult result;
		try {
			result = assembler.accept(part, System.currentTimeMillis());
		} catch (RuntimeException exception) {
			assemblers.remove(part.transferId());
			XaeroMapsync_r.LOGGER.warn("Rejected invalid fragmented transfer {}", part.transferId(), exception);
			return;
		}
		if (result == TransferAssembler.ReceiveResult.COMPLETE) {
			byte[] data = assembler.assembledData();
			if (!decoding.add(part.transferId())) return;
			try {
				completionHandler.accept(data, successful -> finishCompleted(part.transferId(), successful));
			} catch (RuntimeException exception) {
				XaeroMapsync_r.LOGGER.warn("Rejected invalid completed transfer {}", part.transferId(), exception);
				finishCompleted(part.transferId(), false);
			}
		} else if (result == TransferAssembler.ReceiveResult.CORRUPT) {
			negativeAcknowledgementSender.accept(new cn.net.rms.xaeromapsync_r.network.TransferNackPayload(
					part.transferId(), List.of()));
			assemblers.remove(part.transferId());
		} else {
			acknowledgementSender.accept(assembler.acknowledgement());
		}
	}

	public synchronized int activeCount() { return assemblers.size(); }

	public synchronized void clear() {
		assemblers.clear();
		decoding.clear();
	}

	public synchronized void tick(long nowMillis) {
		assemblers.entrySet().removeIf(entry -> {
			if (decoding.contains(entry.getKey())) return false;
			TransferAssembler assembler = entry.getValue();
			if (assembler.checkTimeout(nowMillis) != TransferAssembler.Status.TIMED_OUT) return false;
			negativeAcknowledgementSender.accept(new cn.net.rms.xaeromapsync_r.network.TransferNackPayload(
					entry.getKey(), assembler.missingPartIndexes()));
			return true;
		});
	}

	private synchronized void finishCompleted(UUID transferId, boolean successful) {
		if (!decoding.remove(transferId)) return;
		TransferAssembler assembler = assemblers.remove(transferId);
		if (assembler == null) return;
		TransferAckPayload acknowledgement = assembler.acknowledgement();
		if (successful) {
			acknowledgementSender.accept(acknowledgement);
			return;
		}
		List<Integer> allParts = java.util.stream.IntStream.rangeClosed(0,
				acknowledgement.highestContiguousPart()).boxed().toList();
		negativeAcknowledgementSender.accept(
				new cn.net.rms.xaeromapsync_r.network.TransferNackPayload(transferId, allParts));
	}
}
