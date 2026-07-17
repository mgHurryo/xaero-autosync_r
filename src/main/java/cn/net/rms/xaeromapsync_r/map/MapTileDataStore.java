package cn.net.rms.xaeromapsync_r.map;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

public final class MapTileDataStore {
	private static final int MAGIC = 0x584d5336;
	static final String CACHE_DIRECTORY = "tiles-v6";
	private static final int TILE_VALUES = 256;
	private static final int MAX_MEMORY_TILES = 1024;
	private static final int MAX_HISTORICAL_TILES = 16_384;
	private static final long SYNCHRONOUS_WRITE_TIMEOUT_MILLIS = 2_000L;
	private static final int WRITER_STRIPES = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
	private static final int MAX_PENDING_WRITES_PER_STRIPE = 256;
	private static final Pattern TILE_FILE = Pattern.compile("(-?\\d+)_(-?\\d+)\\.tile");
	private static final Pattern STAGED_TILE_FILE = Pattern.compile("-?\\d+_-?\\d+\\.tile\\.stage-[0-9a-fA-F-]{36}");
	private final Map<String, MapTile> memory = new LinkedHashMap<>(128, 0.75F, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<String, MapTile> eldest) { return size() > MAX_MEMORY_TILES; }
	};
	private final Map<String, MapTile> historical = new LinkedHashMap<>(128, 0.75F, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<String, MapTile> eldest) {
			return size() > MAX_HISTORICAL_TILES;
		}
	};
	private ExecutorService[] writers;
	private ExecutorService recoveryReader;
	private Path root;

	public synchronized void start(MinecraftServer server) {
		start(server.getWorldPath(LevelResource.ROOT).resolve("xaero-mapsync_r").resolve(CACHE_DIRECTORY));
	}

	public synchronized void start(Path root) {
		this.root = root;
		memory.clear();
		historical.clear();
		recoveryReader = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "xaero-mapsync-index-recovery");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
		writers = new ExecutorService[WRITER_STRIPES];
		for (int index = 0; index < writers.length; index++) {
			int stripe = index;
			writers[index] = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
					new ArrayBlockingQueue<>(MAX_PENDING_WRITES_PER_STRIPE), runnable -> {
						Thread thread = new Thread(runnable, "xaero-mapsync-tile-writer-" + stripe);
						thread.setDaemon(true);
						return thread;
					}, new ThreadPoolExecutor.AbortPolicy());
		}
	}

	public void put(MapTile tile) {
		putAsynchronously(tile, successful -> { });
	}

	/**
	 * Persists without blocking the server tick. Writes for the same tile stay ordered,
	 * while unrelated coordinates can use separate writer stripes.
	 */
	public boolean putAsynchronously(MapTile tile, Consumer<Boolean> completion) {
		if (tile == null || completion == null) throw new IllegalArgumentException("Tile and completion callback are required");
		Path target;
		ExecutorService activeWriter;
		synchronized (this) {
			target = path(tile.dimension(), tile.chunkX(), tile.chunkZ());
			activeWriter = writerFor(tile.dimension(), tile.chunkX(), tile.chunkZ());
		}
		if (target == null || activeWriter == null) return false;
		try {
			activeWriter.execute(() -> {
				preserveCurrentBody(tile);
				boolean successful = write(target, tile);
				if (successful) {
					synchronized (MapTileDataStore.this) {
						memory.put(key(tile.dimension(), tile.chunkX(), tile.chunkZ()), tile);
					}
				}
				try {
					completion.accept(successful);
				} catch (RuntimeException exception) {
					XaeroMapsync_r.LOGGER.warn("Map tile completion callback failed for {}", target, exception);
				}
			});
			return true;
		} catch (RejectedExecutionException exception) {
			XaeroMapsync_r.LOGGER.debug("map_sync event=tile_writer_backpressure target={} action=retry_later", target);
			return false;
		}
	}

	/** Writes an immutable candidate without replacing the published tile body. */
	public boolean stageAsynchronously(MapTile tile, Consumer<Optional<StagedTile>> completion) {
		if (tile == null || completion == null) throw new IllegalArgumentException("Tile and completion callback are required");
		Path target;
		ExecutorService activeWriter;
		synchronized (this) {
			target = path(tile.dimension(), tile.chunkX(), tile.chunkZ());
			activeWriter = writerFor(tile.dimension(), tile.chunkX(), tile.chunkZ());
		}
		if (target == null || activeWriter == null) return false;
		Path stagedPath = target.resolveSibling(target.getFileName() + ".stage-" + UUID.randomUUID());
		try {
			activeWriter.execute(() -> {
				Optional<StagedTile> result = write(stagedPath, tile)
						? Optional.of(new StagedTile(tile, target, stagedPath))
						: Optional.empty();
				try {
					completion.accept(result);
				} catch (RuntimeException exception) {
					XaeroMapsync_r.LOGGER.warn("Map tile staging callback failed for {}", stagedPath, exception);
					result.ifPresent(this::discardStaged);
				}
			});
			return true;
		} catch (RejectedExecutionException exception) {
			XaeroMapsync_r.LOGGER.warn("Map tile cache writer rejected staging {}", stagedPath, exception);
			return false;
		}
	}

	/** Must be called from the staging writer while the caller holds its generation lock. */
	public boolean commitStaged(StagedTile staged) {
		if (staged == null) return false;
		try {
			preserveCurrentBody(staged.tile);
			try {
				Files.move(staged.stagedPath, staged.targetPath, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
				Files.move(staged.stagedPath, staged.targetPath, StandardCopyOption.REPLACE_EXISTING);
			}
			synchronized (this) {
				memory.put(key(staged.tile.dimension(), staged.tile.chunkX(), staged.tile.chunkZ()), staged.tile);
			}
			return true;
		} catch (IOException | RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to commit staged map tile {}", staged.stagedPath, exception);
			discardStaged(staged);
			return false;
		}
	}

	public void discardStaged(StagedTile staged) {
		if (staged == null) return;
		try {
			Files.deleteIfExists(staged.stagedPath);
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to discard staged map tile {}", staged.stagedPath, exception);
		}
	}

	public synchronized boolean hasWriteCapacity(String dimension, int chunkX, int chunkZ) {
		ExecutorService writer = writerFor(dimension, chunkX, chunkZ);
		return writer instanceof ThreadPoolExecutor
				&& ((ThreadPoolExecutor) writer).getQueue().remainingCapacity() > 0;
	}

	/**
	 * Persists the tile on the ordered writer and returns only after the atomic replacement succeeds.
	 * Callers must publish the corresponding index entry only when this method returns {@code true}.
	 */
	public boolean putSynchronously(MapTile tile) {
		Path target;
		ExecutorService activeWriter;
		synchronized (this) {
			target = path(tile.dimension(), tile.chunkX(), tile.chunkZ());
			activeWriter = writerFor(tile.dimension(), tile.chunkX(), tile.chunkZ());
		}
		if (target == null || activeWriter == null) {
			XaeroMapsync_r.LOGGER.warn("Cannot persist map tile before the tile data store is started");
			return false;
		}

		Future<Boolean> writeResult;
		try {
			writeResult = activeWriter.submit(() -> {
				preserveCurrentBody(tile);
				return write(target, tile);
			});
		} catch (RejectedExecutionException exception) {
			XaeroMapsync_r.LOGGER.warn("Map tile cache writer rejected synchronous write {}", target, exception);
			return false;
		}
		try {
			if (!writeResult.get(SYNCHRONOUS_WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) return false;
			synchronized (this) {
				memory.put(key(tile.dimension(), tile.chunkX(), tile.chunkZ()), tile);
			}
			return true;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			writeResult.cancel(true);
			XaeroMapsync_r.LOGGER.warn("Interrupted while persisting map tile cache {}", target, exception);
		} catch (ExecutionException exception) {
			XaeroMapsync_r.LOGGER.warn("Map tile cache writer failed for {}", target, exception.getCause());
		} catch (TimeoutException exception) {
			XaeroMapsync_r.LOGGER.warn("Timed out while persisting map tile cache {}", target);
		}
		return false;
	}

	public Optional<MapTile> find(String dimension, int chunkX, int chunkZ) {
		Path target;
		synchronized (this) {
			MapTile cached = memory.get(key(dimension, chunkX, chunkZ));
			if (cached != null) return Optional.of(cached);
			target = path(dimension, chunkX, chunkZ);
		}
		if (target == null || !Files.isRegularFile(target)) return Optional.empty();
		try {
			MapTile tile = read(target, dimension, chunkX, chunkZ);
			synchronized (this) { memory.put(key(dimension, chunkX, chunkZ), tile); }
			return Optional.of(tile);
		} catch (IOException | RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to read map tile cache {}", target, exception);
			return Optional.empty();
		}
	}

	/** Returns the immutable body referenced by a retained catalog epoch. */
	public Optional<MapTile> find(String dimension, int chunkX, int chunkZ, long contentHash) {
		Optional<MapTile> current = find(dimension, chunkX, chunkZ);
		if (current.isPresent() && current.get().contentHash() == contentHash) return current;
		synchronized (this) {
			return Optional.ofNullable(historical.get(versionKey(dimension, chunkX, chunkZ, contentHash)));
		}
	}

	private void preserveCurrentBody(MapTile replacement) {
		Optional<MapTile> current = find(replacement.dimension(), replacement.chunkX(), replacement.chunkZ());
		if (current.isEmpty() || current.get().contentHash() == replacement.contentHash()) return;
		MapTile previous = current.get();
		synchronized (this) {
			historical.put(versionKey(previous.dimension(), previous.chunkX(), previous.chunkZ(),
					previous.contentHash()), previous);
		}
	}

	private static String versionKey(String dimension, int chunkX, int chunkZ, long contentHash) {
		return key(dimension, chunkX, chunkZ) + '@' + Long.toUnsignedString(contentHash);
	}

	public int recoverIndex(MapTileIndexStore index) {
		Path activeRoot;
		synchronized (this) { activeRoot = root; }
		if (activeRoot == null || !Files.isDirectory(activeRoot)) return 0;
		int recovered = 0;
		try (Stream<Path> dimensions = Files.list(activeRoot)) {
			for (Path dimensionPath : (Iterable<Path>) dimensions.filter(Files::isDirectory)::iterator) {
				if (Thread.currentThread().isInterrupted()) return recovered;
				String dimension;
				try {
					dimension = new String(Base64.getUrlDecoder().decode(dimensionPath.getFileName().toString()),
							StandardCharsets.UTF_8);
				} catch (IllegalArgumentException exception) {
					XaeroMapsync_r.LOGGER.warn("Ignoring map tile directory with invalid dimension id {}", dimensionPath);
					continue;
				}
				try (Stream<Path> files = Files.list(dimensionPath)) {
					for (Path tilePath : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
						if (Thread.currentThread().isInterrupted()) return recovered;
						String fileName = tilePath.getFileName().toString();
						if (STAGED_TILE_FILE.matcher(fileName).matches()) {
							try {
								Files.deleteIfExists(tilePath);
								XaeroMapsync_r.LOGGER.debug("Removed orphaned staged map tile {}", tilePath);
							} catch (IOException exception) {
								XaeroMapsync_r.LOGGER.warn("Failed to remove orphaned staged map tile {}", tilePath, exception);
							}
							continue;
						}
						Matcher matcher = TILE_FILE.matcher(fileName);
						if (!matcher.matches()) continue;
						try {
							int chunkX = Integer.parseInt(matcher.group(1));
							int chunkZ = Integer.parseInt(matcher.group(2));
							Optional<MapTileIndexEntry> previous = index.find(dimension, chunkX, chunkZ);
							long modifiedAtMillis = Files.getLastModifiedTime(tilePath).toMillis();
							if (!shouldReadForRecovery(previous, modifiedAtMillis)) continue;
							Optional<MapTile> tile = find(dimension, chunkX, chunkZ);
							if (tile.isPresent() && tile.get().hasRenderableSurface()) {
								if (previous.isEmpty() || previous.get().contentHash() != tile.get().contentHash()) recovered++;
								index.upsert(tile.get());
							}
						} catch (NumberFormatException exception) {
							XaeroMapsync_r.LOGGER.warn("Ignoring map tile with invalid coordinates {}", tilePath);
						} catch (IOException exception) {
							XaeroMapsync_r.LOGGER.warn("Failed to inspect map tile cache {}", tilePath, exception);
						}
					}
				} catch (IOException exception) {
					XaeroMapsync_r.LOGGER.warn("Failed to scan map tile directory {}", dimensionPath, exception);
				}
			}
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to recover map tile index from {}", activeRoot, exception);
		}
		return recovered;
	}

	public boolean recoverIndexAsynchronously(MapTileIndexStore index, Consumer<Integer> completion) {
		if (index == null || completion == null) {
			throw new IllegalArgumentException("Index and completion callback are required");
		}
		ExecutorService activeReader;
		synchronized (this) { activeReader = recoveryReader; }
		if (activeReader == null) return false;
		try {
			activeReader.execute(() -> {
				int recovered = recoverIndex(index);
				try {
					completion.accept(recovered);
				} catch (RuntimeException exception) {
					XaeroMapsync_r.LOGGER.warn("Map tile index recovery callback failed", exception);
				}
			});
			return true;
		} catch (RejectedExecutionException exception) {
			XaeroMapsync_r.LOGGER.warn("Map tile index recovery was rejected", exception);
			return false;
		}
	}

	static boolean shouldReadForRecovery(Optional<MapTileIndexEntry> existing, long fileModifiedAtMillis) {
		return existing.isEmpty() || fileModifiedAtMillis > existing.get().updatedAtMillis();
	}

	public void stop() {
		ExecutorService[] activeWriters;
		ExecutorService activeRecoveryReader;
		synchronized (this) {
			activeWriters = writers;
			writers = null;
			activeRecoveryReader = recoveryReader;
			recoveryReader = null;
		}
		if (activeRecoveryReader != null) {
			activeRecoveryReader.shutdownNow();
			try {
				activeRecoveryReader.awaitTermination(1, TimeUnit.SECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
		if (activeWriters == null) return;
		for (ExecutorService activeWriter : activeWriters) activeWriter.shutdown();
		for (ExecutorService activeWriter : activeWriters) {
			try {
				if (!activeWriter.awaitTermination(10, TimeUnit.SECONDS)) {
					XaeroMapsync_r.LOGGER.warn("Timed out while flushing map tile cache writes");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				XaeroMapsync_r.LOGGER.warn("Interrupted while flushing map tile cache writes", exception);
				break;
			}
		}
	}

	private ExecutorService writerFor(String dimension, int chunkX, int chunkZ) {
		if (writers == null || writers.length == 0) return null;
		int stripe = Math.floorMod(key(dimension, chunkX, chunkZ).hashCode(), writers.length);
		return writers[stripe];
	}

	private synchronized Path path(String dimension, int chunkX, int chunkZ) {
		if (root == null) return null;
		String encodedDimension = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(dimension.getBytes(StandardCharsets.UTF_8));
		return root.resolve(encodedDimension).resolve(chunkX + "_" + chunkZ + ".tile");
	}

	private static boolean write(Path target, MapTile tile) {
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try {
			if (MapTileHasher.hashSurface(tile) != tile.contentHash()) {
				throw new IOException("Map tile content hash does not match v" + MapTile.FORMAT_VERSION + " surface data");
			}
			Files.createDirectories(target.getParent());
			try (DataOutputStream output = new DataOutputStream(
					new DeflaterOutputStream(new BufferedOutputStream(Files.newOutputStream(temp))))) {
				output.writeInt(MAGIC);
				output.writeInt(MapTile.FORMAT_VERSION);
				output.writeLong(tile.contentHash());
				writeArray(output, tile.baseStateIds());
				writeArray(output, tile.baseHeights());
				writeArray(output, tile.topHeights());
				writeStrings(output, tile.biomeKeys());
				writeBytes(output, tile.lightAbove());
				writeBooleans(output, tile.glowing());
				writeBooleans(output, tile.cave());
				writeOverlays(output, tile.overlays());
			}
			try {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException | RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to persist map tile cache {}", target, exception);
			try {
				Files.deleteIfExists(temp);
			} catch (IOException cleanupException) {
				exception.addSuppressed(cleanupException);
			}
			return false;
		}
	}

	private static MapTile read(Path target, String dimension, int chunkX, int chunkZ) throws IOException {
		try (DataInputStream input = new DataInputStream(
				new InflaterInputStream(new BufferedInputStream(Files.newInputStream(target))))) {
			if (input.readInt() != MAGIC) throw new IOException("Unsupported map tile cache format");
			int formatVersion = input.readInt();
			if (formatVersion != MapTile.FORMAT_VERSION) {
				throw new IOException("Unsupported map tile format version: " + formatVersion);
			}
			long hash = input.readLong();
			int[] baseStates = readArray(input);
			int[] baseHeights = readArray(input);
			int[] topHeights = readArray(input);
			String[] biomes = readStrings(input);
			byte[] lights = readBytes(input);
			boolean[] glowing = readBooleans(input);
			boolean[] cave = readBooleans(input);
			List<List<MapTile.Overlay>> overlays = readOverlays(input);
			if (input.read() != -1) throw new IOException("Trailing map tile cache data");
			MapTile tile = new MapTile(dimension, chunkX, chunkZ, baseStates, baseHeights, topHeights, biomes, lights,
					glowing, cave, overlays, hash);
			if (MapTileHasher.hashSurface(tile) != hash) throw new IOException("Map tile cache hash mismatch");
			return tile;
		} catch (EOFException exception) {
			throw new IOException("Truncated map tile cache", exception);
		}
	}

	private static void writeArray(DataOutputStream output, int[] values) throws IOException {
		if (values.length != TILE_VALUES) throw new IOException("Invalid map tile value count: " + values.length);
		for (int value : values) output.writeInt(value);
	}

	private static int[] readArray(DataInputStream input) throws IOException {
		int[] values = new int[TILE_VALUES];
		for (int index = 0; index < TILE_VALUES; index++) values[index] = input.readInt();
		return values;
	}

	private static void writeStrings(DataOutputStream output, String[] values) throws IOException {
		if (values.length != TILE_VALUES) throw new IOException("Invalid map tile value count: " + values.length);
		for (String value : values) output.writeUTF(value);
	}

	private static String[] readStrings(DataInputStream input) throws IOException {
		String[] values = new String[TILE_VALUES];
		for (int index = 0; index < TILE_VALUES; index++) values[index] = input.readUTF();
		return values;
	}

	private static void writeBytes(DataOutputStream output, byte[] values) throws IOException {
		if (values.length != TILE_VALUES) throw new IOException("Invalid map tile value count: " + values.length);
		output.write(values);
	}

	private static byte[] readBytes(DataInputStream input) throws IOException {
		byte[] values = new byte[TILE_VALUES];
		input.readFully(values);
		return values;
	}

	private static void writeBooleans(DataOutputStream output, boolean[] values) throws IOException {
		if (values.length != TILE_VALUES) throw new IOException("Invalid map tile value count: " + values.length);
		for (boolean value : values) output.writeByte(value ? 1 : 0);
	}

	private static boolean[] readBooleans(DataInputStream input) throws IOException {
		boolean[] values = new boolean[TILE_VALUES];
		for (int index = 0; index < TILE_VALUES; index++) {
			int value = input.readUnsignedByte();
			if (value > 1) throw new IOException("Invalid map tile boolean value: " + value);
			values[index] = value == 1;
		}
		return values;
	}

	private static void writeOverlays(DataOutputStream output, List<List<MapTile.Overlay>> overlays) throws IOException {
		if (overlays.size() != TILE_VALUES) throw new IOException("Invalid map tile overlay column count: " + overlays.size());
		for (List<MapTile.Overlay> column : overlays) {
			output.writeByte(column.size());
			for (MapTile.Overlay overlay : column) {
				output.writeInt(overlay.blockStateId());
				output.writeFloat(overlay.transparency());
				output.writeByte(overlay.lightAbove());
				output.writeByte(overlay.glowing() ? 1 : 0);
				output.writeShort(overlay.opacity());
			}
		}
	}

	private static List<List<MapTile.Overlay>> readOverlays(DataInputStream input) throws IOException {
		List<List<MapTile.Overlay>> overlays = new ArrayList<>(TILE_VALUES);
		for (int columnIndex = 0; columnIndex < TILE_VALUES; columnIndex++) {
			int count = input.readUnsignedByte();
			if (count > MapTile.MAX_OVERLAYS_PER_COLUMN) throw new IOException("Invalid map tile overlay count: " + count);
			List<MapTile.Overlay> column = new ArrayList<>(count);
			for (int overlayIndex = 0; overlayIndex < count; overlayIndex++) {
				int stateId = input.readInt();
				float transparency = input.readFloat();
				byte light = input.readByte();
				int glowing = input.readUnsignedByte();
				if (glowing > 1) throw new IOException("Invalid overlay glowing value: " + glowing);
				try {
					int opacity = input.readUnsignedShort();
					column.add(new MapTile.Overlay(stateId, transparency, light, glowing == 1, opacity));
				} catch (IllegalArgumentException exception) {
					throw new IOException("Invalid map tile overlay", exception);
				}
			}
			overlays.add(List.copyOf(column));
		}
		return List.copyOf(overlays);
	}

	private static String key(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + ChunkPos.asLong(chunkX, chunkZ);
	}

	public static final class StagedTile {
		private final MapTile tile;
		private final Path targetPath;
		private final Path stagedPath;

		private StagedTile(MapTile tile, Path targetPath, Path stagedPath) {
			this.tile = tile;
			this.targetPath = targetPath;
			this.stagedPath = stagedPath;
		}
	}
}
