package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStoreTest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class CompressionCodecTest {
	@Test
	void surfaceRoundTripPreservesNativeSemanticsWithAndWithoutCompression() {
		MapTile tile = MapTileDataStoreTest.tile("minecraft:overworld", -3, 8, 12);
		for (String compression : Arrays.asList("none", "zlib")) {
			byte[] encoded = CompressionCodec.encodeSurface(CompressionCodec.MapTileSurfaceData.fromTile(tile), compression);
			CompressionCodec.MapTileSurfaceData decoded = CompressionCodec.decodeSurface(encoded, 256, compression);

			assertArrayEquals(tile.baseStateIds(), decoded.baseStateIds());
			assertArrayEquals(tile.baseHeights(), decoded.baseHeights());
			assertArrayEquals(tile.topHeights(), decoded.topHeights());
			assertArrayEquals(tile.biomeKeys(), decoded.biomeKeys());
			assertArrayEquals(tile.lightAbove(), decoded.lightAbove());
			assertArrayEquals(tile.glowing(), decoded.glowing());
			assertArrayEquals(tile.cave(), decoded.cave());
			assertEquals(tile.overlays(), decoded.overlays());
		}
	}

	@Test
	void decoderRejectsTrailingOrTruncatedSurfaceData() {
		MapTile tile = MapTileDataStoreTest.tile("minecraft:overworld", 0, 0, 1);
		byte[] encoded = CompressionCodec.encodeSurface(CompressionCodec.MapTileSurfaceData.fromTile(tile), "none");
		byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
		byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);

		assertThrows(IllegalArgumentException.class, () -> CompressionCodec.decodeSurface(trailing, 256, "none"));
		assertThrows(IllegalArgumentException.class, () -> CompressionCodec.decodeSurface(truncated, 256, "none"));
	}

	@Test
	void oversizedBiomeKeyIsRejectedBeforeEncodingOrPersistence() {
		MapTile source = MapTileDataStoreTest.tile("minecraft:overworld", 0, 0, 1);
		String[] biomeKeys = source.biomeKeys();
		biomeKeys[0] = "example:" + "x".repeat(MapTile.MAX_BIOME_KEY_BYTES);

		assertThrows(IllegalArgumentException.class, () -> new MapTile(source.dimension(), source.chunkX(), source.chunkZ(),
				source.baseStateIds(), source.baseHeights(), source.topHeights(), biomeKeys, source.lightAbove(),
				source.glowing(), source.cave(), source.overlays(), source.contentHash()));
	}
}
