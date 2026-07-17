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
	private final Consumer<byte[]> completionHandler;

	public ClientTransferManager(Consumer<TransferAckPayload> acknowledgementSender, Consumer<byte[]> completionHandler) {
		this(acknowledgementSender, ignored -> { }, completionHandler);
	}

	public ClientTransferManager(Consumer<TransferAckPayload> acknowledgementSender,
			Consumer<cn.net.rms.xaeromapsync_r.network.TransferNackPayload> negativeAcknowledgementSender,
			Consumer<byte[]> completionHandler) {
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
			acknowledgementSender.accept(assembler.acknowledgement());
			byte[] data = assembler.assembledData();
			assemblers.remove(part.transferId());
			try {
				completionHandler.accept(data);
			} catch (RuntimeException exception) {
				XaeroMapsync_r.LOGGER.warn("Rejected invalid completed transfer {}", part.transferId(), exception);
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

	public synchronized void clear() { assemblers.clear(); }

	public synchronized void tick(long nowMillis) {
		assemblers.entrySet().removeIf(entry -> {
			TransferAssembler assembler = entry.getValue();
			if (assembler.checkTimeout(nowMillis) != TransferAssembler.Status.TIMED_OUT) return false;
			negativeAcknowledgementSender.accept(new cn.net.rms.xaeromapsync_r.network.TransferNackPayload(
					entry.getKey(), assembler.missingPartIndexes()));
			return true;
		});
	}
}
