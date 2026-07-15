package cn.net.rms.xaeromapsync_r.server.activity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

/** Thread-safe owner of region activity records and manual controls. */
public final class RegionActivityStore {
	private final Map<RegionKey, RegionActivityRecord> records = new LinkedHashMap<>();
	private final int stormBlockChangesThreshold;
	private final int stormDirtyChunksThreshold;
	private final int stormCooldownTicks;
	private final int stableTicks;
	private boolean paused;
	private final Set<RegionKey> pausedRegions = new HashSet<>();

	public RegionActivityStore(
			int stormBlockChangesThreshold,
			int stormDirtyChunksThreshold,
			int stormCooldownTicks,
			int stableTicks) {
		// Let the record validate all threshold values consistently.
		new RegionActivityRecord(
				stormBlockChangesThreshold,
				stormDirtyChunksThreshold,
				stormCooldownTicks,
				stableTicks);
		this.stormBlockChangesThreshold = stormBlockChangesThreshold;
		this.stormDirtyChunksThreshold = stormDirtyChunksThreshold;
		this.stormCooldownTicks = stormCooldownTicks;
		this.stableTicks = stableTicks;
	}

	/** Records one tick unless automatic processing is paused. */
	public synchronized void recordTick(RegionKey key, int blockChanges, int dirtyChunks) {
		Objects.requireNonNull(key, "key");
		if (blockChanges < 0 || dirtyChunks < 0) {
			throw new IllegalArgumentException("Activity counts must not be negative");
		}
		if (paused || pausedRegions.contains(key)) {
			return;
		}
		records.computeIfAbsent(key, ignored -> newRecord()).recordTick(blockChanges, dirtyChunks);
	}

	public synchronized Optional<RegionActivityRecord> get(RegionKey key) {
		return Optional.ofNullable(records.get(Objects.requireNonNull(key, "key")));
	}

	public synchronized void pause() {
		paused = true;
	}

	public synchronized void resume() {
		paused = false;
	}

	public synchronized boolean isPaused() {
		return paused;
	}

	public synchronized void pause(RegionKey key) { pausedRegions.add(Objects.requireNonNull(key, "key")); }
	public synchronized void resume(RegionKey key) { pausedRegions.remove(Objects.requireNonNull(key, "key")); }
	public synchronized boolean isPaused(RegionKey key) { return pausedRegions.contains(Objects.requireNonNull(key, "key")); }

	/** Manual controls remain available while automatic processing is paused. */
	public synchronized void markStorm(RegionKey key) {
		Objects.requireNonNull(key, "key");
		records.computeIfAbsent(key, ignored -> newRecord()).markStorm();
	}

	public synchronized boolean clear(RegionKey key) {
		return records.remove(Objects.requireNonNull(key, "key")) != null;
	}

	public synchronized int clear() {
		int cleared = records.size();
		records.clear();
		return cleared;
	}

	public synchronized Statistics statistics() {
		int quiet = 0;
		int active = 0;
		int storm = 0;
		int cooldown = 0;
		int stable = 0;
		for (RegionActivityRecord record : records.values()) {
			switch (record.state()) {
				case QUIET:
					quiet++;
					break;
				case ACTIVE:
					active++;
					break;
				case STORM:
					storm++;
					break;
				case COOLDOWN:
					cooldown++;
					break;
				case STABLE:
					stable++;
					break;
				default:
					break;
			}
		}
		return new Statistics(records.size(), quiet, active, storm, cooldown, stable, paused);
	}

	private RegionActivityRecord newRecord() {
		return new RegionActivityRecord(
				stormBlockChangesThreshold,
				stormDirtyChunksThreshold,
				stormCooldownTicks,
				stableTicks);
	}

	public record Statistics(
			int total,
			int quiet,
			int active,
			int storm,
			int cooldown,
			int stable,
			boolean paused) {
	}
}
