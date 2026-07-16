package cn.net.rms.xaeromapsync_r.server.dirty;

import java.util.BitSet;

public final class DirtyChunkRecord {
	private final String dimension;
	private final int chunkX;
	private final int chunkZ;
	private final BitSet dirtyColumns = new BitSet(256);
	private DirtyActivityState state = DirtyActivityState.ACTIVE;
	private long firstDirtyTick;
	private long lastDirtyTick;
	private int changesInWindow;

	public DirtyChunkRecord(String dimension, int chunkX, int chunkZ, long tick) {
		this.dimension = dimension;
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		this.firstDirtyTick = tick;
		this.lastDirtyTick = tick;
	}

	public void markColumn(int localX, int localZ, long tick) {
		dirtyColumns.set((localZ & 15) * 16 + (localX & 15));
		lastDirtyTick = tick;
		changesInWindow++;
		state = changesInWindow >= 512 ? DirtyActivityState.STORM : DirtyActivityState.ACTIVE;
	}

	public void advance(long tick) {
		advance(tick, 100, 200);
	}

	public void advance(long tick, int stormCooldownTicks, int stableTicks) {
		long quietTicks = tick - lastDirtyTick;
		if (state == DirtyActivityState.STORM && quietTicks >= stormCooldownTicks) {
			state = DirtyActivityState.COOLDOWN;
			return;
		}
		if ((state == DirtyActivityState.ACTIVE || state == DirtyActivityState.COOLDOWN) && quietTicks >= stableTicks) {
			state = DirtyActivityState.STABLE;
			changesInWindow = 0;
		}
	}

	public String dimension() {
		return dimension;
	}

	public int chunkX() {
		return chunkX;
	}

	public int chunkZ() {
		return chunkZ;
	}

	public DirtyActivityState state() {
		return state;
	}

	public long firstDirtyTick() {
		return firstDirtyTick;
	}

	public long lastDirtyTick() {
		return lastDirtyTick;
	}

	public int dirtyColumnCount() {
		return dirtyColumns.cardinality();
	}

	public long[] dirtyColumnsAsLongArray() {
		return dirtyColumns.toLongArray();
	}

	public void restore(DirtyActivityState state, long firstDirtyTick, long lastDirtyTick, long[] dirtyColumns) {
		this.state = state;
		this.firstDirtyTick = firstDirtyTick;
		this.lastDirtyTick = lastDirtyTick;
		this.dirtyColumns.clear();
		if (dirtyColumns != null) {
			this.dirtyColumns.or(BitSet.valueOf(dirtyColumns));
		}
	}
}
