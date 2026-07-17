package cn.net.rms.xaeromapsync_r.server;

import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Server-side core/ahead, ring, then background ordering for patch manifests. */
public final class ViewportPatchPrioritizer {
	private ViewportPatchPrioritizer() { }

	public static List<MapPatchManifest> sort(List<MapPatchManifest> manifests, int centerChunkX, int centerChunkZ,
			double motionX, double motionZ) {
		List<MapPatchManifest> result = new ArrayList<>(manifests);
		result.sort(Comparator.comparing((MapPatchManifest manifest) -> score(manifest.key(), centerChunkX, centerChunkZ,
				motionX, motionZ)).thenComparing((MapPatchManifest manifest) -> -manifest.key().sideLength())
				.thenComparing(MapPatchManifest::key));
		return List.copyOf(result);
	}

	static Score score(MapPatchKey key, int centerChunkX, int centerChunkZ, double motionX, double motionZ) {
		double patchCenterX = key.minChunkX() + (key.sideLength() - 1) / 2.0D;
		double patchCenterZ = key.minChunkZ() + (key.sideLength() - 1) / 2.0D;
		double dx = patchCenterX - centerChunkX;
		double dz = patchCenterZ - centerChunkZ;
		double distance = Math.max(Math.abs(dx), Math.abs(dz));
		int band = distance <= 12.0D ? 0 : distance <= 40.0D ? 1 : 2;
		double motionLength = Math.hypot(motionX, motionZ);
		double ahead = motionLength < 0.001D ? 0.0D : -(dx * motionX + dz * motionZ) / motionLength;
		return new Score(band, ahead, distance);
	}

	record Score(int band, double ahead, double distance) implements Comparable<Score> {
		@Override public int compareTo(Score other) {
			int bandOrder = Integer.compare(band, other.band);
			if (bandOrder != 0) return bandOrder;
			int aheadOrder = Double.compare(ahead, other.ahead);
			return aheadOrder != 0 ? aheadOrder : Double.compare(distance, other.distance);
		}
	}
}
