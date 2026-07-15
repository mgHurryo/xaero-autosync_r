package cn.net.rms.xaeromapsync_r.server.activity;

/** Thread-safe activity state machine for one region. */
public final class RegionActivityRecord {
	private final int stormBlockChangesThreshold;
	private final int stormDirtyChunksThreshold;
	private final int stormCooldownTicks;
	private final int stableTicks;
	private RegionActivityState state = RegionActivityState.QUIET;
	private int totalBlockChanges;
	private int totalDirtyChunks;
	private int quietTicks;

	public RegionActivityRecord(
			int stormBlockChangesThreshold,
			int stormDirtyChunksThreshold,
			int stormCooldownTicks,
			int stableTicks) {
		this.stormBlockChangesThreshold = requirePositive(stormBlockChangesThreshold, "Storm block-change threshold");
		this.stormDirtyChunksThreshold = requirePositive(stormDirtyChunksThreshold, "Storm dirty-chunk threshold");
		this.stormCooldownTicks = requirePositive(stormCooldownTicks, "Storm cooldown ticks");
		this.stableTicks = requirePositive(stableTicks, "Stable ticks");
	}

	/** Applies exactly one tick of observations. */
	public synchronized void recordTick(int blockChanges, int dirtyChunks) {
		requireNonNegative(blockChanges, "Block changes");
		requireNonNegative(dirtyChunks, "Dirty chunks");

		totalBlockChanges = saturatedAdd(totalBlockChanges, blockChanges);
		totalDirtyChunks = saturatedAdd(totalDirtyChunks, dirtyChunks);

		boolean hasActivity = blockChanges != 0 || dirtyChunks != 0;
		if (hasActivity) {
			quietTicks = 0;
			if (state == RegionActivityState.COOLDOWN
					|| state == RegionActivityState.STORM
					|| blockChanges >= stormBlockChangesThreshold
					|| dirtyChunks >= stormDirtyChunksThreshold) {
				state = RegionActivityState.STORM;
			} else {
				state = RegionActivityState.ACTIVE;
			}
			return;
		}

		switch (state) {
			case ACTIVE:
				if (++quietTicks >= stableTicks) {
					state = RegionActivityState.STABLE;
					quietTicks = 0;
				}
				break;
			case STORM:
				if (++quietTicks >= stormCooldownTicks) {
					state = RegionActivityState.COOLDOWN;
					quietTicks = 0;
				}
				break;
			case COOLDOWN:
				if (++quietTicks >= stableTicks) {
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

	public synchronized int quietTicks() {
		return quietTicks;
	}

	private static int saturatedAdd(int current, int increment) {
		return increment > Integer.MAX_VALUE - current ? Integer.MAX_VALUE : current + increment;
	}

	private static int requirePositive(int value, String name) {
		if (value <= 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return value;
	}

	private static void requireNonNegative(int value, String name) {
		if (value < 0) {
			throw new IllegalArgumentException(name + " must not be negative");
		}
	}
}
