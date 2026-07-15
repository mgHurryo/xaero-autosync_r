package cn.net.rms.xaeromapsync_r.xaero;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ReflectiveXaeroMapAdapterTest {
	@Test
	void versionGateOnlyAcceptsXaeroWorldMap1251() {
		assertTrue(ReflectiveXaeroMapAdapter.supportsVersion("1.25.1"));
		assertFalse(ReflectiveXaeroMapAdapter.supportsVersion("1.25"));
		assertFalse(ReflectiveXaeroMapAdapter.supportsVersion("1.25.2"));
		assertFalse(ReflectiveXaeroMapAdapter.supportsVersion(null));
	}

	@Test
	void coordinatesUseFloorDivisionForNegativeChunks() {
		assertEquals(-2, ReflectiveXaeroMapAdapter.regionCoordinate(-33));
		assertEquals(-1, ReflectiveXaeroMapAdapter.regionCoordinate(-1));
		assertEquals(0, ReflectiveXaeroMapAdapter.regionCoordinate(31));
		assertEquals(1, ReflectiveXaeroMapAdapter.regionCoordinate(32));
		assertEquals(-2, ReflectiveXaeroMapAdapter.tileChunkCoordinate(-5));
		assertEquals(-1, ReflectiveXaeroMapAdapter.tileChunkCoordinate(-1));
		assertEquals(1, ReflectiveXaeroMapAdapter.tileChunkCoordinate(4));
	}

	@Test
	void inactiveDimensionIsNotAnAdapterFailure() {
		assertFalse(ReflectiveXaeroMapAdapter.isCurrentDimension("minecraft:the_nether", "minecraft:overworld"));
		assertTrue(ReflectiveXaeroMapAdapter.isCurrentDimension("minecraft:overworld", "minecraft:overworld"));
	}

	@Test
	void validTileIsDelegatedToRuntime() {
		AtomicInteger calls = new AtomicInteger();
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(tile -> calls.incrementAndGet());

		adapter.apply(tile(256));

		assertEquals(1, calls.get());
		assertTrue(adapter.isAvailable());
	}

	@Test
	void invalidSurfaceDisablesBeforeRuntimeMutation() {
		AtomicInteger calls = new AtomicInteger();
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(tile -> calls.incrementAndGet());

		adapter.apply(tile(255));

		assertEquals(0, calls.get());
		assertFalse(adapter.isAvailable());
	}

	@Test
	void reflectiveFailureDisablesAdapterAndFutureCallsAreNoOps() {
		AtomicInteger calls = new AtomicInteger();
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(tile -> {
			calls.incrementAndGet();
			throw new ReflectiveOperationException("signature changed");
		});

		adapter.apply(tile(256));
		adapter.apply(tile(256));

		assertEquals(1, calls.get());
		assertFalse(adapter.isAvailable());
	}

	@Test
	void notReadyRuntimeRemainsAvailableForRetry() {
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(tile -> {
			throw new IllegalStateException("Xaero WorldMapSession is not initialized");
		});

		assertFalse(adapter.apply(tile(256)));
		assertTrue(adapter.isAvailable());
	}

	private static MapTile tile(int size) {
		return new MapTile("minecraft:overworld", -1, -5, new int[size], new int[size], new int[size], new int[size], 1L);
	}
}
