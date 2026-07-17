package cn.net.rms.xaeromapsync_r.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RegionAccessStoreTest {
	private static final RegionKey REGION = new RegionKey("minecraft:overworld", -1, 2);

	@Test
	void absentRulePreservesCreatorOnlyCompatibility() {
		RegionAccessStore store = new RegionAccessStore();

		assertEquals(RegionAccessDecision.ALLOWED, store.decision(REGION, null));
		assertEquals(RegionAccessDecision.ALLOWED, store.decision(REGION, "builders"));
	}

	@Test
	void explicitAllowlistAndDenyUseDenyPrecedence() {
		RegionAccessStore store = new RegionAccessStore();

		store.allowTeam(REGION, "builders");
		assertEquals(RegionAccessDecision.ALLOWED, store.decision(REGION, "builders"));
		assertEquals(RegionAccessDecision.DENIED_NOT_ALLOWLISTED, store.decision(REGION, "visitors"));
		assertEquals(RegionAccessDecision.DENIED_NOT_ALLOWLISTED, store.decision(REGION, null));

		store.denyTeam(REGION, "builders");
		assertEquals(RegionAccessDecision.DENIED_BY_TEAM, store.decision(REGION, "builders"));
	}

	@Test
	void regionDisableOverridesEveryTeamRule() {
		RegionAccessStore store = new RegionAccessStore();
		store.allowTeam(REGION, "builders");

		store.setWaypointChangesDisabled(REGION, true);

		assertEquals(RegionAccessDecision.DENIED_BY_REGION_DISABLE, store.decision(REGION, "builders"));
		assertEquals(RegionAccessDecision.DENIED_BY_REGION_DISABLE, store.decision(REGION, null));
	}

	@Test
	void rulesRoundTripThroughVersionedJson(@TempDir Path tempDir) throws Exception {
		Path path = tempDir.resolve("region_permissions.json");
		RegionAccessStore original = new RegionAccessStore();
		original.allowTeam(REGION, "builders");
		original.denyTeam(REGION, "visitors");
		original.setWaypointChangesDisabled(REGION, true);
		original.save(path);

		RegionAccessStore restored = new RegionAccessStore();
		restored.load(path);

		RegionAccessRule rule = restored.get(REGION).orElseThrow();
		assertFalse(rule.defaultAllowed());
		assertTrue(rule.waypointChangesDisabled());
		assertEquals(java.util.Set.of("builders"), rule.allowedTeams());
		assertEquals(java.util.Set.of("visitors"), rule.deniedTeams());
	}

	@Test
	void persistedConflictKeepsDenyPrecedence(@TempDir Path tempDir) throws Exception {
		Path path = tempDir.resolve("region_permissions.json");
		Files.writeString(path, "{\"version\":1,\"rules\":[{\"dimension\":\"minecraft:overworld\","
				+ "\"regionX\":-1,\"regionZ\":2,\"defaultAllowed\":false,"
				+ "\"allowedTeams\":[\"builders\"],\"deniedTeams\":[\"builders\"]}]}");
		RegionAccessStore store = new RegionAccessStore();

		store.load(path);

		assertEquals(RegionAccessDecision.DENIED_BY_TEAM, store.decision(REGION, "builders"));
	}
}
