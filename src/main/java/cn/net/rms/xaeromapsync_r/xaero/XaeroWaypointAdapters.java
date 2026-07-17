package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;

public final class XaeroWaypointAdapters {
	public static final String SUPPORTED_MINIMAP_VERSION = "22.11.1";

	private XaeroWaypointAdapters() {
	}

	public static XaeroWaypointAdapter create() {
		FabricLoader loader = FabricLoader.getInstance();
		ModContainer minimap = loader.getModContainer("xaerominimap").orElse(null);
		if (minimap == null) {
			return unavailable("Xaero's Minimap is not loaded");
		}
		String version = minimap.getMetadata().getVersion().getFriendlyString();
		if (!SUPPORTED_MINIMAP_VERSION.equals(version)) {
			return unavailable("Unsupported Xaero's Minimap version " + version + "; required " + SUPPORTED_MINIMAP_VERSION);
		}

		try {
			ReflectiveXaeroWaypointBridge bridge = new ReflectiveXaeroWaypointBridge(XaeroWaypointAdapters.class.getClassLoader());
			Minecraft minecraft = Minecraft.getInstance();
			XaeroMapsync_r.LOGGER.info("Xaero waypoint reflection adapter enabled for Minimap {}", version);
			return new ReflectiveXaeroWaypointAdapter(
				bridge,
				() -> minecraft.level == null ? null : minecraft.level.dimension().location().toString(),
				() -> minecraft.player == null ? null : minecraft.player.getUUID(),
				minecraft::isSameThread
			);
		} catch (ReflectiveOperationException | LinkageError exception) {
			String reason = "Xaero's Minimap " + version + " reflection contract validation failed";
			XaeroMapsync_r.LOGGER.error(reason + "; waypoint synchronization disabled", exception);
			return new UnavailableXaeroWaypointAdapter(reason);
		}
	}

	private static XaeroWaypointAdapter unavailable(String reason) {
		XaeroMapsync_r.LOGGER.warn(reason + "; waypoint synchronization disabled");
		return new UnavailableXaeroWaypointAdapter(reason);
	}
}
