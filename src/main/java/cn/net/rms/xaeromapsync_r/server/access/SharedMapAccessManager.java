package cn.net.rms.xaeromapsync_r.server.access;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class SharedMapAccessManager {
	private final RegionAccessStore regions = new RegionAccessStore();
	private final SharedMapAuditLog audit = new SharedMapAuditLog();

	public void load(MinecraftServer server) {
		Path directory = directory(server);
		audit.start(directory.resolve("access_audit.jsonl"));
		try {
			regions.load(directory.resolve("region_permissions.json"));
			XaeroMapsync_r.LOGGER.info("Loaded {} shared map region access rules", regions.size());
		} catch (IOException | RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to load shared map region access rules", exception);
		}
	}

	public void save(MinecraftServer server) {
		try {
			regions.save(directory(server).resolve("region_permissions.json"));
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to save shared map region access rules", exception);
		}
	}

	public RegionAccessStore regions() {
		return regions;
	}

	public SharedMapAuditLog audit() {
		return audit;
	}

	private static Path directory(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("xaero-mapsync_r");
	}
}
