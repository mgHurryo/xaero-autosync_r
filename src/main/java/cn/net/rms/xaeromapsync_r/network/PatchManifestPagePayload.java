package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.map.MapPatchManifest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

public final class PatchManifestPagePayload {
	public static final int MAX_MANIFESTS = 128;
	private final long syncId;
	private final long epoch;
	private final int nextCursor;
	private final int totalCount;
	private final List<MapPatchManifest> manifests;

	public PatchManifestPagePayload(long syncId, long epoch, int nextCursor, int totalCount, List<MapPatchManifest> manifests) {
		if (syncId <= 0L || nextCursor < 0 || totalCount < 0 || manifests == null || manifests.size() > MAX_MANIFESTS
				|| nextCursor > totalCount) throw new IllegalArgumentException("Invalid patch manifest page");
		this.syncId = syncId;
		this.epoch = epoch;
		this.nextCursor = nextCursor;
		this.totalCount = totalCount;
		this.manifests = List.copyOf(manifests);
	}

	public static PatchManifestPagePayload read(FriendlyByteBuf buffer) {
		long syncId = buffer.readVarLong();
		long epoch = buffer.readLong();
		int nextCursor = buffer.readVarInt();
		int totalCount = buffer.readVarInt();
		int count = buffer.readVarInt();
		if (count < 0 || count > MAX_MANIFESTS) throw new IllegalArgumentException("Invalid patch manifest count: " + count);
		List<MapPatchManifest> manifests = new ArrayList<>(count);
		for (int index = 0; index < count; index++) manifests.add(MapPatchPayloadCodec.readManifest(buffer));
		return new PatchManifestPagePayload(syncId, epoch, nextCursor, totalCount, manifests);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeVarLong(syncId);
		buffer.writeLong(epoch);
		buffer.writeVarInt(nextCursor);
		buffer.writeVarInt(totalCount);
		buffer.writeVarInt(manifests.size());
		for (MapPatchManifest manifest : manifests) MapPatchPayloadCodec.writeManifest(buffer, manifest);
	}

	public long syncId() { return syncId; }
	public long epoch() { return epoch; }
	public int nextCursor() { return nextCursor; }
	public int totalCount() { return totalCount; }
	public List<MapPatchManifest> manifests() { return manifests; }
	public boolean complete() { return nextCursor >= totalCount; }
}
