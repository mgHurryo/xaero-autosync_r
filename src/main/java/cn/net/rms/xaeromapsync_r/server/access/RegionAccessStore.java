package cn.net.rms.xaeromapsync_r.server.access;

import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Persistent allow/deny policy for dimension-scoped 8x8 chunk regions. */
public final class RegionAccessStore {
	private static final int FILE_VERSION = 1;
	private static final int MAX_TEAM_NAME_LENGTH = 64;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Map<RegionKey, MutableRule> rules = new LinkedHashMap<>();

	public synchronized RegionAccessDecision decision(RegionKey key, String teamName) {
		MutableRule rule = rules.get(Objects.requireNonNull(key, "key"));
		if (rule == null) {
			return RegionAccessDecision.ALLOWED;
		}
		if (rule.waypointChangesDisabled) {
			return RegionAccessDecision.DENIED_BY_REGION_DISABLE;
		}
		if (teamName != null && rule.deniedTeams.contains(teamName)) {
			return RegionAccessDecision.DENIED_BY_TEAM;
		}
		if (teamName != null && rule.allowedTeams.contains(teamName)) {
			return RegionAccessDecision.ALLOWED;
		}
		return rule.defaultAllowed ? RegionAccessDecision.ALLOWED : RegionAccessDecision.DENIED_NOT_ALLOWLISTED;
	}

	/** Adding the first allow entry changes the region to an explicit team allowlist. */
	public synchronized void allowTeam(RegionKey key, String teamName) {
		MutableRule rule = rule(key);
		String validatedTeam = validateTeamName(teamName);
		rule.defaultAllowed = false;
		rule.deniedTeams.remove(validatedTeam);
		rule.allowedTeams.add(validatedTeam);
	}

	public synchronized void denyTeam(RegionKey key, String teamName) {
		MutableRule rule = rule(key);
		String validatedTeam = validateTeamName(teamName);
		rule.allowedTeams.remove(validatedTeam);
		rule.deniedTeams.add(validatedTeam);
	}

	public synchronized void clearTeamRule(RegionKey key, String teamName) {
		MutableRule rule = rules.get(Objects.requireNonNull(key, "key"));
		if (rule == null) {
			return;
		}
		String validatedTeam = validateTeamName(teamName);
		rule.allowedTeams.remove(validatedTeam);
		rule.deniedTeams.remove(validatedTeam);
	}

	public synchronized void setWaypointChangesDisabled(RegionKey key, boolean disabled) {
		rule(key).waypointChangesDisabled = disabled;
	}

	public synchronized boolean reset(RegionKey key) {
		return rules.remove(Objects.requireNonNull(key, "key")) != null;
	}

	public synchronized Optional<RegionAccessRule> get(RegionKey key) {
		MutableRule rule = rules.get(Objects.requireNonNull(key, "key"));
		return rule == null ? Optional.empty() : Optional.of(rule.snapshot());
	}

	public synchronized int size() {
		return rules.size();
	}

	public synchronized void load(Path path) throws IOException {
		rules.clear();
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			AccessFile file = GSON.fromJson(reader, AccessFile.class);
			if (file == null || file.rules == null) {
				return;
			}
			if (file.version > FILE_VERSION) {
				throw new IOException("Unsupported region access file version: " + file.version);
			}
			for (RuleFile stored : file.rules) {
				if (stored == null) {
					continue;
				}
				RegionKey key = new RegionKey(stored.dimension, stored.regionX, stored.regionZ);
				MutableRule rule = new MutableRule(stored.defaultAllowed);
				rule.waypointChangesDisabled = stored.waypointChangesDisabled;
				copyTeams(stored.allowedTeams, rule.allowedTeams);
				copyTeams(stored.deniedTeams, rule.deniedTeams);
				rule.allowedTeams.removeAll(rule.deniedTeams);
				rules.put(key, rule);
			}
		}
	}

	public synchronized void save(Path path) throws IOException {
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		AccessFile file = new AccessFile();
		file.version = FILE_VERSION;
		file.rules = new RuleFile[rules.size()];
		int index = 0;
		for (Map.Entry<RegionKey, MutableRule> entry : rules.entrySet()) {
			file.rules[index++] = RuleFile.from(entry.getKey(), entry.getValue());
		}
		Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(tempPath)) {
			GSON.toJson(file, writer);
		}
		try {
			Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private MutableRule rule(RegionKey key) {
		return rules.computeIfAbsent(Objects.requireNonNull(key, "key"), ignored -> new MutableRule(true));
	}

	private static void copyTeams(String[] source, Set<String> target) {
		if (source == null) {
			return;
		}
		for (String teamName : source) {
			try {
				target.add(validateTeamName(teamName));
			} catch (IllegalArgumentException ignored) {
				// Ignore malformed persisted subjects while retaining the rest of the file.
			}
		}
	}

	private static String validateTeamName(String teamName) {
		if (teamName == null || teamName.isBlank() || teamName.length() > MAX_TEAM_NAME_LENGTH) {
			throw new IllegalArgumentException("Scoreboard team name must contain 1-64 characters");
		}
		return teamName;
	}

	private static final class MutableRule {
		private boolean defaultAllowed;
		private boolean waypointChangesDisabled;
		private final Set<String> allowedTeams = new LinkedHashSet<>();
		private final Set<String> deniedTeams = new LinkedHashSet<>();

		private MutableRule(boolean defaultAllowed) {
			this.defaultAllowed = defaultAllowed;
		}

		private RegionAccessRule snapshot() {
			return new RegionAccessRule(defaultAllowed, waypointChangesDisabled, allowedTeams, deniedTeams);
		}
	}

	private static final class AccessFile {
		private int version;
		private RuleFile[] rules;
	}

	private static final class RuleFile {
		private String dimension;
		private int regionX;
		private int regionZ;
		private boolean defaultAllowed;
		private boolean waypointChangesDisabled;
		private String[] allowedTeams;
		private String[] deniedTeams;

		private static RuleFile from(RegionKey key, MutableRule rule) {
			RuleFile file = new RuleFile();
			file.dimension = key.dimension();
			file.regionX = key.regionX();
			file.regionZ = key.regionZ();
			file.defaultAllowed = rule.defaultAllowed;
			file.waypointChangesDisabled = rule.waypointChangesDisabled;
			file.allowedTeams = rule.allowedTeams.toArray(new String[0]);
			file.deniedTeams = rule.deniedTeams.toArray(new String[0]);
			return file;
		}
	}
}
