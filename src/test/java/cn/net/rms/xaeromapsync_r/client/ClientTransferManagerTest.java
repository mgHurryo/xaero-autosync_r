package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.network.TransferFragmenter;
import cn.net.rms.xaeromapsync_r.network.TransferPartPayload;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class ClientTransferManagerTest {
	@Test
	void disconnectClearDropsIncompleteTransfers() {
		ClientTransferManager manager = new ClientTransferManager(ignored -> { }, ignored -> { });
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(),
				new byte[TransferPartPayload.MAX_PART_BYTES + 1]);
		manager.accept(parts.get(0));
		assertEquals(1, manager.activeCount());

		manager.clear();

		assertEquals(0, manager.activeCount());
	}

	@Test
	void timeoutSendsMissingPartNackAndReleasesTransfer() {
		List<cn.net.rms.xaeromapsync_r.network.TransferNackPayload> nacks = new ArrayList<>();
		ClientTransferManager manager = new ClientTransferManager(ignored -> { }, nacks::add,
				(data, completion) -> completion.accept(true));
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(),
				new byte[TransferPartPayload.MAX_PART_BYTES + 1]);
		manager.accept(parts.get(0));

		manager.tick(System.currentTimeMillis() + 31_000L);

		assertEquals(0, manager.activeCount());
		assertEquals(List.of(1), nacks.get(0).missingPartIndexes());
	}

	@Test
	void capacityEvictionCancelsTheServerTransfer() {
		List<cn.net.rms.xaeromapsync_r.network.TransferNackPayload> nacks = new ArrayList<>();
		ClientTransferManager manager = new ClientTransferManager(ignored -> { }, nacks::add,
				(data, completion) -> completion.accept(true));
		for (int index = 0; index < 25; index++) {
			List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(),
					new byte[TransferPartPayload.MAX_PART_BYTES + 1]);
			manager.accept(parts.get(0));
		}

		assertEquals(24, manager.activeCount());
		assertEquals(1, nacks.size());
		assertEquals(List.of(), nacks.get(0).missingPartIndexes());
	}

	@Test
	void finalAcknowledgementWaitsForDecodeSuccess() {
		List<cn.net.rms.xaeromapsync_r.network.TransferAckPayload> acknowledgements = new ArrayList<>();
		List<cn.net.rms.xaeromapsync_r.network.TransferNackPayload> nacks = new ArrayList<>();
		List<java.util.function.Consumer<Boolean>> completions = new ArrayList<>();
		ClientTransferManager manager = new ClientTransferManager(acknowledgements::add, nacks::add,
				(data, completion) -> completions.add(completion));
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(), new byte[32]);

		manager.accept(parts.get(0));
		assertTrue(acknowledgements.isEmpty());
		assertEquals(1, manager.activeCount());

		completions.get(0).accept(true);
		assertEquals(1, acknowledgements.size());
		assertEquals(0, manager.activeCount());
		assertTrue(nacks.isEmpty());
	}

	@Test
	void decodeFailureRequestsCompleteRetransmission() {
		List<cn.net.rms.xaeromapsync_r.network.TransferAckPayload> acknowledgements = new ArrayList<>();
		List<cn.net.rms.xaeromapsync_r.network.TransferNackPayload> nacks = new ArrayList<>();
		ClientTransferManager manager = new ClientTransferManager(acknowledgements::add, nacks::add,
				(data, completion) -> completion.accept(false));
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(),
				new byte[TransferPartPayload.MAX_PART_BYTES + 1]);

		for (TransferPartPayload part : parts) manager.accept(part);

		assertEquals(1, acknowledgements.size(), "only the first contiguous part is acknowledged");
		assertEquals(List.of(0, 1), nacks.get(0).missingPartIndexes());
		assertEquals(0, manager.activeCount());
	}
}
