package cn.net.rms.xaeromapsync_r.server.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RegionActivityRecordTest {
	@Test
	void startsQuietAndLowActivityBecomesActive() {
		RegionActivityRecord record = record();

		record.recordTick(0, 0);
		assertEquals(RegionActivityState.QUIET, record.state());

		record.recordTick(9, 1);
		assertEquals(RegionActivityState.ACTIVE, record.state());
		assertEquals(9, record.totalBlockChanges());
		assertEquals(1, record.totalDirtyChunks());
	}

	@Test
	void eitherThresholdEntersStormAtExactBoundary() {
		RegionActivityRecord blockStorm = record();
		RegionActivityRecord dirtyStorm = record();

		blockStorm.recordTick(10, 0);
		dirtyStorm.recordTick(0, 3);

		assertEquals(RegionActivityState.STORM, blockStorm.state());
		assertEquals(RegionActivityState.STORM, dirtyStorm.state());
	}

	@Test
	void eachSpecializedThresholdEntersStormAtExactBoundary() {
		RegionActivitySample[] samples = {
				new RegionActivitySample(0, 0, 4, 0, 0, 0),
				new RegionActivitySample(0, 0, 0, 2, 0, 0),
				new RegionActivitySample(0, 0, 0, 0, 5, 0),
				new RegionActivitySample(0, 0, 0, 0, 0, 6)
		};

		for (RegionActivitySample sample : samples) {
			RegionActivityRecord record = specializedRecord();
			record.recordTick(sample);
			assertEquals(RegionActivityState.STORM, record.state());
		}
	}

	@Test
	void specializedSignalsAccumulateWithoutTriggeringBelowThreshold() {
		RegionActivityRecord record = specializedRecord();
		record.recordTick(new RegionActivitySample(0, 0, 3, 1, 4, 5));

		assertEquals(RegionActivityState.ACTIVE, record.state());
		assertEquals(3, record.totalTntEntities());
		assertEquals(1, record.totalExplosions());
		assertEquals(4, record.totalPistonActions());
		assertEquals(5, record.totalLightUpdates());
	}

	@Test
	void stormCrossesCooldownAndStableBoundaries() {
		RegionActivityRecord record = record();
		record.recordTick(10, 0);

		record.recordTick(0, 0);
		assertEquals(RegionActivityState.STORM, record.state());
		assertEquals(1, record.quietTicks());

		record.recordTick(0, 0);
		assertEquals(RegionActivityState.COOLDOWN, record.state());
		assertEquals(0, record.quietTicks());

		record.recordTick(0, 0);
		record.recordTick(0, 0);
		assertEquals(RegionActivityState.COOLDOWN, record.state());

		record.recordTick(0, 0);
		assertEquals(RegionActivityState.STABLE, record.state());
	}

	@Test
	void cooldownRecurrenceImmediatelyReturnsToStorm() {
		RegionActivityRecord record = record();
		record.recordTick(10, 0);
		record.recordTick(0, 0);
		record.recordTick(0, 0);
		assertEquals(RegionActivityState.COOLDOWN, record.state());

		record.recordTick(1, 0);

		assertEquals(RegionActivityState.STORM, record.state());
		assertEquals(0, record.quietTicks());
	}

	@Test
	void activeAndStableUseConfiguredStableBoundary() {
		RegionActivityRecord record = record();
		record.recordTick(1, 0);
		record.recordTick(0, 0);
		record.recordTick(0, 0);
		assertEquals(RegionActivityState.ACTIVE, record.state());

		record.recordTick(0, 0);
		assertEquals(RegionActivityState.STABLE, record.state());

		record.recordTick(1, 0);
		assertEquals(RegionActivityState.ACTIVE, record.state());
	}

	@Test
	void lifetimeCountersSaturateWithoutOverflow() {
		RegionActivityRecord record = new RegionActivityRecord(Integer.MAX_VALUE, Integer.MAX_VALUE, 2, 3);

		record.recordTick(Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1);
		record.recordTick(100, 100);

		assertEquals(Integer.MAX_VALUE, record.totalBlockChanges());
		assertEquals(Integer.MAX_VALUE, record.totalDirtyChunks());
	}

	@Test
	void rejectsInvalidThresholdsAndNegativeObservations() {
		assertThrows(IllegalArgumentException.class, () -> new RegionActivityRecord(0, 1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new RegionActivityRecord(1, 0, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new RegionActivityRecord(1, 1, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> new RegionActivityRecord(1, 1, 1, 0));
		assertThrows(IllegalArgumentException.class, () -> new RegionActivityThresholds(1, 1, 0, 1, 1, 1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new RegionActivityThresholds(1, 1, 1, 0, 1, 1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new RegionActivityThresholds(1, 1, 1, 1, 0, 1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new RegionActivityThresholds(1, 1, 1, 1, 1, 0, 1, 1));

		RegionActivityRecord record = record();
		assertThrows(IllegalArgumentException.class, () -> record.recordTick(-1, 0));
		assertThrows(IllegalArgumentException.class, () -> record.recordTick(0, -1));
		assertEquals(0, record.totalBlockChanges());
		assertEquals(0, record.totalDirtyChunks());
	}

	@Test
	void manualStormResetsQuietProgress() {
		RegionActivityRecord record = record();
		record.recordTick(1, 0);
		record.recordTick(0, 0);

		record.markStorm();

		assertEquals(RegionActivityState.STORM, record.state());
		assertEquals(0, record.quietTicks());
	}

	private static RegionActivityRecord record() {
		return new RegionActivityRecord(10, 3, 2, 3);
	}

	private static RegionActivityRecord specializedRecord() {
		return new RegionActivityRecord(new RegionActivityThresholds(10, 3, 4, 2, 5, 6, 2, 3));
	}
}
