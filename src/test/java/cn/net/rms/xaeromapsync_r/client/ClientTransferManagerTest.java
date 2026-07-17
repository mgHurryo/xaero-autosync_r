package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.xaeromapsync_r.network.TransferFragmenter;
import cn.net.rms.xaeromapsync_r.network.TransferPartPayload;
import java.util.List;
import java.util.UUID;
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
}
