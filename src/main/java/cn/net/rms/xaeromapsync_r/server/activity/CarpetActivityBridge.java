package cn.net.rms.xaeromapsync_r.server.activity;

import cn.net.rms.xaeromapsync_r.server.SharedMapServer;

/**
 * Optional integration boundary for Carpet extensions that can detect update suppression.
 * Callers pass only a dimension id and coordinates, so loading this class never requires Carpet.
 */
public final class CarpetActivityBridge {
	private CarpetActivityBridge() {
	}

	public static void recordUpdateSuppression(String dimension, int blockX, int blockZ) {
		SharedMapServer.recordUpdateSuppression(dimension, blockX, blockZ);
	}
}
