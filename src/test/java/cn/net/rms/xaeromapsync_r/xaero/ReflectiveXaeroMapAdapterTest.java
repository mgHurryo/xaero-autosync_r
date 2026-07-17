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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
	@org.junit.jupiter.api.io.TempDir Path tempDirectory;

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
	void terminalRegionStatesAreRequeuedEvenWithStaleReloadFlag() {
		assertTrue(ReflectiveXaeroMapAdapter.shouldRequestRegionLoad((byte) 0, true, false, false));
		assertTrue(ReflectiveXaeroMapAdapter.shouldRequestRegionLoad((byte) 4, true, false, false));
		assertFalse(ReflectiveXaeroMapAdapter.shouldRequestRegionLoad((byte) 0, true, true, false));
		assertFalse(ReflectiveXaeroMapAdapter.shouldRequestRegionLoad((byte) 4, false, false, true));
		assertFalse(ReflectiveXaeroMapAdapter.shouldRequestRegionLoad((byte) 1, false, false, false));
		assertFalse(ReflectiveXaeroMapAdapter.shouldRequestRegionLoad((byte) 2, false, false, false));
		assertFalse(ReflectiveXaeroMapAdapter.shouldRequestRegionLoad((byte) 3, false, false, false));
	}

	@Test
	void inactiveDimensionIsNotAnAdapterFailure() {
		assertFalse(ReflectiveXaeroMapAdapter.isCurrentDimension("minecraft:the_nether", "minecraft:overworld"));
		assertTrue(ReflectiveXaeroMapAdapter.isCurrentDimension("minecraft:overworld", "minecraft:overworld"));
	}

	@Test
	void parsesOnlyXaeroRegionZipNamesForArchiveFallback() {
		assertEquals(new XaeroMapAdapter.LocalRegion(-12, 34),
				ReflectiveXaeroMapAdapter.regionFromFileName("-12_34.zip").orElseThrow());
		assertTrue(ReflectiveXaeroMapAdapter.regionFromFileName("cache_1_2.zip").isEmpty());
		assertTrue(ReflectiveXaeroMapAdapter.regionFromFileName("1_2.zip.tmp").isEmpty());
		assertTrue(ReflectiveXaeroMapAdapter.regionFromFileName(null).isEmpty());
	}

	@Test
	void archiveFolderCandidatesDoNotDuplicateXaeroMultiworldPrefix() {
		Path root = Path.of("XaeroWorldMap", "server", "null");
		assertEquals(List.of(root.resolve("mw$default")),
				ReflectiveXaeroMapAdapter.xaeroRegionFolderCandidates(root, "mw$default"));
		assertEquals(List.of(root.resolve("default"), root.resolve("mw$default")),
				ReflectiveXaeroMapAdapter.xaeroRegionFolderCandidates(root, "default"));
		assertTrue(ReflectiveXaeroMapAdapter.xaeroRegionFolderCandidates(root, null).isEmpty());
	}

	@Test
	void archiveFallbackUsesOnlyAnUnambiguousMultiworldFolder() throws IOException {
		Path mainFolder = tempDirectory.resolve("current-dimension");
		Path current = Files.createDirectories(mainFolder.resolve("mw$default"));
		assertEquals(current, ReflectiveXaeroMapAdapter.uniqueXaeroMultiworldFolder(mainFolder).orElseThrow());

		Files.createDirectories(mainFolder.resolve("mw$alternate"));
		assertTrue(ReflectiveXaeroMapAdapter.uniqueXaeroMultiworldFolder(mainFolder).isEmpty());
	}

	@Test
	void onlyCurrentlyLoadedChunksAreLocallyOwned() {
		assertEquals(XaeroMapAdapter.LocalTileState.REMOTE,
				ReflectiveXaeroMapAdapter.localOwnership(false, true));
		assertEquals(XaeroMapAdapter.LocalTileState.GENERATING,
				ReflectiveXaeroMapAdapter.localOwnership(true, false));
		assertEquals(XaeroMapAdapter.LocalTileState.READY,
				ReflectiveXaeroMapAdapter.localOwnership(true, true));
	}

	@Test
	void allAirLocalSnapshotsAreRejectedAsXaeroPlaceholders() {
		assertFalse(ReflectiveXaeroMapAdapter.isUsableLocalSnapshot(allAirTile(256)));

		int[] baseStateIds = new int[256];
		baseStateIds[17] = 1;
		assertTrue(ReflectiveXaeroMapAdapter.isUsableLocalSnapshot(tile(256, baseStateIds)));
		assertTrue(ReflectiveXaeroMapAdapter.isUsableLocalSnapshot(overlayOnlyTile(256)));
	}

	@Test
	void pinnedXaeroJarStoresLastSaveTimeAsLongField() throws IOException {
		ClassSignature signature = readClassSignature("xaero/map/region/LeveledRegion.class");

		assertEquals("J", signature.fieldDescriptor("lastSaveTime"));
		assertEquals("()Z", signature.methodDescriptor("isAllCachePrepared"));
		assertEquals("(Z)V", signature.methodDescriptor("setAllCachePrepared"));
		assertEquals("(ZLjava/lang/String;)V", signature.methodDescriptor("setShouldCache"));
		assertNull(signature.methodDescriptor("getLastSaveTime"));
		assertNull(signature.methodDescriptor("setLastSaveTime"));
	}

	@Test
	void pinnedXaeroJarExposesNativeLoadingGate() throws IOException {
		ClassSignature signature = readClassSignature("xaero/map/MapProcessor.class");
		ClassSignature saveLoad = readClassSignature("xaero/map/file/MapSaveLoad.class");
		ClassSignature region = readClassSignature("xaero/map/region/MapRegion.class");
		ClassSignature leveledRegion = readClassSignature("xaero/map/region/LeveledRegion.class");

		assertEquals("(Ljava/lang/Runnable;)V", signature.methodDescriptor("waitForLoadingToFinish"));
		assertEquals("(II)Lxaero/map/region/MapTile;", signature.methodDescriptor("getMapTile"));
		assertEquals("()Ljava/lang/String;", signature.methodDescriptor("getCurrentDimension"));
		assertTrue(saveLoad.hasMethodDescriptor("requestLoad", "(Lxaero/map/region/MapRegion;Ljava/lang/String;)V"));
		assertEquals("Ljava/util/ArrayList;", saveLoad.fieldDescriptor("toLoad"));
		assertEquals("(Lxaero/map/region/LeveledRegion;)Z", saveLoad.methodDescriptor("toCacheContains"));
		assertEquals("(Lxaero/map/region/LeveledRegion;)V", saveLoad.methodDescriptor("requestCache"));
		assertEquals("()Z", leveledRegion.methodDescriptor("shouldCache"));
		assertEquals("(ZLjava/lang/String;)V", leveledRegion.methodDescriptor("setShouldCache"));
		assertEquals("()Z", leveledRegion.methodDescriptor("recacheHasBeenRequested"));
		assertEquals("(ZLjava/lang/String;)V", leveledRegion.methodDescriptor("setRecacheHasBeenRequested"));
		assertEquals("()Z", region.methodDescriptor("isWritingPaused"));
		assertEquals("Ljava/lang/Object;", region.fieldDescriptor("writerThreadPauseSync"));
		ClassSignature tile = readClassSignature("xaero/map/region/MapTile.class");
		assertEquals("()Z", tile.methodDescriptor("isLoaded"));
		assertEquals("()Z", tile.methodDescriptor("wasWrittenOnce"));
		ClassSignature core = readClassSignature("xaero/map/core/XaeroWorldMapCore.class");
		assertEquals("Ljava/lang/reflect/Field;", core.fieldDescriptor("chunkCleanField"));
		assertEquals("(II)V", core.methodDescriptor("chunkUpdateCallback"));
		ClassSignature mapWorld = readClassSignature("xaero/map/world/MapWorld.class");
		ClassSignature mapDimension = readClassSignature("xaero/map/world/MapDimension.class");
		ClassSignature detection = readClassSignature("xaero/map/file/RegionDetection.class");
		assertEquals("()Lxaero/map/world/MapWorld;", signature.methodDescriptor("getMapWorld"));
		assertEquals("()Lxaero/map/world/MapDimension;", mapWorld.methodDescriptor("getCurrentDimension"));
		assertEquals("()Lnet/minecraft/class_5321;", mapWorld.methodDescriptor("getCurrentDimensionId"));
		assertEquals("(Lnet/minecraft/class_5321;)Lxaero/map/world/MapDimension;",
				mapWorld.methodDescriptor("getDimension"));
		assertEquals("()Ljava/lang/String;", mapWorld.methodDescriptor("getCurrentMultiworld"));
		assertEquals("()Ljava/lang/String;", mapWorld.methodDescriptor("getMainId"));
		assertEquals("(Lnet/minecraft/class_5321;)Ljava/lang/String;", signature.methodDescriptor("getDimensionName"));
		assertEquals("(Ljava/lang/String;Ljava/lang/String;)Ljava/nio/file/Path;",
				saveLoad.methodDescriptor("getMainFolder"));
		assertEquals("()Ljava/lang/String;", signature.methodDescriptor("getCurrentWorldId"));
		assertEquals("()Ljava/util/Hashtable;", mapDimension.methodDescriptor("getDetectedRegions"));
		assertEquals("()Ljava/nio/file/Path;", mapDimension.methodDescriptor("getMainFolderPath"));
		assertEquals("()Ljava/lang/String;", mapDimension.methodDescriptor("getCurrentMultiworld"));
		assertEquals("()I", detection.methodDescriptor("getRegionX"));
		assertEquals("()I", detection.methodDescriptor("getRegionZ"));
	}

	@Test
	void archiveRegionOperationsAreDelegatedToRuntime() {
		XaeroMapAdapter.LocalRegion region = new XaeroMapAdapter.LocalRegion(-2, 3);
		ReflectiveXaeroMapAdapter.XaeroRuntime runtime = new ReflectiveXaeroMapAdapter.XaeroRuntime() {
			@Override public void apply(MapTile ignored) { }
			@Override public List<XaeroMapAdapter.LocalRegion> knownLocalRegions(String dimension) {
				return List.of(region);
			}
			@Override public boolean prepareLocalRegion(String dimension, XaeroMapAdapter.LocalRegion requested) {
				return requested.equals(region);
			}
		};
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(runtime);
		assertEquals(List.of(region), adapter.knownLocalRegions("minecraft:overworld"));
		assertTrue(adapter.prepareLocalRegion("minecraft:overworld", region));
	}

	@Test
	void transientArchiveDiscoveryFailureDoesNotDisableLiveMapAdapter() {
		ReflectiveXaeroMapAdapter.XaeroRuntime runtime = new ReflectiveXaeroMapAdapter.XaeroRuntime() {
			@Override public void apply(MapTile ignored) { }
			@Override public List<XaeroMapAdapter.LocalRegion> knownLocalRegions(String dimension) {
				throw new NullPointerException("Xaero dimension id is joining");
			}
		};
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(runtime);

		assertTrue(adapter.knownLocalRegions("minecraft:overworld").isEmpty());
		assertTrue(adapter.isAvailable());
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
		assertEquals("(I)V", overlay.methodDescriptor("increaseOpacity"));
		assertEquals("(Lxaero/map/region/Overlay;)V", mapBlock.methodDescriptor("addOverlay"));
		assertEquals("(Lnet/minecraft/class_2680;IILxaero/map/biome/BiomeKey;BZZ)V",
				mapBlock.methodDescriptor("write"));
		assertEquals("(Z)V", mapBlock.methodDescriptor("setSlopeUnknown"));
	}

	@Test
	void pinnedXaeroJarExposesNativeSnapshotReadSignatures() throws IOException {
		ClassSignature tile = readClassSignature("xaero/map/region/MapTile.class");
		ClassSignature pixel = readClassSignature("xaero/map/region/MapPixel.class");
		ClassSignature block = readClassSignature("xaero/map/region/MapBlock.class");
		ClassSignature biome = readClassSignature("xaero/map/biome/BiomeKey.class");
		ClassSignature overlay = readClassSignature("xaero/map/region/Overlay.class");
		ClassSignature region = readClassSignature("xaero/map/region/MapRegion.class");
		ClassSignature chunk = readClassSignature("xaero/map/region/MapTileChunk.class");
		ClassSignature core = readClassSignature("xaero/map/core/XaeroWorldMapCore.class");

		assertEquals("(II)Lxaero/map/region/MapTileChunk;", region.methodDescriptor("getChunk"));
		assertEquals("()I", chunk.methodDescriptor("getLoadState"));
		assertEquals("(II)Lxaero/map/region/MapTile;", chunk.methodDescriptor("getTile"));
		assertEquals("(II)Lxaero/map/region/MapBlock;", tile.methodDescriptor("getBlock"));
		assertEquals("()Lnet/minecraft/class_2680;", pixel.methodDescriptor("getState"));
		assertEquals("B", pixel.fieldDescriptor("light"));
		assertEquals("Z", pixel.fieldDescriptor("glowing"));
		assertEquals("()I", block.methodDescriptor("getHeight"));
		assertEquals("()I", block.methodDescriptor("getTopHeight"));
		assertEquals("()Lxaero/map/biome/BiomeKey;", block.methodDescriptor("getBiome"));
		assertEquals("()Ljava/util/ArrayList;", block.methodDescriptor("getOverlays"));
		assertEquals("()Z", block.methodDescriptor("isCaveBlock"));
		assertEquals("(Lnet/minecraft/class_2378;)Lnet/minecraft/class_2960;",
				biome.methodDescriptor("getIdentifier"));
		assertEquals("()F", overlay.methodDescriptor("getTransparency"));
		assertEquals("()I", overlay.methodDescriptor("getOpacity"));
		assertEquals("Ljava/lang/reflect/Field;", core.fieldDescriptor("chunkCleanField"));
	}

	@Test
	void localSnapshotIsDelegatedToRuntime() {
		MapTile snapshot = tile(256);
		ReflectiveXaeroMapAdapter.XaeroRuntime runtime = new ReflectiveXaeroMapAdapter.XaeroRuntime() {
			@Override public void apply(MapTile ignored) { }
			@Override public Optional<MapTile> localTile(String dimension, int chunkX, int chunkZ) {
				return Optional.of(snapshot);
			}
		};
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(runtime);

		assertEquals(snapshot, adapter.localTile("minecraft:overworld", -1, -5).orElseThrow());
		assertTrue(adapter.isAvailable());
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
	void inactiveDimensionBatchRemainsAvailableForRetry() {
		ReflectiveXaeroMapAdapter adapter = new ReflectiveXaeroMapAdapter(tile -> {
			throw new IllegalStateException("Xaero map tile dimension is not loaded yet: minecraft:the_nether");
		});

		assertEquals(XaeroMapAdapter.ApplyResult.RETRY_LATER, adapter.applyBatchResult(java.util.List.of(tile(256))));
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
		int[] baseStateIds = new int[size];
		if (size > 0) {
			baseStateIds[0] = 1;
		}
		return tile(size, baseStateIds);
	}

	private static MapTile allAirTile(int size) {
		return new MapTile("minecraft:overworld", -1, -5, new int[size], new int[size], new int[size], new int[size], 1L);
	}

	private static MapTile overlayOnlyTile(int size) {
		List<List<MapTile.Overlay>> overlays = new ArrayList<>(size);
		for (int index = 0; index < size; index++) {
			overlays.add(index == 0 ? List.of(new MapTile.Overlay(9, 0.66F, (byte) 15, false, 1)) : List.of());
		}
		return new MapTile("minecraft:overworld", -1, -5, new int[size], new int[size], new int[size],
				new int[size], new byte[size], new boolean[size], new boolean[size], overlays, 1L);
	}

	private static MapTile tile(int size, int[] baseStateIds) {
		return new MapTile("minecraft:overworld", -1, -5, new int[size], baseStateIds, new int[size], new int[size], 1L);
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
