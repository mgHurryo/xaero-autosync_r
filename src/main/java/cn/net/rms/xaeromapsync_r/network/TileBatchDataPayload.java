package cn.net.rms.xaeromapsync_r.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

public final class TileBatchDataPayload {
	public static final int MAX_TILES = TileBatchRequestPayload.MAX_REQUESTS;
	private final List<TileDataPayload> tiles;

	public TileBatchDataPayload(Collection<TileDataPayload> tiles) {
		if (tiles == null || tiles.isEmpty() || tiles.size() > MAX_TILES) {
			throw new IllegalArgumentException("Invalid tile batch count: "
					+ (tiles == null ? 0 : tiles.size()));
		}
		this.tiles = List.copyOf(tiles);
	}

	public static TileBatchDataPayload read(FriendlyByteBuf buffer) {
		int count = buffer.readVarInt();
		if (count < 1 || count > MAX_TILES) {
			throw new IllegalArgumentException("Invalid tile batch count: " + count);
		}
		List<TileDataPayload> tiles = new ArrayList<>(count);
		for (int index = 0; index < count; index++) tiles.add(TileDataPayload.read(buffer));
		return new TileBatchDataPayload(tiles);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeVarInt(tiles.size());
		for (TileDataPayload tile : tiles) tile.write(buffer);
	}

	public List<TileDataPayload> tiles() {
		return tiles;
	}
}
