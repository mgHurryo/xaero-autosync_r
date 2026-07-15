package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStoreTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class TileDataPayloadTest {
	@Test
	void wireRoundTripPreservesVersionedSurfaceTile() {
		MapTile tile = MapTileDataStoreTest.tile("minecraft:overworld", -7, 11, 4);
		TileDataPayload original = TileDataPayload.fromTile(tile, 42L, "zlib");
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		original.write(buffer);
		TileDataPayload decoded = TileDataPayload.read(buffer);

		assertEquals(42L, decoded.revision());
		assertEquals(tile.contentHash(), decoded.tile().contentHash());
		assertEquals(tile.overlays(), decoded.tile().overlays());
		assertEquals(tile.dimension(), decoded.tile().dimension());
	}

	@Test
	void rejectsUnsupportedWireFormatBeforeReadingTileBody() {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeVarInt(MapTile.FORMAT_VERSION - 1);

		assertThrows(IllegalArgumentException.class, () -> TileDataPayload.read(buffer));
	}
}
