package cn.net.rms.xaeromapsync_r.xaero;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class XaeroBinaryCompatibilityTest {
	private static final String FIXTURE_DIRECTORY_ENV = "XAERO_FIXTURE_DIRECTORY";
	private static final String EXPECTED_WORLD_FIXTURES_ENV = "XAERO_EXPECTED_WORLD_FIXTURES";
	private static final String EXPECTED_MINIMAP_FIXTURES_ENV = "XAERO_EXPECTED_MINIMAP_FIXTURES";
	private static final Pattern WORLD_MAP_FIXTURE = Pattern.compile(
			"XaerosWorldMap_([0-9.]+)_Fabric_1\\.17\\.1\\.jar");
	private static final Pattern MINIMAP_FIXTURE = Pattern.compile(
			"Xaeros_Minimap_([0-9.]+)_Fabric_1\\.17\\.1\\.jar");

	@Test
	void everyWorldMapFixtureMatchesTheCompleteAdaptiveRuntimeContract() throws IOException {
		for (Path jar : discoverFixtures(WORLD_MAP_FIXTURE, EXPECTED_WORLD_FIXTURES_ENV, 12,
				XaeroCompatibilityTest.WORLD_MAP_RELEASES)) {
			verifyFabricFixture(jar, WORLD_MAP_FIXTURE, "xaeroworldmap");
			verifyWorldMapContract(jar);
		}
	}

	@Test
	void everyMinimapFixtureMatchesTheCompleteWaypointContract() throws IOException {
		for (Path jar : discoverFixtures(MINIMAP_FIXTURE, EXPECTED_MINIMAP_FIXTURES_ENV, 3,
				XaeroCompatibilityTest.MINIMAP_RELEASES)) {
			verifyFabricFixture(jar, MINIMAP_FIXTURE, "xaerominimap");
			verifyMinimapContract(jar);
		}
	}

	private static List<Path> discoverFixtures(Pattern filePattern, String expectedCountEnvironment,
			int defaultExpectedCount, List<String> publishedVersions) throws IOException {
		String configuredDirectory = System.getenv(FIXTURE_DIRECTORY_ENV);
		Path directory = Path.of(configuredDirectory == null || configuredDirectory.isBlank()
				? "xaeromap-origin" : configuredDirectory);
		assertTrue(Files.isDirectory(directory), "Missing Xaero fixture directory " + directory);

		List<Path> fixtures;
		try (Stream<Path> entries = Files.list(directory)) {
			fixtures = entries.filter(Files::isRegularFile)
					.filter(path -> filePattern.matcher(path.getFileName().toString()).matches())
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.toList();
		}
		int expectedCount = expectedFixtureCount(expectedCountEnvironment, defaultExpectedCount);
		assertEquals(expectedCount, fixtures.size(),
				"Unexpected Xaero fixture count in " + directory + ": " + fixtureNames(fixtures));
		if (expectedCount == publishedVersions.size()) {
			Set<String> fixtureVersions = fixtures.stream()
					.map(path -> fixtureVersion(filePattern, path))
					.collect(java.util.stream.Collectors.toSet());
			assertEquals(Set.copyOf(publishedVersions), fixtureVersions,
					"Xaero full-matrix fixture versions in " + directory);
		}
		return fixtures;
	}

	private static String fixtureVersion(Pattern filePattern, Path fixture) {
		java.util.regex.Matcher matcher = filePattern.matcher(fixture.getFileName().toString());
		if (!matcher.matches()) throw new AssertionError("Invalid Xaero fixture name " + fixture);
		return matcher.group(1);
	}

	private static int expectedFixtureCount(String environmentName, int defaultValue) {
		String configured = System.getenv(environmentName);
		if (configured == null || configured.isBlank()) return defaultValue;
		try {
			int parsed = Integer.parseInt(configured);
			if (parsed <= 0) throw new NumberFormatException("count must be positive");
			return parsed;
		} catch (NumberFormatException exception) {
			throw new AssertionError(environmentName + " must be a positive integer: " + configured, exception);
		}
	}

	private static String fixtureNames(List<Path> fixtures) {
		return fixtures.stream().map(path -> path.getFileName().toString()).toList().toString();
	}

	private static void verifyWorldMapContract(Path jar) throws IOException {
		Signature session = signature(jar, "xaero/map/WorldMapSession.class");
		assertStaticMethod(jar, session, "getCurrentSession", "()Lxaero/map/WorldMapSession;");
		assertMethod(jar, session, "getMapProcessor", "()Lxaero/map/MapProcessor;");

		Signature processor = signature(jar, "xaero/map/MapProcessor.class");
		assertMethod(jar, processor, "getMapRegion", "(IIZ)Lxaero/map/region/MapRegion;");
		assertMethod(jar, processor, "getTilePool", "()Lxaero/map/pool/MapTilePool;");
		assertMethod(jar, processor, "getMapSaveLoad", "()Lxaero/map/file/MapSaveLoad;");
		assertAnyMethod(jar, processor, "getMapTile",
				"(II)Lxaero/map/region/MapTile;",
				"(III)Lxaero/map/region/MapTile;");
		assertMethod(jar, processor, "getCurrentDimension", "()Ljava/lang/String;");
		assertMethod(jar, processor, "getMapWorld", "()Lxaero/map/world/MapWorld;");
		assertMethod(jar, processor, "getDimensionName",
				"(Lnet/minecraft/class_5321;)Ljava/lang/String;");
		assertMethod(jar, processor, "getCurrentWorldId", "()Ljava/lang/String;");
		assertOptionalMethod(jar, processor, "waitForLoadingToFinish", "(Ljava/lang/Runnable;)V");

		boolean hasShapeCache = hasClass(jar, "xaero/map/cache/BlockStateShortShapeCache.class");
		if (hasShapeCache) {
			assertOptionalMethod(jar, processor, "getBlockStateShortShapeCache",
					"()Lxaero/map/cache/BlockStateShortShapeCache;");
		}

		Signature mapWorld = signature(jar, "xaero/map/world/MapWorld.class");
		assertMethod(jar, mapWorld, "getCurrentDimension", "()Lxaero/map/world/MapDimension;");
		assertMethod(jar, mapWorld, "getCurrentDimensionId", "()Lnet/minecraft/class_5321;");
		assertMethod(jar, mapWorld, "getDimension",
				"(Lnet/minecraft/class_5321;)Lxaero/map/world/MapDimension;");
		assertMethod(jar, mapWorld, "getCurrentMultiworld", "()Ljava/lang/String;");
		assertMethod(jar, mapWorld, "getMainId", "()Ljava/lang/String;");

		Signature dimension = signature(jar, "xaero/map/world/MapDimension.class");
		assertAnyMethod(jar, dimension, "region discovery",
				"getDetectedRegions()Ljava/util/Hashtable;",
				"getWorldSaveDetectedRegions()Ljava/lang/Iterable;");
		assertMethod(jar, dimension, "getMainFolderPath", "()Ljava/nio/file/Path;");
		assertMethod(jar, dimension, "getCurrentMultiworld", "()Ljava/lang/String;");

		Signature detection = signature(jar, "xaero/map/file/RegionDetection.class");
		assertMethod(jar, detection, "getRegionX", "()I");
		assertMethod(jar, detection, "getRegionZ", "()I");

		Signature core = signature(jar, "xaero/map/core/XaeroWorldMapCore.class");
		assertStaticMethod(jar, core, "chunkUpdateCallback", "(II)V");

		Signature region = signature(jar, "xaero/map/region/MapRegion.class");
		assertMethod(jar, region, "getChunk", "(II)Lxaero/map/region/MapTileChunk;");
		assertMethod(jar, region, "setChunk", "(IILxaero/map/region/MapTileChunk;)V");
		assertMethod(jar, region, "getLoadState", "()B");
		assertMethod(jar, region, "isBeingWritten", "()Z");
		assertMethod(jar, region, "setBeingWritten", "(Z)V");
		assertMethod(jar, region, "isWritingPaused", "()Z");
		assertMethod(jar, region, "isRefreshing", "()Z");
		assertMethod(jar, region, "requestRefresh", "(Lxaero/map/MapProcessor;)V");
		assertMethod(jar, region, "cancelRefresh", "(Lxaero/map/MapProcessor;)V");
		assertField(jar, region, "writerThreadPauseSync", "Ljava/lang/Object;");
		assertOptionalField(jar, region, "hasHadTerrain", "Z");

		Signature leveledRegion = signature(jar, "xaero/map/region/LeveledRegion.class");
		assertField(jar, leveledRegion, "lastSaveTime", "J");
		assertMethod(jar, leveledRegion, "shouldCache", "()Z");
		assertMethod(jar, leveledRegion, "reloadHasBeenRequested", "()Z");
		assertMethod(jar, leveledRegion, "recacheHasBeenRequested", "()Z");
		assertMethod(jar, leveledRegion, "setRecacheHasBeenRequested", "(ZLjava/lang/String;)V");
		assertMethod(jar, leveledRegion, "setShouldCache", "(ZLjava/lang/String;)V");

		Signature chunk = signature(jar, "xaero/map/region/MapTileChunk.class");
		assertMethod(jar, chunk, "<init>", "(Lxaero/map/region/MapRegion;II)V");
		assertMethod(jar, chunk, "getLoadState", "()I");
		assertMethod(jar, chunk, "setLoadState", "(B)V");
		assertMethod(jar, chunk, "getTile", "(II)Lxaero/map/region/MapTile;");
		String legacySetTile = "(IILxaero/map/region/MapTile;)V";
		String cachedSetTile = "(IILxaero/map/region/MapTile;Lxaero/map/cache/BlockStateShortShapeCache;)V";
		assertAnyMethod(jar, chunk, "setTile", legacySetTile, cachedSetTile);
		if (!chunk.hasMethod("setTile", legacySetTile)) {
			assertTrue(hasShapeCache, "Cached setTile requires BlockStateShortShapeCache in " + jar);
			assertMethod(jar, processor, "getBlockStateShortShapeCache",
					"()Lxaero/map/cache/BlockStateShortShapeCache;");
		}
		assertMethod(jar, chunk, "wasChanged", "()Z");
		assertMethod(jar, chunk, "setChanged", "(Z)V");
		assertOptionalMethod(jar, chunk, "setHasHadTerrain", "()V");
		assertField(jar, chunk, "includeInSave", "Z");
		assertOptionalField(jar, chunk, "hasHadTerrain", "Z");

		Signature tilePool = signature(jar, "xaero/map/pool/MapTilePool.class");
		assertMethod(jar, tilePool, "get", "(Ljava/lang/String;II)Lxaero/map/region/MapTile;");

		Signature tile = signature(jar, "xaero/map/region/MapTile.class");
		assertMethod(jar, tile, "setBlock", "(IILxaero/map/region/MapBlock;)V");
		assertMethod(jar, tile, "setLoaded", "(Z)V");
		assertMethod(jar, tile, "setWrittenOnce", "(Z)V");
		assertOptionalMethod(jar, tile, "setWorldInterpretationVersion", "(I)V");
		assertMethod(jar, tile, "isLoaded", "()Z");
		assertMethod(jar, tile, "wasWrittenOnce", "()Z");
		assertMethod(jar, tile, "getBlock", "(II)Lxaero/map/region/MapBlock;");

		Signature pixel = signature(jar, "xaero/map/region/MapPixel.class");
		assertMethod(jar, pixel, "getState", "()Lnet/minecraft/class_2680;");
		assertField(jar, pixel, "light", "B");
		assertField(jar, pixel, "glowing", "Z");

		boolean legacyBiome = hasClass(jar, "xaero/map/biome/BiomeKey.class");
		String biomeDescriptor = legacyBiome ? "Lxaero/map/biome/BiomeKey;" : "Lnet/minecraft/class_5321;";
		Signature block = signature(jar, "xaero/map/region/MapBlock.class");
		assertMethod(jar, block, "<init>", "()V");
		assertMethod(jar, block, "getHeight", "()I");
		assertOptionalMethod(jar, block, "getTopHeight", "()I");
		assertMethod(jar, block, "getBiome", "()" + biomeDescriptor);
		assertMethod(jar, block, "getOverlays", "()Ljava/util/ArrayList;");
		assertOptionalMethod(jar, block, "isCaveBlock", "()Z");
		assertAnyMethod(jar, block, "write",
				"(Lnet/minecraft/class_2680;II" + biomeDescriptor + "BZZ)V",
				"(Lnet/minecraft/class_2680;II[I" + biomeDescriptor + "BZZ)V",
				"(Lnet/minecraft/class_2680;I[I" + biomeDescriptor + "BZZ)V");
		assertMethod(jar, block, "setSlopeUnknown", "(Z)V");
		assertMethod(jar, block, "addOverlay", "(Lxaero/map/region/Overlay;)V");

		Signature overlay = signature(jar, "xaero/map/region/Overlay.class");
		assertAnyMethod(jar, overlay, "<init>",
				"(Lnet/minecraft/class_2680;FBZ)V",
				"(Lnet/minecraft/class_2680;IIFBZ)V",
				"(Lnet/minecraft/class_2680;[IIBZ)V",
				"(Lnet/minecraft/class_2680;BZ)V");
		assertOptionalMethod(jar, overlay, "getTransparency", "()F");
		assertMethod(jar, overlay, "getOpacity", "()I");
		assertMethod(jar, overlay, "increaseOpacity", "(I)V");

		Signature saveLoad = signature(jar, "xaero/map/file/MapSaveLoad.class");
		assertMethod(jar, saveLoad, "getMainFolder",
				"(Ljava/lang/String;Ljava/lang/String;)Ljava/nio/file/Path;");
		assertMethod(jar, saveLoad, "requestLoad",
				"(Lxaero/map/region/MapRegion;Ljava/lang/String;)V");
		assertField(jar, saveLoad, "toLoad", "Ljava/util/ArrayList;");
		assertMethod(jar, saveLoad, "toCacheContains", "(Lxaero/map/region/LeveledRegion;)Z");
		assertMethod(jar, saveLoad, "removeToCache", "(Lxaero/map/region/LeveledRegion;)V");
		assertMethod(jar, saveLoad, "requestCache", "(Lxaero/map/region/LeveledRegion;)V");
		assertMethod(jar, saveLoad, "getToSave", "()Ljava/util/ArrayList;");

		boolean hasBiomeKeyManager = hasClass(jar, "xaero/map/biome/BiomeKeyManager.class");
		if (hasBiomeKeyManager) {
			assertTrue(legacyBiome, "BiomeKeyManager requires BiomeKey in " + jar);
			assertField(jar, saveLoad, "biomeKeyManager", "Lxaero/map/biome/BiomeKeyManager;");
			Signature biomeManager = signature(jar, "xaero/map/biome/BiomeKeyManager.class");
			assertMethod(jar, biomeManager, "get", "(Ljava/lang/String;)Lxaero/map/biome/BiomeKey;");
		}
		if (legacyBiome) {
			Signature biome = signature(jar, "xaero/map/biome/BiomeKey.class");
			assertMethod(jar, biome, "getIdentifier",
					"(Lnet/minecraft/class_2378;)Lnet/minecraft/class_2960;");
		}
	}

	private static void verifyMinimapContract(Path jar) throws IOException {
		Signature session = signature(jar, "xaero/common/XaeroMinimapSession.class");
		assertPublicStaticMethod(jar, session, "getCurrentSession",
				"()Lxaero/common/XaeroMinimapSession;");
		assertPublicMethod(jar, session, "getWaypointsManager",
				"()Lxaero/common/minimap/waypoints/WaypointsManager;");

		Signature main = signature(jar, "xaero/common/AXaeroMinimap.class");
		if (session.hasNoArgMethod("getModMain")) {
			assertPublicMethod(jar, session, "getModMain", "()Lxaero/common/AXaeroMinimap;");
		} else {
			assertPublicStaticField(jar, main, "INSTANCE", "Lxaero/common/AXaeroMinimap;");
		}

		Signature manager = signature(jar, "xaero/common/minimap/waypoints/WaypointsManager.class");
		assertPublicMethod(jar, manager, "getCurrentWorld",
				"()Lxaero/common/minimap/waypoints/WaypointWorld;");
		assertPublicMethod(jar, manager, "getDimensionKeyForDirectoryName",
				"(Ljava/lang/String;)Lnet/minecraft/class_5321;");

		Signature world = signature(jar, "xaero/common/minimap/waypoints/WaypointWorld.class");
		assertPublicMethod(jar, world, "getCurrentSet",
				"()Lxaero/common/minimap/waypoints/WaypointSet;");
		assertPublicMethod(jar, world, "getSets", "()Ljava/util/HashMap;");
		assertPublicMethod(jar, world, "addSet", "(Ljava/lang/String;)V");
		assertPublicMethod(jar, world, "getId", "()Ljava/lang/String;");

		Signature set = signature(jar, "xaero/common/minimap/waypoints/WaypointSet.class");
		assertPublicMethod(jar, set, "getList", "()Ljava/util/ArrayList;");
		assertPublicMethod(jar, set, "getName", "()Ljava/lang/String;");

		Signature screen = signature(jar, "xaero/common/gui/GuiWaypoints.class");
		assertMethod(jar, screen, "getSelectedWaypointsList", "()Ljava/util/ArrayList;");
		assertField(jar, screen, "displayedWorld", "Lxaero/common/minimap/waypoints/WaypointWorld;");
		assertField(jar, screen, "selectedListSet", "Ljava/util/concurrent/ConcurrentSkipListSet;");

		assertPublicMethod(jar, main, "getSettings", "()Lxaero/common/settings/ModSettings;");
		Signature settings = signature(jar, "xaero/common/settings/ModSettings.class");
		assertPublicMethod(jar, settings, "saveWaypoints",
				"(Lxaero/common/minimap/waypoints/WaypointWorld;)V");

		Signature waypoint = signature(jar, "xaero/common/minimap/waypoints/Waypoint.class");
		assertPublicMethod(jar, waypoint, "<init>", "(IIILjava/lang/String;Ljava/lang/String;I)V");
		assertPublicMethod(jar, waypoint, "getX", "()I");
		assertPublicMethod(jar, waypoint, "getY", "()I");
		assertPublicMethod(jar, waypoint, "getZ", "()I");
		assertPublicMethod(jar, waypoint, "getName", "()Ljava/lang/String;");
		assertPublicMethod(jar, waypoint, "getSymbol", "()Ljava/lang/String;");
		assertPublicMethod(jar, waypoint, "getColor", "()I");
		assertPublicMethod(jar, waypoint, "isServerWaypoint", "()Z");
		assertPublicMethod(jar, waypoint, "setX", "(I)V");
		assertPublicMethod(jar, waypoint, "setY", "(I)V");
		assertPublicMethod(jar, waypoint, "setZ", "(I)V");
		assertPublicMethod(jar, waypoint, "setName", "(Ljava/lang/String;)V");
		assertPublicMethod(jar, waypoint, "setSymbol", "(Ljava/lang/String;)V");
		assertPublicMethod(jar, waypoint, "setColor", "(I)V");

		Signature list = signature(jar, "xaero/common/gui/GuiWaypoints$List.class");
		assertMethod(jar, list, "drawWaypointSlot",
				"(Lnet/minecraft/class_4587;Lxaero/common/minimap/waypoints/Waypoint;II)V");
	}

	private static void verifyFabricFixture(Path jarPath, Pattern filePattern, String expectedModId) {
		try (JarFile jar = new JarFile(jarPath.toFile())) {
			java.util.jar.JarEntry metadataEntry = jar.getJarEntry("fabric.mod.json");
			assertNotNull(metadataEntry, "Missing Fabric metadata in " + jarPath);
			assertNull(jar.getJarEntry("META-INF/mods.toml"), "Forge metadata found in " + jarPath);
			try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(metadataEntry),
					StandardCharsets.UTF_8)) {
				JsonObject metadata = new Gson().fromJson(reader, JsonObject.class);
				assertNotNull(metadata, "Invalid Fabric metadata in " + jarPath);
				assertEquals(expectedModId, metadata.get("id").getAsString(), jarPath.toString());
				java.util.regex.Matcher fileName = filePattern.matcher(jarPath.getFileName().toString());
				assertTrue(fileName.matches(), "Invalid Xaero fixture name " + jarPath);
				assertEquals(fileName.group(1), metadata.get("version").getAsString(), jarPath.toString());

				JsonObject depends = metadata.getAsJsonObject("depends");
				assertNotNull(depends, "Missing Fabric dependencies in " + jarPath);
				JsonElement minecraft = depends.get("minecraft");
				assertNotNull(minecraft, "Missing Minecraft dependency in " + jarPath);
				boolean supportsMinecraft1171 = minecraft.isJsonPrimitive()
						&& "1.17.1".equals(minecraft.getAsString());
				if (minecraft.isJsonArray()) {
					for (JsonElement version : minecraft.getAsJsonArray()) {
						supportsMinecraft1171 |= "1.17.1".equals(version.getAsString());
					}
				}
				assertTrue(supportsMinecraft1171, jarPath.toString());
			}
		} catch (IOException exception) {
			throw new AssertionError("Cannot inspect Xaero Fabric fixture " + jarPath, exception);
		}
	}

	private static boolean hasClass(Path jarPath, String entryName) throws IOException {
		try (JarFile jar = new JarFile(jarPath.toFile())) {
			return jar.getJarEntry(entryName) != null;
		}
	}

	private static Signature signature(Path jarPath, String entryName) throws IOException {
		try (JarFile jar = new JarFile(jarPath.toFile())) {
			java.util.jar.JarEntry entry = jar.getJarEntry(entryName);
			assertNotNull(entry, "Missing " + entryName + " in " + jarPath);
			try (InputStream input = jar.getInputStream(entry)) {
				Signature signature = new Signature();
				new ClassReader(input).accept(signature,
						ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				return signature;
			}
		}
	}

	private static void assertMethod(Path jar, Signature signature, String name, String descriptor) {
		assertTrue(signature.hasMethod(name, descriptor),
				() -> "Missing " + name + descriptor + " in " + jar);
	}

	private static void assertAnyMethod(Path jar, Signature signature, String label, String... members) {
		for (String member : members) {
			int descriptorStart = member.indexOf('(');
			String name = descriptorStart == 0 ? label : member.substring(0, descriptorStart);
			String descriptor = descriptorStart == 0 ? member : member.substring(descriptorStart);
			if (signature.hasMethod(name, descriptor)) return;
		}
		throw new AssertionError("Missing " + label + " contract in " + jar + ": " + List.of(members));
	}

	private static void assertOptionalMethod(Path jar, Signature signature, String name, String descriptor) {
		if (signature.hasMethodParameters(name, descriptor)) assertMethod(jar, signature, name, descriptor);
	}

	private static void assertField(Path jar, Signature signature, String name, String descriptor) {
		assertTrue(signature.hasField(name, descriptor),
				() -> "Missing " + name + descriptor + " in " + jar);
	}

	private static void assertOptionalField(Path jar, Signature signature, String name, String descriptor) {
		if (signature.hasFieldName(name)) assertField(jar, signature, name, descriptor);
	}

	private static void assertStaticMethod(Path jar, Signature signature, String name, String descriptor) {
		assertMethod(jar, signature, name, descriptor);
		assertTrue((signature.methodAccess(name, descriptor) & Opcodes.ACC_STATIC) != 0,
				() -> name + descriptor + " must be static in " + jar);
	}

	private static void assertPublicMethod(Path jar, Signature signature, String name, String descriptor) {
		assertMethod(jar, signature, name, descriptor);
		assertTrue((signature.methodAccess(name, descriptor) & Opcodes.ACC_PUBLIC) != 0,
				() -> name + descriptor + " must be public in " + jar);
	}

	private static void assertPublicStaticMethod(Path jar, Signature signature, String name, String descriptor) {
		assertPublicMethod(jar, signature, name, descriptor);
		assertTrue((signature.methodAccess(name, descriptor) & Opcodes.ACC_STATIC) != 0,
				() -> name + descriptor + " must be static in " + jar);
	}

	private static void assertPublicStaticField(Path jar, Signature signature, String name, String descriptor) {
		assertField(jar, signature, name, descriptor);
		int access = signature.fieldAccess(name, descriptor);
		assertTrue((access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_STATIC) != 0,
				() -> name + descriptor + " must be public static in " + jar);
	}

	private static final class Signature extends ClassVisitor {
		private final Map<String, Map<String, Integer>> fields = new HashMap<>();
		private final Map<String, Map<String, Integer>> methods = new HashMap<>();

		private Signature() {
			super(Opcodes.ASM9);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			fields.computeIfAbsent(name, ignored -> new HashMap<>()).put(descriptor, access);
			return null;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				String[] exceptions) {
			methods.computeIfAbsent(name, ignored -> new HashMap<>()).put(descriptor, access);
			return null;
		}

		private boolean hasField(String name, String descriptor) {
			return fields.getOrDefault(name, Map.of()).containsKey(descriptor);
		}

		private boolean hasFieldName(String name) {
			return fields.containsKey(name);
		}

		private int fieldAccess(String name, String descriptor) {
			return fields.getOrDefault(name, Map.of()).getOrDefault(descriptor, 0);
		}

		private boolean hasMethod(String name, String descriptor) {
			return methods.getOrDefault(name, Map.of()).containsKey(descriptor);
		}

		private boolean hasMethodParameters(String name, String descriptor) {
			String parameters = descriptor.substring(0, descriptor.indexOf(')') + 1);
			return methods.getOrDefault(name, Map.of()).keySet().stream()
					.anyMatch(candidate -> candidate.startsWith(parameters));
		}

		private boolean hasNoArgMethod(String name) {
			return methods.getOrDefault(name, Map.of()).keySet().stream()
					.anyMatch(descriptor -> descriptor.startsWith("()"));
		}

		private int methodAccess(String name, String descriptor) {
			return methods.getOrDefault(name, Map.of()).getOrDefault(descriptor, 0);
		}
	}
}
