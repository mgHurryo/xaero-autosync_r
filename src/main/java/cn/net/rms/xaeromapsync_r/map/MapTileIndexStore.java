package cn.net.rms.xaeromapsync_r.map;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

public final class MapTileIndexStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Map<String, MapTileIndexEntry> tiles = new LinkedHashMap<>();
	private long nextRevision = 1L;

	public synchronized void load(MinecraftServer server) {
		Path path = path(server);
		tiles.clear();
		nextRevision = 1L;
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			MapTileIndexFile file = GSON.fromJson(reader, MapTileIndexFile.class);
			if (file == null) {
				return;
			}
			nextRevision = Math.max(1L, file.nextRevision);
			if (file.tiles != null) {
				for (MapTileIndexEntry tile : file.tiles) {
					if (tile != null && tile.dimension() != null) {
						tiles.put(key(tile.dimension(), tile.chunkX(), tile.chunkZ()), tile);
						nextRevision = Math.max(nextRevision, tile.revision() + 1L);
					}
				}
			}
			XaeroMapsync_r.LOGGER.info("Loaded {} debug map tile index entries", tiles.size());
		} catch (IOException | RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to load map tile index at {}", path, exception);
		}
	}

	public synchronized void save(MinecraftServer server) {
		Path path = path(server);
		Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			MapTileIndexFile file = new MapTileIndexFile();
			file.nextRevision = nextRevision;
			file.rootHash = rootHash();
			file.tiles = tiles.values().toArray(new MapTileIndexEntry[0]);
			try (Writer writer = Files.newBufferedWriter(tempPath)) {
				GSON.toJson(file, writer);
			}
			Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to save map tile index at {}", path, exception);
		}
	}

	public synchronized MapTileIndexEntry upsert(MapTile tile) {
		String key = key(tile.dimension(), tile.chunkX(), tile.chunkZ());
		MapTileIndexEntry previous = tiles.get(key);
		if (previous != null && previous.contentHash() == tile.contentHash()) {
			return previous;
		}
		MapTileIndexEntry entry = new MapTileIndexEntry(tile.dimension(), tile.chunkX(), tile.chunkZ(), tile.contentHash(), nextRevision++, System.currentTimeMillis());
		tiles.put(key, entry);
		return entry;
	}

	public synchronized long rootHash() {
		return MerkleTreeBuilder.rootHash(MerkleTreeBuilder.build(tiles.values()));
	}

	public synchronized int totalCount() {
		return tiles.size();
	}

	public synchronized Collection<MapTileIndexEntry> snapshot() {
		return Collections.unmodifiableCollection(tiles.values());
	}

	public synchronized Collection<MerkleNode> merkleSnapshot() {
		return MerkleTreeBuilder.build(tiles.values());
	}

	public synchronized List<MerkleNode> merkleRoots() {
		return MerkleTreeBuilder.roots(MerkleTreeBuilder.build(tiles.values()));
	}

	public synchronized long rootHash(String dimension) {
		return MerkleTreeBuilder.rootHash(merkleSnapshot(dimension));
	}

	public synchronized List<MerkleNode> merkleRoots(String dimension) {
		return MerkleTreeBuilder.roots(merkleSnapshot(dimension));
	}

	private List<MerkleNode> merkleSnapshot(String dimension) {
		List<MapTileIndexEntry> entries = new ArrayList<>();
		for (MapTileIndexEntry entry : tiles.values()) if (entry.dimension().equals(dimension)) entries.add(entry);
		return MerkleTreeBuilder.build(entries);
	}

	public synchronized List<MerkleNode> merkleChildren(String dimension, int level, int nodeX, int nodeZ) {
		if (level <= 0) {
			return Collections.emptyList();
		}
		List<MerkleNode> children = new ArrayList<>();
		for (MerkleNode node : MerkleTreeBuilder.build(tiles.values())) {
			if (node.dimension().equals(dimension) && node.level() == level - 1
					&& Math.floorDiv(node.nodeX(), 2) == nodeX && Math.floorDiv(node.nodeZ(), 2) == nodeZ) {
				children.add(node);
			}
		}
		children.sort(Comparator.comparingInt(MerkleNode::nodeX).thenComparingInt(MerkleNode::nodeZ));
		return children;
	}

	public synchronized Optional<MapTileIndexEntry> find(String dimension, int chunkX, int chunkZ) {
		return Optional.ofNullable(tiles.get(key(dimension, chunkX, chunkZ)));
	}

	private static String key(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + ChunkPos.asLong(chunkX, chunkZ);
	}

	private static Path path(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("xaero-mapsync_r").resolve("map_tile_index.json");
	}

	private static final class MapTileIndexFile {
		private long nextRevision;
		private long rootHash;
		private MapTileIndexEntry[] tiles;
	}
}
