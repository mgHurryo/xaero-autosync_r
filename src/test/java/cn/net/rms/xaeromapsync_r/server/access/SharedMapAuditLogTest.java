package cn.net.rms.xaeromapsync_r.server.access;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SharedMapAuditLogTest {
	@Test
	void appendsStructuredAuditEntry(@TempDir Path tempDir) throws Exception {
		Path path = tempDir.resolve("access_audit.jsonl");
		SharedMapAuditLog audit = new SharedMapAuditLog();
		audit.start(path);
		UUID actorId = UUID.randomUUID();
		UUID waypointId = UUID.randomUUID();

		audit.record(
				new SharedMapActor(actorId, "Admin", "staff", true),
				"access.disable",
				true,
				new RegionKey("minecraft:overworld", 1, -2),
				waypointId,
				"manual control");

		String entry = Files.readString(path);
		assertTrue(entry.contains("\"action\":\"access.disable\""));
		assertTrue(entry.contains(actorId.toString()));
		assertTrue(entry.contains(waypointId.toString()));
		assertTrue(entry.contains("minecraft:overworld"));
	}
}
