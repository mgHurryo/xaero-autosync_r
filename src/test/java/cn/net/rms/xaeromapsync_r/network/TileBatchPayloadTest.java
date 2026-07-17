package cn.net.rms.xaeromapsync_r.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileDataStoreTest;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class TileBatchPayloadTest {
	@Test
	void requestRoundTripPreservesMultipleTiles() {
		TileBatchRequestPayload decoded = roundTrip(new TileBatchRequestPayload(List.of(
				new TileRequestPayload("minecraft:overworld", -1, 2, 3L),
				new TileRequestPayload("minecraft:overworld", 4, -5, 6L))));

		assertEquals(2, decoded.requests().size());
		assertEquals(-1, decoded.requests().get(0).chunkX());
		assertEquals(6L, decoded.requests().get(1).knownRevision());
	}

	@Test
	void dataRoundTripPreservesMultipleCompressedTiles() {
		MapTile first = MapTileDataStoreTest.tile("minecraft:overworld", -1, 2, 3);
		MapTile second = MapTileDataStoreTest.tile("minecraft:overworld", 4, -5, 6);
		TileBatchDataPayload decoded = roundTrip(new TileBatchDataPayload(List.of(
				TileDataPayload.fromTile(first, 7L, "zlib"),
				TileDataPayload.fromTile(second, 8L, "zlib"))));

		assertEquals(2, decoded.tiles().size());
		assertEquals(first.contentHash(), decoded.tiles().get(0).tile().contentHash());
		assertEquals(8L, decoded.tiles().get(1).revision());
	}

	@Test
	void payloadsRejectMoreThanTheProtocolBatchLimit() {
		List<TileRequestPayload> requests = new ArrayList<>();
		List<TileDataPayload> tiles = new ArrayList<>();
		for (int index = 0; index <= TileBatchRequestPayload.MAX_REQUESTS; index++) {
			requests.add(new TileRequestPayload("minecraft:overworld", index, 0, 1L));
			MapTile tile = MapTileDataStoreTest.tile("minecraft:overworld", index, 0, index);
			tiles.add(TileDataPayload.fromTile(tile, 1L, "zlib"));
		}

		assertThrows(IllegalArgumentException.class, () -> new TileBatchRequestPayload(requests));
		assertThrows(IllegalArgumentException.class, () -> new TileBatchDataPayload(tiles));
	}

	private static TileBatchRequestPayload roundTrip(TileBatchRequestPayload payload) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		payload.write(buffer);
		return TileBatchRequestPayload.read(buffer);
	}

	private static TileBatchDataPayload roundTrip(TileBatchDataPayload payload) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		payload.write(buffer);
		return TileBatchDataPayload.read(buffer);
	}
}
