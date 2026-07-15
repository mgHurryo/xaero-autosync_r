package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import net.minecraft.network.FriendlyByteBuf;

public final class TileDataPayload {
	private static final int MAX_DIMENSION_LENGTH = 256;
	private static final int TILE_HEIGHT_COUNT = 256;
	private static final int MAX_PAYLOAD_BYTES = 8192;
	private final MapTile tile;
	private final long revision;
	private final String compression;
	private final byte[] heightPayload;

	public TileDataPayload(MapTile tile, long revision, String compression, byte[] heightPayload) {
		if (tile.heights().length != TILE_HEIGHT_COUNT) {
			throw new IllegalArgumentException("Map tile must contain 256 heights");
		}
		if (heightPayload.length > MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException("Tile payload is too large: " + heightPayload.length);
		}
		this.tile = tile;
		this.revision = revision;
		this.compression = compression;
		this.heightPayload = heightPayload.clone();
	}

	public static TileDataPayload fromTile(MapTile tile, long revision, String compression) {
		return new TileDataPayload(tile, revision, compression, CompressionCodec.encodeHeights(tile.heights(), compression));
	}

	public static TileDataPayload read(FriendlyByteBuf buffer) {
		String dimension = buffer.readUtf(MAX_DIMENSION_LENGTH);
		int chunkX = buffer.readInt();
		int chunkZ = buffer.readInt();
		long revision = buffer.readVarLong();
		long contentHash = buffer.readLong();
		String compression = buffer.readUtf(32);
		int payloadLength = buffer.readVarInt();
		if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException("Invalid tile payload length: " + payloadLength);
		}
		byte[] payload = new byte[payloadLength];
		buffer.readBytes(payload);
		int[] heights = CompressionCodec.decodeHeights(payload, TILE_HEIGHT_COUNT, compression);
		MapTile tile = new MapTile(dimension, chunkX, chunkZ, heights, contentHash);
		return new TileDataPayload(tile, revision, compression, payload);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeUtf(tile.dimension(), MAX_DIMENSION_LENGTH);
		buffer.writeInt(tile.chunkX());
		buffer.writeInt(tile.chunkZ());
		buffer.writeVarLong(revision);
		buffer.writeLong(tile.contentHash());
		buffer.writeUtf(compression, 32);
		buffer.writeVarInt(heightPayload.length);
		buffer.writeBytes(heightPayload);
	}

	public MapTile tile() {
		return tile;
	}

	public long revision() {
		return revision;
	}
}
