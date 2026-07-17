package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.map.MapPatch;
import cn.net.rms.xaeromapsync_r.map.MapPatchKey;
import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;

public final class PatchDataPayload {
	private final MapPatch patch;
	private final List<TileDataPayload> tiles;

	public PatchDataPayload(MapPatchManifest manifest, List<TileDataPayload> tiles) {
		if (tiles == null || tiles.size() != manifest.tiles().size())
			throw new IllegalArgumentException("Patch data must match the manifest tile count");
		this.patch = new MapPatch(manifest, tiles.stream().map(TileDataPayload::tile).toList());
		Map<Long, MapPatchManifest.TileReference> references = new HashMap<>(manifest.tiles().size() * 2);
		for (MapPatchManifest.TileReference reference : manifest.tiles()) {
			MapPatchManifest.TileReference previous = references.put(coordinateKey(reference.chunkX(), reference.chunkZ()), reference);
			if (previous != null) throw new IllegalArgumentException("Patch manifest contains duplicate tile coordinates");
		}
		for (TileDataPayload tile : tiles) {
			MapPatchManifest.TileReference reference = references.get(coordinateKey(tile.tile().chunkX(), tile.tile().chunkZ()));
			if (reference == null) throw new IllegalArgumentException("Patch tile is missing from the manifest");
			if (reference.revision() != tile.revision()) throw new IllegalArgumentException("Patch tile revision mismatch");
		}
		this.tiles = List.copyOf(tiles);
	}

	public static PatchDataPayload read(FriendlyByteBuf buffer) {
		MapPatchManifest manifest = MapPatchPayloadCodec.readManifest(buffer);
		int count = buffer.readVarInt();
		if (count != manifest.key().tileCount())
			throw new IllegalArgumentException("Invalid patch data tile count: " + count);
		List<TileDataPayload> tiles = new ArrayList<>(count);
		for (int index = 0; index < count; index++) tiles.add(TileDataPayload.read(buffer));
		return new PatchDataPayload(manifest, tiles);
	}

	public void write(FriendlyByteBuf buffer) {
		MapPatchPayloadCodec.writeManifest(buffer, patch.manifest());
		buffer.writeVarInt(tiles.size());
		for (TileDataPayload tile : tiles) tile.write(buffer);
	}

	public MapPatch patch() { return patch; }
	public List<TileDataPayload> tiles() { return tiles; }

	private static long coordinateKey(int chunkX, int chunkZ) {
		return (long) chunkX << 32 | chunkZ & 0xffffffffL;
	}
}
