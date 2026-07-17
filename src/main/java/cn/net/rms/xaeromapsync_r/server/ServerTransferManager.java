package cn.net.rms.xaeromapsync_r.server;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.network.TransferAckPayload;
import cn.net.rms.xaeromapsync_r.network.TransferFragmenter;
import cn.net.rms.xaeromapsync_r.network.TransferPartPayload;
import cn.net.rms.xaeromapsync_r.network.TransferNackPayload;
import cn.net.rms.xaeromapsync_r.network.TransferSession;
import java.util.ArrayList;
import java.util.Collections;
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
	private static final int MAX_TRANSFERS_PER_PLAYER = 24;
	private static final long RETRY_TIMEOUT_MILLIS = 5_000L;
	private final NetworkBudgetTracker budget;
	private final BiConsumer<ServerPlayer, TransferPartPayload> sender;
	private final Map<UUID, LinkedHashMap<UUID, PendingTransfer>> transfers = new LinkedHashMap<>();
	private long ticks;
	private long lowPriorityDeferrals;

	public ServerTransferManager(NetworkBudgetTracker budget, BiConsumer<ServerPlayer, TransferPartPayload> sender) {
		this.budget = budget;
		this.sender = sender;
	}

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::tick);
	}

	public synchronized UUID start(ServerPlayer player, byte[] data) {
		return start(player, data, false);
	}

	public synchronized UUID start(ServerPlayer player, byte[] data, boolean lowPriority) {
		LinkedHashMap<UUID, PendingTransfer> playerTransfers = transfers.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashMap<>());
		if (playerTransfers.size() >= MAX_TRANSFERS_PER_PLAYER) throw new IllegalStateException("Too many active transfers");
		UUID transferId = UUID.randomUUID();
		List<TransferPartPayload> parts = TransferFragmenter.fragment(transferId, data);
		TransferSession session = new TransferSession(parts, RETRY_TIMEOUT_MILLIS, 8);
		playerTransfers.put(transferId, new PendingTransfer(session, parts, lowPriority));
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

	public synchronized void negativeAcknowledge(UUID playerId, TransferNackPayload acknowledgement) {
		Map<UUID, PendingTransfer> playerTransfers = transfers.get(playerId);
		if (playerTransfers == null) return;
		if (acknowledgement.missingPartIndexes().isEmpty()) {
			playerTransfers.remove(acknowledgement.transferId());
			if (playerTransfers.isEmpty()) transfers.remove(playerId);
			return;
		}
		PendingTransfer pending = playerTransfers.get(acknowledgement.transferId());
		if (pending == null) return;
		int firstMissing = firstRetransmissionPart(
				acknowledgement.missingPartIndexes(), pending.parts.size());
		pending.nextPart = Math.min(pending.nextPart, firstMissing);
	}

	static int firstRetransmissionPart(List<Integer> missingPartIndexes, int partCount) {
		int firstMissing = Collections.min(missingPartIndexes);
		return firstMissing < partCount ? firstMissing : 0;
	}

	private synchronized void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		processTransfers(server, now, false);
		processTransfers(server, now, true);
		if (++ticks % 200L == 0L) logSummary(now);
	}

	private void processTransfers(MinecraftServer server, long now, boolean lowPriorityPass) {
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
				if (pending.lowPriority != lowPriorityPass) continue;
				boolean awaitingAcknowledgement = pending.sendingStarted
						&& pending.nextPart > pending.session.highestAcknowledgedPart() + 1;
				TransferSession.Status status = awaitingAcknowledgement
						? pending.session.checkTimeout(now) : pending.session.status();
				if (status == TransferSession.Status.RETRIES_EXHAUSTED) {
					UUID transferId = pending.parts.isEmpty() ? null : pending.parts.get(0).transferId();
					XaeroMapsync_r.LOGGER.warn(
							"map_sync event=server_transfer_retries_exhausted player_id={} transfer_id={} parts={} acknowledged_part={} low_priority={} age_ms={}",
							player.getUUID(), transferId, pending.parts.size(), pending.session.highestAcknowledgedPart(),
							pending.lowPriority, now - pending.createdAtMillis);
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
					boolean spent = lowPriorityPass
							? budget.trySpendLowPriority(player.getUUID(), estimatedBytes)
							: budget.trySpend(player.getUUID(), estimatedBytes);
					if (!spent) {
						if (lowPriorityPass) lowPriorityDeferrals++;
						break;
					}
					if (!pending.sendingStarted) {
						pending.session.start(now);
						pending.sendingStarted = true;
					}
					sender.accept(player, part);
					pending.session.markPartSent(now);
					pending.nextPart++;
				}
			}
			if (playerEntry.getValue().isEmpty()) players.remove();
		}
	}

	private void logSummary(long now) {
		long normalPending = transfers.values().stream().flatMap(value -> value.values().stream())
				.filter(pending -> !pending.lowPriority).count();
		long lowPending = transfers.values().stream().flatMap(value -> value.values().stream())
				.filter(pending -> pending.lowPriority).count();
		long oldestLowWait = transfers.values().stream().flatMap(value -> value.values().stream())
				.filter(pending -> pending.lowPriority).mapToLong(pending -> now - pending.createdAtMillis)
				.max().orElse(0L);
		XaeroMapsync_r.LOGGER.info(
				"map_sync event=server_transfer_summary normal_pending={} low_pending={} oldest_low_wait_ms={} low_deferred_ticks={} bandwidth_last_tick={} bandwidth_p95={}",
				normalPending, lowPending, oldestLowWait, lowPriorityDeferrals,
				budget.lastCompletedTickBytes(), budget.p95BytesPerTick());
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
		private final boolean lowPriority;
		private final long createdAtMillis;
		private int nextPart;
		private boolean sendingStarted;

		private PendingTransfer(TransferSession session, List<TransferPartPayload> parts, boolean lowPriority) {
			this.session = session;
			this.parts = new ArrayList<>(parts);
			this.lowPriority = lowPriority;
			this.createdAtMillis = System.currentTimeMillis();
		}
	}
}
