package cn.net.rms.xaeromapsync_r.xaero;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class ReflectiveXaeroMapAdapterTest {
	private static final Path XAERO_WORLD_MAP_JAR = Path.of(
			"xaeromap-origin", "XaerosWorldMap_1.25.1_Fabric_1.17.1.jar");

	@Test
	void versionGateOnlyAcceptsXaeroWorldMap1251() {
		assertTrue(ReflectiveXaeroMapAdapter.supportsVersion("1.25.1"));
		assertFalse(ReflectiveXaeroMapAdapter.supportsVersion("1.25"));
		assertFalse(ReflectiveXaeroMapAdapter.supportsVersion("1.25.2"));
		assertFalse(ReflectiveXaeroMapAdapter.supportsVersion(null));
	}

	@Test
	void coordinatesUseFloorDivisionForNegativeChunks() {
		assertEquals(-2, ReflectiveXaeroMapAdapter.regionCoordinate(-33));
		assertEquals(-1, ReflectiveXaeroMapAdapter.regionCoordinate(-1));
		assertEquals(0, ReflectiveXaeroMapAdapter.regionCoordinate(31));
		assertEquals(1, ReflectiveXaeroMapAdapter.regionCoordinate(32));
		assertEquals(-2, ReflectiveXaeroMapAdapter.tileChunkCoordinate(-5));
		assertEquals(-1, ReflectiveXaeroMapAdapter.tileChunkCoordinate(-1));
		assertEquals(1, ReflectiveXaeroMapAdapter.tileChunkCoordinate(4));
	}

	@Test
	void inactiveDimensionIsNotAnAdapterFailure() {
		assertFalse(ReflectiveXaeroMapAdapter.isCurrentDimension("minecraft:the_nether", "minecraft:overworld"));
		assertTrue(ReflectiveXaeroMapAdapter.isCurrentDimension("minecraft:overworld", "minecraft:overworld"));
	}

	@Test
	void pinnedXaeroJarStoresLastSaveTimeAsLongField() throws IOException {
		ClassSignature signature = readClassSignature("xaero/map/region/LeveledRegion.class");

		assertEquals("J", signature.fieldDescriptor("lastSaveTime"));
		assertEquals("()Z", signature.methodDescriptor("isAllCachePrepared"));
		assertEquals("(Z)V", signature.methodDescriptor("setAllCachePrepared"));
		assertNull(signature.methodDescriptor("getLastSaveTime"));
		assertNull(signature.methodDescriptor("setLastSaveTime"));
	}

	@Test
	void pinnedXaeroJarExposesNativeLoadingGate() throws IOException {
		ClassSignature signature = readClassSignature("xaero/map/MapProcessor.class");
		ClassSignature saveLoad = readClassSignature("xaero/map/file/MapSaveLoad.class");
		ClassSignature region = readClassSignature("xaero/map/region/MapRegion.class");

		assertEquals("(Ljava/lang/Runnable;)V", signature.methodDescriptor("waitForLoadingToFinish"));
		assertTrue(saveLoad.hasMethodDescriptor("requestLoad", "(Lxaero/map/region/MapRegion;Ljava/lang/String;)V"));
		assertEquals("(Lxaero/map/region/LeveledRegion;)Z", saveLoad.methodDescriptor("toCacheContains"));
		assertEquals("()Z", region.methodDescriptor("isWritingPaused"));
	}

	@Test
	void pinnedXaeroJarUsesDistinctRegionAndChunkLoadStateSignatures() throws IOException {
		ClassSignature region = readClassSignature("xaero/map/region/MapRegion.class");
		ClassSignature chunk = readClassSignature("xaero/map/region/MapTileChunk.class");

		assertEquals("()B", region.methodDescriptor("getLoadState"));
		assertEquals("()I", chunk.methodDescriptor("getLoadState"));
		assertEquals("(B)V", region.methodDescriptor("setLoadState"));
		assertEquals("(B)V", chunk.methodDescriptor("setLoadState"));
	}

	@Test
	void pinnedXaeroJarExposesNativeOverlayConstructionAndInsertion() throws IOException {
		ClassSignature overlay = readClassSignature("xaero/map/region/Overlay.class");
		ClassSignature mapBlock = readClassSignature("xaero/map/region/MapBlock.class");

		assertEquals("(Lnet/minecraft/class_2680;FBZ)V", overlay.methodDescriptor("<init>"));
		assertEquals("(Lxaero/map/region/Overlay;)V", mapBlock.methodDescriptor("addOverlay"));
		assertEquals("(Lnet/minecraft/class_2680;IILxaero/map/biome/BiomeKey;BZZ)V",
				mapBlock.methodDescriptor("write"));
	}

	@Test
	void validTileIsDelegatedToRuntime() {
		AtomicInteger calls = new AtomicInteger();
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(tile -> calls.incrementAndGet());

		adapter.apply(tile(256));

		assertEquals(1, calls.get());
		assertTrue(adapter.isAvailable());
	}

	@Test
	void invalidSurfaceDisablesBeforeRuntimeMutation() {
		AtomicInteger calls = new AtomicInteger();
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(tile -> calls.incrementAndGet());

		adapter.apply(tile(255));

		assertEquals(0, calls.get());
		assertFalse(adapter.isAvailable());
	}

	@Test
	void reflectiveFailureDisablesAdapterAndFutureCallsAreNoOps() {
		AtomicInteger calls = new AtomicInteger();
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(tile -> {
			calls.incrementAndGet();
			throw new ReflectiveOperationException("signature changed");
		});

		adapter.apply(tile(256));
		adapter.apply(tile(256));

		assertEquals(1, calls.get());
		assertFalse(adapter.isAvailable());
	}

	@Test
	void notReadyRuntimeRemainsAvailableForRetry() {
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(tile -> {
			throw new IllegalStateException("Xaero WorldMapSession is not initialized");
		});

		assertFalse(adapter.apply(tile(256)));
		assertTrue(adapter.isAvailable());
	}

	@Test
	void loadingRegionRemainsAvailableForRetry() {
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(tile -> {
			throw new IllegalStateException("Xaero region is not fully loaded: state=1");
		});

		assertFalse(adapter.apply(tile(256)));
		assertTrue(adapter.isAvailable());
	}

	@Test
	void pendingRegionSaveRemainsAvailableForRetry() {
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(tile -> {
			throw new IllegalStateException("Xaero region save is pending");
		});

		assertFalse(adapter.apply(tile(256)));
		assertTrue(adapter.isAvailable());
	}

	@Test
	void pendingRegionCacheRemainsAvailableForRetry() {
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(tile -> {
			throw new IllegalStateException("Xaero region cache is pending");
		});

		assertFalse(adapter.apply(tile(256)));
		assertTrue(adapter.isAvailable());
	}

	private static MapTile tile(int size) {
		return new MapTile("minecraft:overworld", -1, -5, new int[size], new int[size], new int[size], new int[size], 1L);
	}

	private static ClassSignature readClassSignature(String classEntry) throws IOException {
		assertTrue(Files.isRegularFile(XAERO_WORLD_MAP_JAR), "Missing pinned Xaero World Map test jar");
		try (java.util.jar.JarFile jar = new java.util.jar.JarFile(XAERO_WORLD_MAP_JAR.toFile())) {
			java.util.jar.JarEntry entry = jar.getJarEntry(classEntry);
			assertNotNull(entry, "Missing class in pinned Xaero World Map jar: " + classEntry);
			try (InputStream input = jar.getInputStream(entry)) {
				ClassSignature signature = new ClassSignature();
				new ClassReader(input).accept(signature, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				return signature;
			}
		}
	}

	private static final class ClassSignature extends ClassVisitor {
		private final java.util.Map<String, String> fields = new java.util.HashMap<>();
		private final java.util.Map<String, String> methods = new java.util.HashMap<>();
		private final java.util.Set<String> methodSignatures = new java.util.HashSet<>();

		private ClassSignature() {
			super(Opcodes.ASM9);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			fields.put(name, descriptor);
			return null;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			methods.put(name, descriptor);
			methodSignatures.add(name + descriptor);
			return null;
		}

		private String fieldDescriptor(String name) {
			return fields.get(name);
		}

		private String methodDescriptor(String name) {
			return methods.get(name);
		}

		private boolean hasMethodDescriptor(String name, String descriptor) {
			return methodSignatures.contains(name + descriptor);
		}
	}
}
