package cn.net.rms.xaeromapsync_r.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ViewportPatchPrioritizerTest {
	@Test
	void ordersCoreAndForwardPatchesBeforeBackground() {
		MapPatchManifest behind = manifest(new MapPatchKey("minecraft:overworld", -2, 0));
		MapPatchManifest ahead = manifest(new MapPatchKey("minecraft:overworld", 2, 0));
		MapPatchManifest background = manifest(new MapPatchKey("minecraft:overworld", 50, 0));

		List<MapPatchManifest> sorted = ViewportPatchPrioritizer.sort(List.of(background, behind, ahead), 0, 0, 1.0D, 0.0D);

		assertEquals(ahead.key(), sorted.get(0).key());
		assertEquals(background.key(), sorted.get(2).key());
	}

	private static MapPatchManifest manifest(MapPatchKey key) {
		List<MapPatchManifest.TileReference> references = new ArrayList<>();
		for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) {
			references.add(new MapPatchManifest.TileReference(key.minChunkX() + dx, key.minChunkZ() + dz, 1L, 1L));
		}
		return new MapPatchManifest(key, 1L, 1L, references);
	}
}
