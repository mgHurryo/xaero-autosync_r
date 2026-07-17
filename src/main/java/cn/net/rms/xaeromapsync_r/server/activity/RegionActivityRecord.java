package cn.net.rms.xaeromapsync_r.server.activity;

import java.util.Objects;

/** Thread-safe activity state machine for one region. */
public final class RegionActivityRecord {
	private final RegionActivityThresholds thresholds;
	private RegionActivityState state = RegionActivityState.QUIET;
	private int totalBlockChanges;
	private int totalDirtyChunks;
	private int totalTntEntities;
	private int totalExplosions;
	private int totalPistonActions;
	private int totalLightUpdates;
	private int quietTicks;

	public RegionActivityRecord(
			int stormBlockChangesThreshold,
			int stormDirtyChunksThreshold,
			int stormCooldownTicks,
			int stableTicks) {
		this(RegionActivityThresholds.blockOnly(
				stormBlockChangesThreshold,
				stormDirtyChunksThreshold,
				stormCooldownTicks,
				stableTicks));
	}

	public RegionActivityRecord(RegionActivityThresholds thresholds) {
		this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
	}

	/** Applies exactly one tick of observations. */
	public synchronized void recordTick(int blockChanges, int dirtyChunks) {
		recordTick(new RegionActivitySample(blockChanges, dirtyChunks, 0, 0, 0, 0));
	}

	/** Applies exactly one tick of all supported activity signals. */
	public synchronized void recordTick(RegionActivitySample sample) {
		Objects.requireNonNull(sample, "sample");
		totalBlockChanges = saturatedAdd(totalBlockChanges, sample.blockChanges());
		totalDirtyChunks = saturatedAdd(totalDirtyChunks, sample.dirtyChunks());
		totalTntEntities = saturatedAdd(totalTntEntities, sample.tntEntities());
		totalExplosions = saturatedAdd(totalExplosions, sample.explosions());
		totalPistonActions = saturatedAdd(totalPistonActions, sample.pistonActions());
		totalLightUpdates = saturatedAdd(totalLightUpdates, sample.lightUpdates());

		if (sample.hasActivity()) {
			quietTicks = 0;
			if (state == RegionActivityState.COOLDOWN
					|| state == RegionActivityState.STORM
					|| thresholds.isStorm(sample)) {
				state = RegionActivityState.STORM;
			} else {
				state = RegionActivityState.ACTIVE;
			}
			return;
		}

		switch (state) {
			case ACTIVE:
				if (++quietTicks >= thresholds.stableTicks()) {
					state = RegionActivityState.STABLE;
					quietTicks = 0;
				}
				break;
			case STORM:
				if (++quietTicks >= thresholds.stormCooldownTicks()) {
					state = RegionActivityState.COOLDOWN;
					quietTicks = 0;
				}
				break;
			case COOLDOWN:
				if (++quietTicks >= thresholds.stableTicks()) {
					state = RegionActivityState.STABLE;
					quietTicks = 0;
				}
				break;
			case QUIET:
			case STABLE:
			default:
				break;
		}
	}

	public synchronized void markStorm() {
		state = RegionActivityState.STORM;
		quietTicks = 0;
	}

	public synchronized RegionActivityState state() {
		return state;
	}

	public synchronized int totalBlockChanges() {
		return totalBlockChanges;
	}

	public synchronized int totalDirtyChunks() {
		return totalDirtyChunks;
	}

	public synchronized int totalTntEntities() { return totalTntEntities; }
	public synchronized int totalExplosions() { return totalExplosions; }
	public synchronized int totalPistonActions() { return totalPistonActions; }
	public synchronized int totalLightUpdates() { return totalLightUpdates; }

	public synchronized int quietTicks() {
		return quietTicks;
	}

	private static int saturatedAdd(int current, int increment) {
		return increment > Integer.MAX_VALUE - current ? Integer.MAX_VALUE : current + increment;
	}
}
