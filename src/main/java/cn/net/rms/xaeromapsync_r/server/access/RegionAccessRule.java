package cn.net.rms.xaeromapsync_r.server.access;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class RegionAccessRule {
	private final boolean defaultAllowed;
	private final boolean waypointChangesDisabled;
	private final Set<String> allowedTeams;
	private final Set<String> deniedTeams;

	RegionAccessRule(boolean defaultAllowed, boolean waypointChangesDisabled, Set<String> allowedTeams, Set<String> deniedTeams) {
		this.defaultAllowed = defaultAllowed;
		this.waypointChangesDisabled = waypointChangesDisabled;
		this.allowedTeams = Collections.unmodifiableSet(new LinkedHashSet<>(allowedTeams));
		this.deniedTeams = Collections.unmodifiableSet(new LinkedHashSet<>(deniedTeams));
	}

	public boolean defaultAllowed() {
		return defaultAllowed;
	}

	public boolean waypointChangesDisabled() {
		return waypointChangesDisabled;
	}

	public Set<String> allowedTeams() {
		return allowedTeams;
	}

	public Set<String> deniedTeams() {
		return deniedTeams;
	}
}
