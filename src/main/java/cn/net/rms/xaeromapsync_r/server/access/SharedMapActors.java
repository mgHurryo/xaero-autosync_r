package cn.net.rms.xaeromapsync_r.server.access;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Team;

public final class SharedMapActors {
	private SharedMapActors() {
	}

	public static SharedMapActor from(ServerPlayer player) {
		Team team = player.getTeam();
		return new SharedMapActor(
				player.getUUID(),
				player.getGameProfile().getName(),
				team == null ? null : team.getName(),
				player.hasPermissions(2));
	}
}
