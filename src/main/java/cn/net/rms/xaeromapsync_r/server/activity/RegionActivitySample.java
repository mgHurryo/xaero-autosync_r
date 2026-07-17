package cn.net.rms.xaeromapsync_r.server.activity;

/** Immutable, lightweight activity counters collected for one region during one server tick. */
public record RegionActivitySample(
		int blockChanges,
		int dirtyChunks,
		int tntEntities,
		int explosions,
		int pistonActions,
		int lightUpdates) {
	public static final RegionActivitySample EMPTY = new RegionActivitySample(0, 0, 0, 0, 0, 0);

	public RegionActivitySample {
		requireNonNegative(blockChanges, "Block changes");
		requireNonNegative(dirtyChunks, "Dirty chunks");
		requireNonNegative(tntEntities, "TNT entities");
		requireNonNegative(explosions, "Explosions");
		requireNonNegative(pistonActions, "Piston actions");
		requireNonNegative(lightUpdates, "Light updates");
	}

	public boolean hasActivity() {
		return blockChanges != 0
				|| dirtyChunks != 0
				|| tntEntities != 0
				|| explosions != 0
				|| pistonActions != 0
				|| lightUpdates != 0;
	}

	private static void requireNonNegative(int value, String name) {
		if (value < 0) {
			throw new IllegalArgumentException(name + " must not be negative");
		}
	}
}
