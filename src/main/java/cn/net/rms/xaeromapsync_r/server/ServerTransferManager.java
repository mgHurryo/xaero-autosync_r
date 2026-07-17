package cn.net.rms.xaeromapsync_r.server;

import cn.net.rms.xaeromapsync_r.network.TransferAckPayload;
import cn.net.rms.xaeromapsync_r.network.TransferFragmenter;
import cn.net.rms.xaeromapsync_r.network.TransferPartPayload;
import cn.net.rms.xaeromapsync_r.network.TransferSession;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class ServerTransferManager {
	private static final int MAX_TRANSFERS_PER_PLAYER = 256;
	private static final long RETRY_TIMEOUT_MILLIS = 5_000L;
	private final NetworkBudgetTracker budget;
	private final BiConsumer<ServerPlayer, TransferPartPayload> sender;
	private final Map<UUID, LinkedHashMap<UUID, PendingTransfer>> transfers = new LinkedHashMap<>();

	public ServerTransferManager(NetworkBudgetTracker budget, BiConsumer<ServerPlayer, TransferPartPayload> sender) {
		this.budget = budget;
		this.sender = sender;
	}

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::tick);
	}

	public synchronized UUID start(ServerPlayer player, byte[] data) {
		LinkedHashMap<UUID, PendingTransfer> playerTransfers = transfers.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashMap<>());
		if (playerTransfers.size() >= MAX_TRANSFERS_PER_PLAYER) throw new IllegalStateException("Too many active transfers");
		UUID transferId = UUID.randomUUID();
		List<TransferPartPayload> parts = TransferFragmenter.fragment(transferId, data);
		TransferSession session = new TransferSession(parts, RETRY_TIMEOUT_MILLIS, 8);
		session.start(System.currentTimeMillis());
		playerTransfers.put(transferId, new PendingTransfer(session, parts));
		return transferId;
	}

	public synchronized void acknowledge(UUID playerId, TransferAckPayload acknowledgement) {
		Map<UUID, PendingTransfer> playerTransfers = transfers.get(playerId);
		if (playerTransfers == null) return;
		PendingTransfer pending = playerTransfers.get(acknowledgement.transferId());
		if (pending == null) return;
		TransferSession.Status status = pending.session.acknowledge(acknowledgement, System.currentTimeMillis());
		pending.nextPart = Math.max(pending.nextPart, acknowledgement.highestContiguousPart() + 1);
		if (status == TransferSession.Status.COMPLETED) {
			playerTransfers.remove(acknowledgement.transferId());
		}
	}

	private synchronized void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<UUID, LinkedHashMap<UUID, PendingTransfer>>> players = transfers.entrySet().iterator();
		while (players.hasNext()) {
			Map.Entry<UUID, LinkedHashMap<UUID, PendingTransfer>> playerEntry = players.next();
			ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
			if (player == null) {
				players.remove();
				continue;
			}
			Iterator<PendingTransfer> pendingIterator = playerEntry.getValue().values().iterator();
			while (pendingIterator.hasNext()) {
				PendingTransfer pending = pendingIterator.next();
				TransferSession.Status status = pending.session.checkTimeout(now);
				if (status == TransferSession.Status.RETRIES_EXHAUSTED) {
					pendingIterator.remove();
					continue;
				}
				if (status == TransferSession.Status.TIMED_OUT) {
					pending.session.retry(now);
					pending.nextPart = pending.session.highestAcknowledgedPart() + 1;
				}
				while (pending.nextPart < pending.parts.size()) {
					TransferPartPayload part = pending.parts.get(pending.nextPart);
					int estimatedBytes = part.payload().length + 64;
					if (!budget.trySpend(player.getUUID(), estimatedBytes)) break;
					sender.accept(player, part);
					pending.nextPart++;
				}
			}
			if (playerEntry.getValue().isEmpty()) players.remove();
		}
	}

	public synchronized int pendingCount() {
		return transfers.values().stream().mapToInt(Map::size).sum();
	}

	public synchronized void cancelPlayer(UUID playerId) {
		transfers.remove(playerId);
	}

	public synchronized void clear() {
		transfers.clear();
	}

	private static final class PendingTransfer {
		private final TransferSession session;
		private final List<TransferPartPayload> parts;
		private int nextPart;

		private PendingTransfer(TransferSession session, List<TransferPartPayload> parts) {
			this.session = session;
			this.parts = new ArrayList<>(parts);
		}
	}
}
