package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
		ClientTransferManager manager = new ClientTransferManager(ignored -> { }, nacks::add, ignored -> { });
		List<TransferPartPayload> parts = TransferFragmenter.fragment(UUID.randomUUID(),
				new byte[TransferPartPayload.MAX_PART_BYTES + 1]);
		manager.accept(parts.get(0));

		manager.tick(System.currentTimeMillis() + 31_000L);

		assertEquals(0, manager.activeCount());
		assertEquals(List.of(1), nacks.get(0).missingPartIndexes());
	}
}
