package cn.net.rms.xaeromapsync_r.server.dirty;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

public final class DirtyChunkStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Map<String, DirtyChunkRecord> records = new LinkedHashMap<>();
	private final Map<String, Long> generations = new HashMap<>();
	private final Map<String, ClaimTicket> inFlight = new HashMap<>();
	private long currentTick;
	private long nextClaimId = 1L;
	private boolean paused;

	public DirtyChunkStore() {
		this(true);
	}

	DirtyChunkStore(boolean registerTickListener) {
		if (registerTickListener) {
			ServerTickEvents.END_SERVER_TICK.register(server -> advance());
		}
	}

	public synchronized void markDirty(String dimension, BlockPos pos) {
		int chunkX = pos.getX() >> 4;
		int chunkZ = pos.getZ() >> 4;
		String key = key(dimension, chunkX, chunkZ);
		DirtyChunkRecord record = records.computeIfAbsent(key, ignored -> new DirtyChunkRecord(dimension, chunkX, chunkZ, currentTick));
		record.markColumn(pos.getX() & 15, pos.getZ() & 15, currentTick);
		generations.put(key, generations.getOrDefault(key, 0L) + 1L);
		inFlight.remove(key);
	}

	/**
	 * Queues a newly observed chunk without the quiet-period delay used for block changes.
	 * The chunk is already loaded and stable when exploration discovers it, so delaying it
	 * would leave ordinary traversal absent from the shared map.
	 */
	public synchronized boolean markDiscovered(String dimension, int chunkX, int chunkZ) {
		String key = key(dimension, chunkX, chunkZ);
		if (records.containsKey(key)) {
			return false;
		}
		DirtyChunkRecord record = new DirtyChunkRecord(dimension, chunkX, chunkZ, currentTick);
		record.restore(DirtyActivityState.STABLE, currentTick, currentTick, null);
		records.put(key, record);
		generations.put(key, 0L);
		return true;
	}

	public synchronized void load(MinecraftServer server) {
		Path path = path(server);
		records.clear();
		generations.clear();
		inFlight.clear();
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			DirtyChunkFile file = GSON.fromJson(reader, DirtyChunkFile.class);
			if (file == null || file.records == null) {
				return;
			}
			for (DirtyRecordFile recordFile : file.records) {
				if (recordFile == null || recordFile.dimension == null) {
					continue;
				}
				DirtyChunkRecord record = new DirtyChunkRecord(recordFile.dimension, recordFile.chunkX, recordFile.chunkZ, currentTick);
				DirtyActivityState state = recordFile.state == null ? DirtyActivityState.ACTIVE : recordFile.state;
				record.restore(state, currentTick, currentTick, recordFile.dirtyColumns);
				String key = key(recordFile.dimension, recordFile.chunkX, recordFile.chunkZ);
				records.put(key, record);
				generations.put(key, 0L);
			}
			XaeroMapsync_r.LOGGER.info("Loaded {} dirty chunks", records.size());
		} catch (IOException | RuntimeException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to load dirty chunks at {}", path, exception);
		}
	}

	public synchronized void save(MinecraftServer server) {
		Path path = path(server);
		Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			DirtyChunkFile file = new DirtyChunkFile();
			file.records = new DirtyRecordFile[records.size()];
			int index = 0;
			for (DirtyChunkRecord record : records.values()) {
				DirtyRecordFile recordFile = new DirtyRecordFile();
				recordFile.dimension = record.dimension();
				recordFile.chunkX = record.chunkX();
				recordFile.chunkZ = record.chunkZ();
				recordFile.state = record.state();
				recordFile.firstDirtyTick = record.firstDirtyTick();
				recordFile.lastDirtyTick = record.lastDirtyTick();
				recordFile.dirtyColumns = record.dirtyColumnsAsLongArray();
				file.records[index++] = recordFile;
			}
			try (Writer writer = Files.newBufferedWriter(tempPath)) {
				GSON.toJson(file, writer);
			}
			Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException exception) {
			XaeroMapsync_r.LOGGER.warn("Failed to save dirty chunks at {}", path, exception);
		}
	}

	public synchronized List<StableDirtyChunk> claimStableDirtyChunks(int budget) {
		if (budget < 0) {
			throw new IllegalArgumentException("Dirty chunk budget must not be negative");
		}
		if (paused || budget == 0) {
			return Collections.emptyList();
		}

		List<StableDirtyChunk> claimed = new ArrayList<>(Math.min(budget, records.size()));
		for (Map.Entry<String, DirtyChunkRecord> entry : records.entrySet()) {
			if (claimed.size() >= budget) {
				break;
			}
			String key = entry.getKey();
			DirtyChunkRecord record = entry.getValue();
			if (record.state() != DirtyActivityState.STABLE || inFlight.containsKey(key)) {
				continue;
			}
			long generation = generations.getOrDefault(key, 0L);
			long claimId = nextClaimId++;
			inFlight.put(key, new ClaimTicket(claimId, generation));
			claimed.add(new StableDirtyChunk(key, claimId, generation, record));
		}
		return Collections.unmodifiableList(claimed);
	}

	public synchronized boolean confirmProcessed(StableDirtyChunk claimedChunk) {
		if (!hasCurrentClaim(claimedChunk)) {
			return false;
		}
		DirtyChunkRecord record = records.get(claimedChunk.key);
		if (record == null || record.state() != DirtyActivityState.STABLE) {
			inFlight.remove(claimedChunk.key);
			return false;
		}
		records.remove(claimedChunk.key);
		generations.remove(claimedChunk.key);
		inFlight.remove(claimedChunk.key);
		return true;
	}

	public synchronized boolean defer(StableDirtyChunk claimedChunk) {
		if (!hasCurrentClaim(claimedChunk)) {
			return false;
		}
		inFlight.remove(claimedChunk.key);
		DirtyChunkRecord record = records.remove(claimedChunk.key);
		if (record != null) {
			records.put(claimedChunk.key, record);
		}
		return true;
	}

	/**
	 * @deprecated Stable records require explicit processing confirmation and cannot be flushed safely.
	 */
	@Deprecated
	public synchronized int flushStableDirtyChunks() {
		return 0;
	}

	public synchronized int totalCount() {
		return records.size();
	}

	public synchronized String stateSummary() {
		Statistics statistics = statistics();
		return "paused=" + statistics.paused()
				+ ",quiet=" + statistics.quiet()
				+ ",active=" + statistics.active()
				+ ",storm=" + statistics.storm()
				+ ",cooldown=" + statistics.cooldown()
				+ ",stable=" + statistics.stable()
				+ ",queuedStable=" + statistics.queuedStable()
				+ ",inFlight=" + statistics.inFlight();
	}

	public synchronized Statistics statistics() {
		int quiet = 0;
		int active = 0;
		int storm = 0;
		int cooldown = 0;
		int stable = 0;
		for (DirtyChunkRecord record : records.values()) {
			switch (record.state()) {
				case QUIET:
					quiet++;
					break;
				case ACTIVE:
					active++;
					break;
				case STORM:
					storm++;
					break;
				case COOLDOWN:
					cooldown++;
					break;
				case STABLE:
					stable++;
					break;
				default:
					break;
			}
		}
		return new Statistics(records.size(), quiet, active, storm, cooldown, stable, stable - inFlight.size(), inFlight.size(), paused);
	}

	public synchronized void setPaused(boolean paused) {
		this.paused = paused;
	}

	void advance() {
		synchronized (this) {
			currentTick++;
			for (DirtyChunkRecord record : records.values()) {
				record.advance(currentTick);
			}
		}
	}

	private boolean hasCurrentClaim(StableDirtyChunk claimedChunk) {
		if (claimedChunk == null) {
			return false;
		}
		ClaimTicket ticket = inFlight.get(claimedChunk.key);
		return ticket != null
				&& ticket.claimId == claimedChunk.claimId
				&& ticket.generation == claimedChunk.generation
				&& generations.getOrDefault(claimedChunk.key, -1L) == claimedChunk.generation;
	}

	private static String key(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + ChunkPos.asLong(chunkX, chunkZ);
	}

	private static Path path(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("xaero-mapsync_r").resolve("dirty_chunks.json");
	}

	public static final class StableDirtyChunk {
		private final String key;
		private final long claimId;
		private final long generation;
		private final String dimension;
		private final int chunkX;
		private final int chunkZ;
		private final long[] dirtyColumns;

		private StableDirtyChunk(String key, long claimId, long generation, DirtyChunkRecord record) {
			this.key = key;
			this.claimId = claimId;
			this.generation = generation;
			this.dimension = record.dimension();
			this.chunkX = record.chunkX();
			this.chunkZ = record.chunkZ();
			this.dirtyColumns = record.dirtyColumnsAsLongArray();
		}

		public String dimension() {
			return dimension;
		}

		public int chunkX() {
			return chunkX;
		}

		public int chunkZ() {
			return chunkZ;
		}

		public long[] dirtyColumns() {
			return dirtyColumns.clone();
		}
	}

	public record Statistics(
			int total,
			int quiet,
			int active,
			int storm,
			int cooldown,
			int stable,
			int queuedStable,
			int inFlight,
			boolean paused) {
	}

	private static final class ClaimTicket {
		private final long claimId;
		private final long generation;

		private ClaimTicket(long claimId, long generation) {
			this.claimId = claimId;
			this.generation = generation;
		}
	}

	private static final class DirtyChunkFile {
		private DirtyRecordFile[] records;
	}

	private static final class DirtyRecordFile {
		private String dimension;
		private int chunkX;
		private int chunkZ;
		private DirtyActivityState state;
		private long firstDirtyTick;
		private long lastDirtyTick;
		private long[] dirtyColumns;
	}
}
