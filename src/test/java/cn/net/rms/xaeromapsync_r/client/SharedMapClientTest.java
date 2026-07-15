package cn.net.rms.xaeromapsync_r.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SharedMapClientTest {
	@TempDir
	Path tempDirectory;

	@Test
	void unavailableTilePreventsRootCompletionAfterQueuesDrain() {
		assertFalse(SharedMapClient.canCompleteMapRoot(true, 0, 0, 0, 0));
	}

	@Test
	void completeRootRequiresEveryQueueAndRequestToBeIdle() {
		assertTrue(SharedMapClient.canCompleteMapRoot(false, 0, 0, 0, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 1, 0, 0, 0));
		assertFalse(SharedMapClient.canCompleteMapRoot(false, 0, 0, 0, 1));
	}

	@Test
	void unchangedLeavesAreRecheckedForMissingLocalRevisions() {
		assertTrue(SharedMapClient.shouldInspectMerkleNode(0, false));
		assertFalse(SharedMapClient.shouldInspectMerkleNode(1, false));
		assertTrue(SharedMapClient.shouldInspectMerkleNode(1, true));
	}

	@Test
	void replacesExistingRootStateFile() throws Exception {
		Path target = tempDirectory.resolve("root.properties");
		Path temp = tempDirectory.resolve("root.properties.tmp");
		Files.writeString(target, "old");
		Files.writeString(temp, "new");

		SharedMapClient.replaceFile(temp, target);

		assertEquals("new", Files.readString(target));
		assertFalse(Files.exists(temp));
	}
}
