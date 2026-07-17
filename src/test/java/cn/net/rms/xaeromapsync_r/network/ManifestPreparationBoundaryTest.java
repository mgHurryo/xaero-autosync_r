package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManifestPreparationBoundaryTest {
	@Test
	void recoversAfterOneManifestPreparationFails() {
		AtomicInteger failures = new AtomicInteger();
		AtomicInteger successes = new AtomicInteger();

		SharedMapNetworking.runManifestPreparationTask(
				() -> { throw new IllegalStateException("broken catalog snapshot"); },
				exception -> failures.incrementAndGet());
		SharedMapNetworking.runManifestPreparationTask(successes::incrementAndGet,
				exception -> failures.incrementAndGet());

		assertEquals(1, failures.get());
		assertEquals(1, successes.get());
	}

	@Test
	void comparesExpectedEpochOnlyForContinuationPages() {
		assertEquals(0, SharedMapNetworking.resumeManifestCursor(0, 0L, Long.MIN_VALUE));
		assertEquals(0, SharedMapNetworking.resumeManifestCursor(4, 0L, Long.MIN_VALUE));
		assertEquals(4, SharedMapNetworking.resumeManifestCursor(4, Long.MIN_VALUE, Long.MIN_VALUE));
	}
}
