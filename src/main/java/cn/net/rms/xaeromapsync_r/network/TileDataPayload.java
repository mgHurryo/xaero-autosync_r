package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import cn.net.rms.xaeromapsync_r.map.MapTileHasher;
import net.minecraft.network.FriendlyByteBuf;

public final class TileDataPayload {
	private static final int MAX_DIMENSION_LENGTH = 256;
	private static final int TILE_HEIGHT_COUNT = 256;
	private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
	private final MapTile tile;
	private final long revision;
	private final String compression;
	private final byte[] surfacePayload;

	public TileDataPayload(MapTile tile, long revision, String compression, byte[] surfacePayload) {
		if (tile.baseHeights().length != TILE_HEIGHT_COUNT) {
			throw new IllegalArgumentException("Map tile must contain 256 columns");
		}
		if (surfacePayload.length > MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException("Tile payload is too large: " + surfacePayload.length);
		}
		this.tile = tile;
		this.revision = revision;
		this.compression = compression;
		this.surfacePayload = surfacePayload.clone();
	}

	public static TileDataPayload fromTile(MapTile tile, long revision, String compression) {
		return new TileDataPayload(tile, revision, compression,
				CompressionCodec.encodeSurface(CompressionCodec.MapTileSurfaceData.fromTile(tile), compression));
	}

	public static TileDataPayload read(FriendlyByteBuf buffer) {
		int formatVersion = buffer.readVarInt();
		if (formatVersion != MapTile.FORMAT_VERSION) {
			throw new IllegalArgumentException("Unsupported map tile wire format: " + formatVersion);
		}
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
		CompressionCodec.MapTileSurfaceData surface = CompressionCodec.decodeSurface(payload, TILE_HEIGHT_COUNT, compression);
		MapTile tile = new MapTile(dimension, chunkX, chunkZ, surface.baseStateIds(), surface.baseHeights(),
				surface.topHeights(), surface.biomeKeys(), surface.lightAbove(), surface.glowing(), surface.cave(),
				surface.overlays(), contentHash);
		if (MapTileHasher.hashSurface(tile) != contentHash) {
			throw new IllegalArgumentException("Map tile payload hash mismatch");
		}
		return new TileDataPayload(tile, revision, compression, payload);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeVarInt(MapTile.FORMAT_VERSION);
		buffer.writeUtf(tile.dimension(), MAX_DIMENSION_LENGTH);
		buffer.writeInt(tile.chunkX());
		buffer.writeInt(tile.chunkZ());
		buffer.writeVarLong(revision);
		buffer.writeLong(tile.contentHash());
		buffer.writeUtf(compression, 32);
		buffer.writeVarInt(surfacePayload.length);
		buffer.writeBytes(surfacePayload);
	}

	public MapTile tile() {
		return tile;
	}

	public long revision() {
		return revision;
	}
}
