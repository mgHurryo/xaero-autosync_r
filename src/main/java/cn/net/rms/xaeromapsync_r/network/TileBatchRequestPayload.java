package cn.net.rms.xaeromapsync_r.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

public final class TileBatchRequestPayload {
	public static final int MAX_REQUESTS = 64;
	private final List<TileRequestPayload> requests;

	public TileBatchRequestPayload(Collection<TileRequestPayload> requests) {
		if (requests == null || requests.isEmpty() || requests.size() > MAX_REQUESTS) {
			throw new IllegalArgumentException("Invalid tile batch request count: "
					+ (requests == null ? 0 : requests.size()));
		}
		this.requests = List.copyOf(requests);
	}

	public static TileBatchRequestPayload read(FriendlyByteBuf buffer) {
		int count = buffer.readVarInt();
		if (count < 1 || count > MAX_REQUESTS) {
			throw new IllegalArgumentException("Invalid tile batch request count: " + count);
		}
		List<TileRequestPayload> requests = new ArrayList<>(count);
		for (int index = 0; index < count; index++) requests.add(TileRequestPayload.read(buffer));
		return new TileBatchRequestPayload(requests);
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeVarInt(requests.size());
		for (TileRequestPayload request : requests) request.write(buffer);
	}

	public List<TileRequestPayload> requests() {
		return requests;
	}
}
