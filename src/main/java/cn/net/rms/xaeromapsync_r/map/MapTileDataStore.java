package cn.net.rms.xaeromapsync_r.map;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

public final class MapTileDataStore {
	private static final int MAGIC = 0x584d5332;
	private static final int TILE_VALUES = 256;
	private static final int MAX_MEMORY_TILES = 1024;
	private static final Pattern TILE_FILE = Pattern.compile("(-?\\d+)_(-?\\d+)\\.tile");
	private final Map<String, MapTile> memory = new LinkedHashMap<>(128, 0.75F, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<String, MapTile> eldest) { return size() > MAX_MEMORY_TILES; }
	};
	private ExecutorService writer;
	private Path root;

	public synchronized void start(MinecraftServer server) {
		start(server.getWorldPath(LevelResource.ROOT).resolve("xaero-mapsync_r").resolve("tiles"));
	}

	synchronized void start(Path root) {
		this.root = root;
		writer = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "xaero-mapsync-tile-writer");
			thread.setDaemon(true);
			return thread;
		});
	}

	public void put(MapTile tile) {
		Path target;
		ExecutorService activeWriter;
		synchronized (this) {
			memory.put(key(tile.dimension(), tile.chunkX(), tile.chunkZ()), tile);
			target = path(tile.dimension(), tile.chunkX(), tile.chunkZ());
			activeWriter = writer;
		}
		if (target != null && activeWriter != null) activeWriter.execute(() -> write(target, tile));
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

	public int recoverIndex(MapTileIndexStore index) {
		Path activeRoot;
		synchronized (this) {
			activeRoot = root;
		}
		if (activeRoot == null || !Files.isDirectory(activeRoot)) {
			return 0;
		}
		int recovered = 0;
		try (Stream<Path> dimensions = Files.list(activeRoot)) {
			for (Path dimensionPath : (Iterable<Path>) dimensions.filter(Files::isDirectory)::iterator) {
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
						Matcher matcher = TILE_FILE.matcher(tilePath.getFileName().toString());
						if (!matcher.matches()) {
							continue;
						}
						try {
							int chunkX = Integer.parseInt(matcher.group(1));
							int chunkZ = Integer.parseInt(matcher.group(2));
							Optional<MapTile> tile = find(dimension, chunkX, chunkZ);
							if (tile.isPresent()) {
								Optional<MapTileIndexEntry> previous = index.find(dimension, chunkX, chunkZ);
								if (previous.isEmpty() || previous.get().contentHash() != tile.get().contentHash()) {
									recovered++;
								}
								index.upsert(tile.get());
							}
						} catch (NumberFormatException exception) {
							XaeroMapsync_r.LOGGER.warn("Ignoring map tile with invalid coordinates {}", tilePath);
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

	public synchronized void stop() {
		ExecutorService activeWriter = writer;
		writer = null;
		if (activeWriter == null) return;
		activeWriter.shutdown();
		try {
			if (!activeWriter.awaitTermination(10, TimeUnit.SECONDS)) {
				XaeroMapsync_r.LOGGER.warn("Timed out while flushing map tile cache writes");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			XaeroMapsync_r.LOGGER.warn("Interrupted while flushing map tile cache writes", exception);
		}
	}

	private synchronized Path path(String dimension, int chunkX, int chunkZ) {
		if (root == null) return null;
		String encodedDimension = Base64.getUrlEncoder().withoutPadding().encodeToString(dimension.getBytes(StandardCharsets.UTF_8));
		return root.resolve(encodedDimension).resolve(chunkX + "_" + chunkZ + ".tile");
	}

	private static void write(Path target, MapTile tile) {
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try {
			Files.createDirectories(target.getParent());
			try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(new BufferedOutputStream(Files.newOutputStream(temp))))) {
				output.writeInt(MAGIC);
				output.writeLong(tile.contentHash());
				writeArray(output, tile.heights());
				writeArray(output, tile.blockStateIds());
				writeArray(output, tile.biomeIds());
				writeArray(output, tile.lightLevels());
			}
			try {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to persist map tile cache {}", target, exception);
		}
	}

	private static MapTile read(Path target, String dimension, int chunkX, int chunkZ) throws IOException {
		try (DataInputStream input = new DataInputStream(new InflaterInputStream(new BufferedInputStream(Files.newInputStream(target))))) {
			if (input.readInt() != MAGIC) throw new IOException("Unsupported map tile cache format");
			long hash = input.readLong();
			int[] heights = readArray(input);
			int[] states = readArray(input);
			int[] biomes = readArray(input);
			int[] lights = readArray(input);
			if (input.read() != -1) throw new IOException("Trailing map tile cache data");
			MapTile tile = new MapTile(dimension, chunkX, chunkZ, heights, states, biomes, lights, hash);
			if (MapTileHasher.hashSurface(heights, states, biomes, lights) != hash) throw new IOException("Map tile cache hash mismatch");
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

	private static String key(String dimension, int chunkX, int chunkZ) { return dimension + ":" + ChunkPos.asLong(chunkX, chunkZ); }
}
