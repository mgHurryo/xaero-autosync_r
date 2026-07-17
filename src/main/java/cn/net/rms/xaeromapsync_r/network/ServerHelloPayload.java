package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.config.SharedMapConfig;
import cn.net.rms.xaeromapsync_r.config.SharedMapProtocolDefaults;
import net.minecraft.network.FriendlyByteBuf;

public final class ServerHelloPayload {
	private final int protocolVersion;
	private final int mapFormatVersion;
	private final String xaeroAdapterVersion;
	private final String compression;
	private final int maxPacketBytes;
	private final boolean accepted;
	private final String message;

	public ServerHelloPayload(int protocolVersion, int mapFormatVersion, String xaeroAdapterVersion, String compression, int maxPacketBytes, boolean accepted, String message) {
		this.protocolVersion = protocolVersion;
		this.mapFormatVersion = mapFormatVersion;
		this.xaeroAdapterVersion = xaeroAdapterVersion;
		this.compression = compression;
		this.maxPacketBytes = maxPacketBytes;
		this.accepted = accepted;
		this.message = message;
	}

	public static ServerHelloPayload from(ClientHelloPayload clientHello) {
		boolean accepted = clientHello.isCompatible();
		String message = accepted ? "Shared map sync enabled" : "Incompatible shared map protocol or map format";
		return new ServerHelloPayload(
				SharedMapConfig.protocolVersion(),
				SharedMapConfig.mapFormatVersion(),
				SharedMapProtocolDefaults.XAERO_ADAPTER_VERSION,
				SharedMapConfig.compression(),
				SharedMapConfig.maxPacketBytes(),
				accepted,
				message);
	}

	public static ServerHelloPayload read(FriendlyByteBuf buffer) {
		return new ServerHelloPayload(
				buffer.readVarInt(),
				buffer.readVarInt(),
				buffer.readUtf(64),
				buffer.readUtf(32),
				buffer.readVarInt(),
				buffer.readBoolean(),
				buffer.readUtf(256));
	}

	public void write(FriendlyByteBuf buffer) {
		buffer.writeVarInt(protocolVersion);
		buffer.writeVarInt(mapFormatVersion);
		buffer.writeUtf(xaeroAdapterVersion);
		buffer.writeUtf(compression);
		buffer.writeVarInt(maxPacketBytes);
		buffer.writeBoolean(accepted);
		buffer.writeUtf(message);
	}

	public boolean accepted() {
		return accepted;
	}

	public int protocolVersion() {
		return protocolVersion;
	}

	public int mapFormatVersion() {
		return mapFormatVersion;
	}

	public String xaeroAdapterVersion() {
		return xaeroAdapterVersion;
	}

	public String compression() {
		return compression;
	}

	public int maxPacketBytes() {
		return maxPacketBytes;
	}

	public String message() {
		return message;
	}
}
