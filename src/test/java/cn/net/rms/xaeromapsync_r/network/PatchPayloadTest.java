package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileHasher;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class PatchPayloadTest {
	@Test
	void roundTripsCompletePatchAndManifestPage() {
		MapPatchKey key = new MapPatchKey("minecraft:overworld", 2, -3);
		List<TileDataPayload> bodies = new ArrayList<>();
		List<MapPatchManifest.TileReference> references = new ArrayList<>();
		for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) {
			MapTile tile = tile(key.minChunkX() + dx, key.minChunkZ() + dz);
			long revision = dx * 4L + dz + 1L;
			bodies.add(TileDataPayload.fromTile(tile, revision, "zlib"));
			references.add(new MapPatchManifest.TileReference(tile.chunkX(), tile.chunkZ(), revision, tile.contentHash()));
		}
		MapPatchManifest manifest = new MapPatchManifest(key, Long.MIN_VALUE, 16L, references);
		PatchDataPayload payload = new PatchDataPayload(manifest, bodies);
		FriendlyByteBuf dataBuffer = new FriendlyByteBuf(Unpooled.buffer());
		payload.write(dataBuffer);
		PatchDataPayload decoded = PatchDataPayload.read(dataBuffer);
		assertEquals(16, decoded.tiles().size());
		assertEquals(manifest.contentHash(), decoded.patch().manifest().contentHash());

		PatchManifestPagePayload page = new PatchManifestPagePayload(9L, Long.MIN_VALUE, 1, 1, List.of(manifest));
		FriendlyByteBuf pageBuffer = new FriendlyByteBuf(Unpooled.buffer());
		page.write(pageBuffer);
		PatchManifestPagePayload decodedPage = PatchManifestPagePayload.read(pageBuffer);
		assertEquals(1, decodedPage.manifests().size());
		assertEquals(Long.MIN_VALUE, decodedPage.epoch());
		assertEquals(Long.MIN_VALUE, decodedPage.manifests().get(0).epoch());
	}

	@Test
	void roundTripsAdaptiveThreeByThreePatchAndManifestPage() {
		MapPatchKey key = MapPatchKey.square("minecraft:overworld", -6, 12, 3);
		List<TileDataPayload> bodies = new ArrayList<>();
		List<MapPatchManifest.TileReference> references = new ArrayList<>();
		for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++) {
			MapTile tile = tile(key.minChunkX() + dx, key.minChunkZ() + dz);
			long revision = dx * 3L + dz + 1L;
			bodies.add(TileDataPayload.fromTile(tile, revision, "zlib"));
			references.add(new MapPatchManifest.TileReference(tile.chunkX(), tile.chunkZ(), revision, tile.contentHash()));
		}
		MapPatchManifest manifest = new MapPatchManifest(key, 13L, 9L, references);
		FriendlyByteBuf dataBuffer = new FriendlyByteBuf(Unpooled.buffer());
		new PatchDataPayload(manifest, bodies).write(dataBuffer);
		PatchDataPayload decoded = PatchDataPayload.read(dataBuffer);
		assertEquals(9, decoded.tiles().size());
		assertEquals(manifest.contentHash(), decoded.patch().manifest().contentHash());

		FriendlyByteBuf pageBuffer = new FriendlyByteBuf(Unpooled.buffer());
		new PatchManifestPagePayload(14L, 13L, 1, 1, List.of(manifest)).write(pageBuffer);
		assertEquals(9, PatchManifestPagePayload.read(pageBuffer).manifests().get(0).tiles().size());
	}

	private static MapTile tile(int chunkX, int chunkZ) {
		int[] states = new int[256]; Arrays.fill(states, 1);
		int[] heights = new int[256]; Arrays.fill(heights, 64);
		String[] biomes = new String[256]; Arrays.fill(biomes, "minecraft:plains");
		MapTile unhashed = new MapTile("minecraft:overworld", chunkX, chunkZ, states, heights, heights, biomes,
				new byte[256], new boolean[256], new boolean[256], MapTile.emptyOverlays(256), 0L);
		return new MapTile(unhashed.dimension(), chunkX, chunkZ, states, heights, heights, biomes,
				new byte[256], new boolean[256], new boolean[256], MapTile.emptyOverlays(256), MapTileHasher.hashSurface(unhashed));
	}
}
