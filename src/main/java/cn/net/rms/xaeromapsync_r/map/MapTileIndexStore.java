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
	static final String INDEX_FILE_NAME = "map_patch_index-v6.json";
	private final Map<String, MapTileIndexEntry> tiles = new LinkedHashMap<>();
	private List<MerkleNode> cachedMerkleSnapshot = List.of();
	private boolean merkleSnapshotDirty = true;
	private long nextRevision = 1L;
	private int surfaceSamplerVersion = MapTileDebugRenderer.SURFACE_SAMPLER_VERSION;

	public synchronized void load(MinecraftServer server) {
		Path path = path(server);
		tiles.clear();
		invalidateMerkleSnapshot();
		nextRevision = 1L;
		surfaceSamplerVersion = MapTileDebugRenderer.SURFACE_SAMPLER_VERSION;
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			MapTileIndexFile file = GSON.fromJson(reader, MapTileIndexFile.class);
			if (file == null) {
				return;
			}
			nextRevision = Math.max(1L, file.nextRevision);
			surfaceSamplerVersion = file.surfaceSamplerVersion;
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
			file.surfaceSamplerVersion = surfaceSamplerVersion;
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
		invalidateMerkleSnapshot();
		return entry;
	}

	public synchronized long rootHash() {
		return MerkleTreeBuilder.rootHash(merkleSnapshotInternal());
	}

	public synchronized int totalCount() {
		return tiles.size();
	}

	public synchronized Collection<MapTileIndexEntry> snapshot() {
		return List.copyOf(tiles.values());
	}

	public synchronized DimensionSnapshot dimensionSnapshot(String dimension) {
		List<MapTileIndexEntry> entries = new ArrayList<>();
		for (MapTileIndexEntry entry : tiles.values()) {
			if (dimension.equals(entry.dimension())) entries.add(entry);
		}
		long epoch = rootHash(dimension);
		return new DimensionSnapshot(epoch, List.copyOf(entries));
	}

	public synchronized boolean requiresSurfaceResample() {
		return !tiles.isEmpty() && surfaceSamplerVersion < MapTileDebugRenderer.SURFACE_SAMPLER_VERSION;
	}

	public synchronized void markSurfaceSamplerCurrent() {
		surfaceSamplerVersion = MapTileDebugRenderer.SURFACE_SAMPLER_VERSION;
	}

	public synchronized Collection<MerkleNode> merkleSnapshot() {
		return List.copyOf(merkleSnapshotInternal());
	}

	public synchronized List<MerkleNode> merkleRoots() {
		return MerkleTreeBuilder.roots(merkleSnapshotInternal());
	}

	public synchronized long rootHash(String dimension) {
		return MerkleTreeBuilder.rootHash(merkleSnapshot(dimension));
	}

	public synchronized List<MerkleNode> merkleRoots(String dimension) {
		return MerkleTreeBuilder.roots(merkleSnapshot(dimension));
	}

	private List<MerkleNode> merkleSnapshot(String dimension) {
		return merkleSnapshotInternal().stream().filter(node -> node.dimension().equals(dimension)).toList();
	}

	public synchronized List<MerkleNode> merkleChildren(String dimension, int level, int nodeX, int nodeZ) {
		return merkleChildren(List.of(new MerkleNodeAddress(dimension, level, nodeX, nodeZ)));
	}

	public synchronized List<MerkleNode> merkleChildren(Collection<MerkleNodeAddress> addresses) {
		if (addresses.isEmpty()) return Collections.emptyList();
		List<MerkleNode> snapshot = merkleSnapshotInternal();
		List<MerkleNode> children = new ArrayList<>();
		for (MerkleNodeAddress address : addresses) {
			if (address.level() <= 0) continue;
			for (MerkleNode node : snapshot) {
				if (node.dimension().equals(address.dimension()) && node.level() == address.level() - 1
						&& Math.floorDiv(node.nodeX(), 2) == address.nodeX()
						&& Math.floorDiv(node.nodeZ(), 2) == address.nodeZ()) {
					children.add(node);
				}
			}
		}
		children.sort(Comparator.comparing(MerkleNode::dimension).thenComparingInt(MerkleNode::level)
				.thenComparingInt(MerkleNode::nodeX).thenComparingInt(MerkleNode::nodeZ));
		return children;
	}

	public synchronized Optional<MapTileIndexEntry> find(String dimension, int chunkX, int chunkZ) {
		return Optional.ofNullable(tiles.get(key(dimension, chunkX, chunkZ)));
	}

	private List<MerkleNode> merkleSnapshotInternal() {
		if (merkleSnapshotDirty) {
			cachedMerkleSnapshot = List.copyOf(MerkleTreeBuilder.build(tiles.values()));
			merkleSnapshotDirty = false;
		}
		return cachedMerkleSnapshot;
	}

	private void invalidateMerkleSnapshot() {
		cachedMerkleSnapshot = List.of();
		merkleSnapshotDirty = true;
	}

	private static String key(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + ChunkPos.asLong(chunkX, chunkZ);
	}

	private static Path path(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("xaero-mapsync_r").resolve(INDEX_FILE_NAME);
	}

	private static final class MapTileIndexFile {
		private long nextRevision;
		private long rootHash;
		private int surfaceSamplerVersion;
		private MapTileIndexEntry[] tiles;
	}

	public record DimensionSnapshot(long epoch, List<MapTileIndexEntry> entries) { }
}
