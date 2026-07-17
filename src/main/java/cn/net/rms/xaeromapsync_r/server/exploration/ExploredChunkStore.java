package cn.net.rms.xaeromapsync_r.server.exploration;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

public final class ExploredChunkStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Map<String, Set<Long>> chunksByDimension = new LinkedHashMap<>();

	public synchronized void load(MinecraftServer server) {
		Path path = path(server);
		chunksByDimension.clear();
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			ExploredChunkFile file = GSON.fromJson(reader, ExploredChunkFile.class);
			if (file == null || file.dimensions == null) {
				return;
			}
			for (DimensionChunks dimension : file.dimensions) {
				if (dimension != null && dimension.dimension != null && dimension.chunks != null) {
					Set<Long> chunks = chunksByDimension.computeIfAbsent(dimension.dimension, ignored -> new LinkedHashSet<>());
					for (long packedChunk : dimension.chunks) {
						chunks.add(packedChunk);
					}
				}
			}
			XaeroMapsync_r.LOGGER.info("Loaded {} explored chunks", totalCount());
		} catch (IOException | RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to load explored chunks at {}", path, exception);
		}
	}

	public synchronized void save(MinecraftServer server) {
		Path path = path(server);
		Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			ExploredChunkFile file = new ExploredChunkFile();
			file.dimensions = new DimensionChunks[chunksByDimension.size()];
			int index = 0;
			for (Map.Entry<String, Set<Long>> entry : chunksByDimension.entrySet()) {
				DimensionChunks dimension = new DimensionChunks();
				dimension.dimension = entry.getKey();
				dimension.chunks = toArray(entry.getValue());
				file.dimensions[index] = dimension;
				index++;
			}
			try (Writer writer = Files.newBufferedWriter(tempPath)) {
				GSON.toJson(file, writer);
			}
			Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to save explored chunks at {}", path, exception);
		}
	}

	public synchronized boolean markExplored(String dimension, int chunkX, int chunkZ) {
		return chunksByDimension
				.computeIfAbsent(dimension, ignored -> new LinkedHashSet<>())
				.add(ChunkPos.asLong(chunkX, chunkZ));
	}

	public synchronized boolean isExplored(String dimension, int chunkX, int chunkZ) {
		Set<Long> chunks = chunksByDimension.get(dimension);
		return chunks != null && chunks.contains(ChunkPos.asLong(chunkX, chunkZ));
	}

	public synchronized int totalCount() {
		int count = 0;
		for (Set<Long> chunks : chunksByDimension.values()) {
			count += chunks.size();
		}
		return count;
	}

	public synchronized List<ExploredChunk> snapshot() {
		List<ExploredChunk> snapshot = new ArrayList<>(totalCount());
		for (Map.Entry<String, Set<Long>> entry : chunksByDimension.entrySet()) {
			for (long packedChunk : entry.getValue()) {
				ChunkPos chunk = new ChunkPos(packedChunk);
				snapshot.add(new ExploredChunk(entry.getKey(), chunk.x, chunk.z));
			}
		}
		return List.copyOf(snapshot);
	}

	public record ExploredChunk(String dimension, int chunkX, int chunkZ) {}

	private static long[] toArray(Set<Long> chunks) {
		long[] values = new long[chunks.size()];
		int index = 0;
		for (Long chunk : chunks) {
			values[index] = chunk.longValue();
			index++;
		}
		return values;
	}

	private static Path path(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("xaero-mapsync_r").resolve("explored_chunks.json");
	}

	private static final class ExploredChunkFile {
		private DimensionChunks[] dimensions;
	}

	private static final class DimensionChunks {
		private String dimension;
		private long[] chunks;
	}
}
