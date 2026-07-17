package cn.net.rms.xaeromapsync_r.xaero;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;

final class XaeroCompatibilityTest {
	private static final List<String> WORLD_MAP_RELEASES = List.of(
			"1.14.5.2", "1.14.6.1", "1.15.0.1", "1.16.0", "1.16.1", "1.17.0", "1.17.1", "1.17.2",
			"1.17.3", "1.18.0", "1.18.1", "1.18.2", "1.18.3", "1.18.4", "1.18.6", "1.18.7", "1.18.8",
			"1.19.0", "1.19.1", "1.20.0", "1.20.1", "1.20.3.1", "1.20.4.1", "1.20.5", "1.20.6", "1.20.7",
			"1.21.0", "1.21.1", "1.21.2", "1.22.0", "1.23.1", "1.23.2", "1.23.3", "1.24.0", "1.25.1",
			"1.26.0", "1.26.2", "1.26.4", "1.26.5", "1.26.6", "1.27.0", "1.28.0", "1.28.1", "1.28.2",
			"1.28.3", "1.28.4", "1.28.6", "1.28.7", "1.28.8", "1.28.9", "1.29.0", "1.29.1", "1.29.2",
			"1.29.4", "1.29.5", "1.30.0", "1.30.1", "1.30.2", "1.30.3", "1.30.5", "1.31.0", "1.32.0",
			"1.33.0", "1.33.1", "1.34.0", "1.34.1", "1.35.0", "1.36.0", "1.37.0", "1.37.1", "1.37.3",
			"1.37.4", "1.37.7", "1.37.8");
	private static final List<String> MINIMAP_RELEASES = List.of(
			"21.12.5.1", "21.13.0", "21.14.0", "21.14.1", "21.15.0.1", "21.15.1", "21.16.0", "21.17.0.1",
			"21.17.1", "21.17.2", "21.18.0", "21.19.0", "21.20.0", "21.21.0", "21.22.0", "21.22.1",
			"21.22.2", "21.22.3", "21.22.5", "21.22.6", "21.23.0", "21.23.1", "22.1.0", "22.1.1", "22.1.2",
			"22.2.0.1", "22.3.0", "22.3.1.1", "22.4.0", "22.5.0", "22.6.0", "22.6.1", "22.7.0", "22.8.0",
			"22.8.1", "22.8.2", "22.9.0", "22.9.2", "22.9.3", "22.10.0", "22.11.1", "22.12.0", "22.13.0",
			"22.13.1", "22.13.2", "22.14.0", "22.15.0", "22.15.1", "22.16.0", "22.16.1", "22.16.2",
			"22.16.3", "22.17.0", "22.17.1", "23.1.0", "23.2.0", "23.3.0", "23.3.1", "23.3.2", "23.3.3",
			"23.4.0", "23.4.1", "23.4.2", "23.4.3", "23.4.4", "23.5.0", "23.6.0", "23.6.1", "23.6.2",
			"23.6.3", "23.7.0", "23.8.0", "23.8.2", "23.8.3", "23.8.4", "23.9.0", "23.9.1", "23.9.3",
			"23.9.4", "23.9.7");

	@Test
	void acceptsEveryPublishedWorldMapFabricReleaseForMinecraft1171() {
		assertTrue(WORLD_MAP_RELEASES.size() == 74);
		WORLD_MAP_RELEASES.forEach(version -> assertTrue(XaeroCompatibility.supportsWorldMap(version), version));
	}

	@Test
	void acceptsEveryPublishedMinimapFabricReleaseForMinecraft1171() {
		assertTrue(MINIMAP_RELEASES.size() == 80);
		MINIMAP_RELEASES.forEach(version -> assertTrue(XaeroCompatibility.supportsMinimap(version), version));
	}

	@Test
	void rejectsVersionsOutsideThePublishedMinecraft1171Boundaries() {
		assertFalse(XaeroCompatibility.supportsWorldMap("1.14.5.1"));
		assertFalse(XaeroCompatibility.supportsWorldMap("1.37.9"));
		assertFalse(XaeroCompatibility.supportsMinimap("21.12.5"));
		assertFalse(XaeroCompatibility.supportsMinimap("23.9.8"));
		assertFalse(XaeroCompatibility.supportsWorldMap("1.25.1-fabric"));
		assertFalse(XaeroCompatibility.supportsMinimap(null));
	}

	@Test
	void fabricMetadataAcceptsEveryPublishedFabricRelease() throws IOException, VersionParsingException {
		Path metadataPath = Path.of("src", "main", "resources", "fabric.mod.json");
		JsonObject metadata;
		try (java.io.Reader reader = Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8)) {
			metadata = new Gson().fromJson(reader, JsonObject.class);
		}
		JsonObject suggests = metadata.getAsJsonObject("suggests");
		VersionPredicate worldMap = VersionPredicate.parse(suggests.get("xaeroworldmap").getAsString());
		VersionPredicate minimap = VersionPredicate.parse(suggests.get("xaerominimap").getAsString());

		for (String release : WORLD_MAP_RELEASES) assertTrue(worldMap.test(Version.parse(release)), release);
		for (String release : MINIMAP_RELEASES) assertTrue(minimap.test(Version.parse(release)), release);
		assertFalse(worldMap.test(Version.parse("1.14.5.1")));
		assertFalse(worldMap.test(Version.parse("1.37.9")));
		assertFalse(minimap.test(Version.parse("21.12.5")));
		assertFalse(minimap.test(Version.parse("23.9.8")));
	}
}
