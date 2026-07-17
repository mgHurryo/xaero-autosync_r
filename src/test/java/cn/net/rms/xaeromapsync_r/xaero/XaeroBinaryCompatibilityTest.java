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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class XaeroBinaryCompatibilityTest {
	private static final Path REFERENCE_DIRECTORY = Path.of("xaeromap-origin");
	private static final List<String> WORLD_MAP_CONTRACT_JARS = List.of(
			"XaerosWorldMap_1.14.5.2_Fabric_1.17.1.jar",
			"XaerosWorldMap_1.17.0_Fabric_1.17.1.jar",
			"XaerosWorldMap_1.18.0_Fabric_1.17.1.jar",
			"XaerosWorldMap_1.18.1_Fabric_1.17.1.jar",
			"XaerosWorldMap_1.20.0_Fabric_1.17.1.jar",
			"XaerosWorldMap_1.21.0_Fabric_1.17.1.jar",
			"XaerosWorldMap_1.29.4_Fabric_1.17.1.jar",
			"XaerosWorldMap_1.30.0_Fabric_1.17.1.jar",
			"XaerosWorldMap_1.30.3_Fabric_1.17.1.jar",
			"XaerosWorldMap_1.37.8_Fabric_1.17.1.jar");
	private static final List<String> MINIMAP_CONTRACT_JARS = List.of(
			"Xaeros_Minimap_21.12.5.1_Fabric_1.17.1.jar",
			"Xaeros_Minimap_23.9.7_Fabric_1.17.1.jar");

	@Test
	void everyWorldMapSignatureFamilyMatchesAnAdaptiveRuntimeContract() throws IOException {
		for (String fileName : WORLD_MAP_CONTRACT_JARS) verifyWorldMapContract(referenceJar(fileName));
	}

	@Test
	void earliestAndLatestMinimapReleasesMatchTheWaypointContract() throws IOException {
		for (String fileName : MINIMAP_CONTRACT_JARS) verifyMinimapContract(referenceJar(fileName));
	}

	private static void verifyWorldMapContract(Path jar) throws IOException {
		Signature session = signature(jar, "xaero/map/WorldMapSession.class");
		assertTrue(session.hasMethod("getCurrentSession", "()Lxaero/map/WorldMapSession;"), jar.toString());
		assertTrue(session.hasMethod("getMapProcessor", "()Lxaero/map/MapProcessor;"), jar.toString());

		Signature processor = signature(jar, "xaero/map/MapProcessor.class");
		assertTrue(processor.hasMethod("getMapRegion", "(IIZ)Lxaero/map/region/MapRegion;"), jar.toString());
		assertTrue(processor.hasMethod("getMapTile", "(II)Lxaero/map/region/MapTile;")
				|| processor.hasMethod("getMapTile", "(III)Lxaero/map/region/MapTile;"), jar.toString());

		Signature chunk = signature(jar, "xaero/map/region/MapTileChunk.class");
		boolean hasShapeCache = hasClass(jar, "xaero/map/cache/BlockStateShortShapeCache.class");
		assertTrue(hasShapeCache
				? chunk.hasMethod("setTile", "(IILxaero/map/region/MapTile;Lxaero/map/cache/BlockStateShortShapeCache;)V")
				: chunk.hasMethod("setTile", "(IILxaero/map/region/MapTile;)V"), jar.toString());

		boolean legacyBiome = hasClass(jar, "xaero/map/biome/BiomeKey.class");
		String biomeDescriptor = legacyBiome ? "Lxaero/map/biome/BiomeKey;" : "Lnet/minecraft/class_5321;";
		Signature block = signature(jar, "xaero/map/region/MapBlock.class");
		assertTrue(block.hasMethod("write", "(Lnet/minecraft/class_2680;II" + biomeDescriptor + "BZZ)V")
				|| block.hasMethod("write", "(Lnet/minecraft/class_2680;II[I" + biomeDescriptor + "BZZ)V")
				|| block.hasMethod("write", "(Lnet/minecraft/class_2680;I[I" + biomeDescriptor + "BZZ)V"),
				jar.toString());

		Signature overlay = signature(jar, "xaero/map/region/Overlay.class");
		assertTrue(overlay.hasMethod("<init>", "(Lnet/minecraft/class_2680;FBZ)V")
				|| overlay.hasMethod("<init>", "(Lnet/minecraft/class_2680;IIFBZ)V")
				|| overlay.hasMethod("<init>", "(Lnet/minecraft/class_2680;[IIBZ)V")
				|| overlay.hasMethod("<init>", "(Lnet/minecraft/class_2680;BZ)V"), jar.toString());

		Signature dimension = signature(jar, "xaero/map/world/MapDimension.class");
		assertTrue(dimension.hasMethod("getDetectedRegions", "()Ljava/util/Hashtable;")
				|| dimension.hasMethod("getWorldSaveDetectedRegions", "()Ljava/lang/Iterable;"), jar.toString());
	}

	private static void verifyMinimapContract(Path jar) throws IOException {
		Signature session = signature(jar, "xaero/common/XaeroMinimapSession.class");
		assertTrue(session.hasMethod("getCurrentSession", "()Lxaero/common/XaeroMinimapSession;"), jar.toString());
		assertTrue(session.hasMethod("getWaypointsManager",
				"()Lxaero/common/minimap/waypoints/WaypointsManager;"), jar.toString());
		Signature main = signature(jar, "xaero/common/AXaeroMinimap.class");
		assertTrue(session.hasMethod("getModMain", "()Lxaero/common/AXaeroMinimap;")
				|| main.hasField("INSTANCE", "Lxaero/common/AXaeroMinimap;"), jar.toString());

		Signature waypoint = signature(jar, "xaero/common/minimap/waypoints/Waypoint.class");
		assertTrue(waypoint.hasMethod("<init>", "(IIILjava/lang/String;Ljava/lang/String;I)V"), jar.toString());
		assertTrue(waypoint.hasMethod("isServerWaypoint", "()Z"), jar.toString());
		Signature screen = signature(jar, "xaero/common/gui/GuiWaypoints.class");
		assertTrue(screen.hasMethod("getSelectedWaypointsList", "()Ljava/util/ArrayList;"), jar.toString());
		assertTrue(screen.hasField("displayedWorld", "Lxaero/common/minimap/waypoints/WaypointWorld;"), jar.toString());
		assertTrue(screen.hasField("selectedListSet", "Ljava/util/concurrent/ConcurrentSkipListSet;"), jar.toString());
		Signature list = signature(jar, "xaero/common/gui/GuiWaypoints$List.class");
		assertTrue(list.hasMethod("drawWaypointSlot",
				"(Lnet/minecraft/class_4587;Lxaero/common/minimap/waypoints/Waypoint;II)V"), jar.toString());
	}

	private static Path referenceJar(String fileName) {
		Path jar = REFERENCE_DIRECTORY.resolve(fileName);
		assertTrue(Files.isRegularFile(jar), "Missing Xaero compatibility fixture " + jar);
		verifyFabricFixture(jar, fileName.startsWith("XaerosWorldMap_") ? "xaeroworldmap" : "xaerominimap");
		return jar;
	}

	private static void verifyFabricFixture(Path jarPath, String expectedModId) {
		try (JarFile jar = new JarFile(jarPath.toFile())) {
			java.util.jar.JarEntry metadataEntry = jar.getJarEntry("fabric.mod.json");
			assertNotNull(metadataEntry, "Missing Fabric metadata in " + jarPath);
			assertNull(jar.getJarEntry("META-INF/mods.toml"), "Forge metadata found in " + jarPath);
			try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(metadataEntry),
					StandardCharsets.UTF_8)) {
				JsonObject metadata = new Gson().fromJson(reader, JsonObject.class);
				assertEquals(expectedModId, metadata.get("id").getAsString(), jarPath.toString());
				assertTrue(jarPath.getFileName().toString().contains("_" + metadata.get("version").getAsString()
						+ "_Fabric_1.17.1.jar"), jarPath.toString());
				JsonElement minecraft = metadata.getAsJsonObject("depends").get("minecraft");
				boolean supportsMinecraft1171 = false;
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

	private static final class Signature extends ClassVisitor {
		private final Set<String> fields = new HashSet<>();
		private final Set<String> methods = new HashSet<>();

		private Signature() {
			super(Opcodes.ASM9);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			fields.add(name + descriptor);
			return null;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				String[] exceptions) {
			methods.add(name + descriptor);
			return null;
		}

		private boolean hasField(String name, String descriptor) {
			return fields.contains(name + descriptor);
		}

		private boolean hasMethod(String name, String descriptor) {
			return methods.contains(name + descriptor);
		}

	}
}
