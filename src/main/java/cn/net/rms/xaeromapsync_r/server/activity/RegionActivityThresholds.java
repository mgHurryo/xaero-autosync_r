package cn.net.rms.xaeromapsync_r.server.activity;

/** Per-tick STORM thresholds and quiet-state timing for regional activity. */
public record RegionActivityThresholds(
		int blockChanges,
		int dirtyChunks,
		int tntEntities,
		int explosions,
		int pistonActions,
		int lightUpdates,
		int stormCooldownTicks,
		int stableTicks) {
	public RegionActivityThresholds {
		requirePositive(blockChanges, "Storm block-change threshold");
		requirePositive(dirtyChunks, "Storm dirty-chunk threshold");
		requirePositive(tntEntities, "Storm TNT-entity threshold");
		requirePositive(explosions, "Storm explosion threshold");
		requirePositive(pistonActions, "Storm piston-action threshold");
		requirePositive(lightUpdates, "Storm light-update threshold");
		requirePositive(stormCooldownTicks, "Storm cooldown ticks");
		requirePositive(stableTicks, "Stable ticks");
	}

	public static RegionActivityThresholds blockOnly(
			int blockChanges,
			int dirtyChunks,
			int stormCooldownTicks,
			int stableTicks) {
		return new RegionActivityThresholds(
				blockChanges,
				dirtyChunks,
				Integer.MAX_VALUE,
				Integer.MAX_VALUE,
				Integer.MAX_VALUE,
				Integer.MAX_VALUE,
				stormCooldownTicks,
				stableTicks);
	}

	public boolean isStorm(RegionActivitySample sample) {
		return sample.blockChanges() >= blockChanges
				|| sample.dirtyChunks() >= dirtyChunks
				|| sample.tntEntities() >= tntEntities
				|| sample.explosions() >= explosions
				|| sample.pistonActions() >= pistonActions
				|| sample.lightUpdates() >= lightUpdates;
	}

	private static void requirePositive(int value, String name) {
		if (value <= 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
