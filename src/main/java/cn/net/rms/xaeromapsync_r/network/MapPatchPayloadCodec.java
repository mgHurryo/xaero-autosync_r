package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

final class MapPatchPayloadCodec {
	private static final int MAX_DIMENSION_LENGTH = 256;

	private MapPatchPayloadCodec() { }

	static void writeManifest(FriendlyByteBuf buffer, MapPatchManifest manifest) {
		buffer.writeUtf(manifest.key().dimension(), MAX_DIMENSION_LENGTH);
		buffer.writeInt(manifest.key().minChunkX());
		buffer.writeInt(manifest.key().minChunkZ());
		buffer.writeVarInt(manifest.key().sideLength());
		buffer.writeLong(manifest.epoch());
		buffer.writeVarLong(manifest.revision());
		buffer.writeLong(manifest.contentHash());
		buffer.writeVarInt(manifest.tiles().size());
		for (MapPatchManifest.TileReference tile : manifest.tiles()) {
			buffer.writeInt(tile.chunkX());
			buffer.writeInt(tile.chunkZ());
			buffer.writeVarLong(tile.revision());
			buffer.writeLong(tile.contentHash());
		}
	}

	static MapPatchManifest readManifest(FriendlyByteBuf buffer) {
		MapPatchKey key = MapPatchKey.square(buffer.readUtf(MAX_DIMENSION_LENGTH), buffer.readInt(), buffer.readInt(),
				buffer.readVarInt());
		long epoch = buffer.readLong();
		long revision = buffer.readVarLong();
		long declaredHash = buffer.readLong();
		int count = buffer.readVarInt();
		if (count != key.tileCount())
			throw new IllegalArgumentException("Invalid patch manifest tile count: " + count);
		List<MapPatchManifest.TileReference> tiles = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			tiles.add(new MapPatchManifest.TileReference(buffer.readInt(), buffer.readInt(), buffer.readVarLong(), buffer.readLong()));
		}
		MapPatchManifest manifest = new MapPatchManifest(key, epoch, revision, tiles);
		if (manifest.contentHash() != declaredHash) throw new IllegalArgumentException("Patch manifest hash mismatch");
		return manifest;
	}
}
