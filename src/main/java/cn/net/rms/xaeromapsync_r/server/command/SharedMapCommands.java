package cn.net.rms.xaeromapsync_r.server.command;

import static net.minecraft.commands.Commands.literal;

import cn.net.rms.xaeromapsync_r.map.MapTileDebugRenderer;
import cn.net.rms.xaeromapsync_r.server.SharedMapServer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.network.chat.TextComponent;
import cn.net.rms.xaeromapsync_r.network.SharedMapNetworking;
import cn.net.rms.xaeromapsync_r.server.access.RegionAccessRule;
import cn.net.rms.xaeromapsync_r.server.access.SharedMapActor;
import cn.net.rms.xaeromapsync_r.server.access.SharedMapActors;
import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;

public final class SharedMapCommands {
	private SharedMapCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(literal("sharedmap")
				.requires(source -> source.hasPermission(2))
				.then(literal("status").executes(context -> {
					context.getSource().sendSuccess(new TextComponent(statusMessage()), false);
					return 1;
				}).then(net.minecraft.commands.Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
						.executes(context -> playerStatus(context.getSource(),
								net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player")))))
				.then(literal("trace")
						.then(net.minecraft.commands.Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
								.then(net.minecraft.commands.Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
										.executes(context -> enableTrace(context.getSource(),
												net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player"),
												IntegerArgumentType.getInteger(context, "seconds"))))))
				.then(literal("save").executes(context -> {
					SharedMapServer.waypoints().save(context.getSource().getServer());
					SharedMapServer.access().save(context.getSource().getServer());
					SharedMapServer.exploredChunks().save(context.getSource().getServer());
					SharedMapServer.dirtyChunks().save(context.getSource().getServer());
					SharedMapServer.mapTiles().save(context.getSource().getServer());
					context.getSource().sendSuccess(new TextComponent("Shared map state saved."), true);
					return 1;
				}))
				.then(literal("pause").executes(context -> {
					SharedMapServer.mapTasks().setPaused(true);
					context.getSource().sendSuccess(new TextComponent("Shared map heavy tasks paused."), true);
					return 1;
				}))
				.then(literal("resume").executes(context -> {
					SharedMapServer.mapTasks().setPaused(false);
					context.getSource().sendSuccess(new TextComponent("Shared map heavy tasks resumed."), true);
					return 1;
				}))
				.then(literal("flush").executes(context -> {
					SharedMapServer.mapTasks().requestDrain();
					int queued = SharedMapServer.dirtyChunks().statistics().queuedStable();
					context.getSource().sendSuccess(new TextComponent("Scheduled stable dirty chunks for budgeted processing: " + queued), true);
					return queued;
				}))
				.then(literal("bandwidth")
						.then(literal("player")
								.then(net.minecraft.commands.Commands.argument("bytesPerTick", IntegerArgumentType.integer(1024, 1048576))
										.executes(context -> setPlayerBandwidth(context.getSource(), IntegerArgumentType.getInteger(context, "bytesPerTick")))))
						.then(literal("global")
								.then(net.minecraft.commands.Commands.argument("bytesPerTick", IntegerArgumentType.integer(1024, 16777216))
										.executes(context -> setGlobalBandwidth(context.getSource(), IntegerArgumentType.getInteger(context, "bytesPerTick")))))
						.then(net.minecraft.commands.Commands.argument("bytesPerTick", IntegerArgumentType.integer(1024, 1048576))
								.executes(context -> setPlayerBandwidth(context.getSource(), IntegerArgumentType.getInteger(context, "bytesPerTick")))))
				.then(literal("region")
						.then(literal("status").executes(context -> {
							RegionKey key = currentRegion(context.getSource());
							String state = SharedMapServer.activity().get(key).map(record -> record.state().name()).orElse("QUIET");
							context.getSource().sendSuccess(new TextComponent("Shared map region " + key.regionX() + "," + key.regionZ()
									+ " state=" + state + ", paused=" + SharedMapServer.activity().isPaused(key)), false);
							return 1;
						}))
						.then(literal("pause").executes(context -> {
							RegionKey key = currentRegion(context.getSource());
							SharedMapServer.activity().pause(key);
							context.getSource().sendSuccess(new TextComponent("Paused shared map processing in current region."), true);
							return 1;
						}))
						.then(literal("resume").executes(context -> {
							SharedMapServer.activity().resume(currentRegion(context.getSource()));
							context.getSource().sendSuccess(new TextComponent("Resumed shared map processing in current region."), true);
							return 1;
						}))
						.then(literal("mark-storm").executes(context -> {
							SharedMapServer.activity().markStorm(currentRegion(context.getSource()));
							context.getSource().sendSuccess(new TextComponent("Marked current shared map region as STORM."), true);
							return 1;
						}))
						.then(literal("clear-storm").executes(context -> {
							SharedMapServer.activity().clear(currentRegion(context.getSource()));
							context.getSource().sendSuccess(new TextComponent("Cleared current shared map region activity state."), true);
							return 1;
						})))
				.then(literal("access")
						.then(literal("status").executes(context -> accessStatus(context.getSource())))
						.then(literal("grant")
								.then(net.minecraft.commands.Commands.argument("team", StringArgumentType.word())
										.executes(context -> grantTeam(context.getSource(), StringArgumentType.getString(context, "team")))))
						.then(literal("revoke")
								.then(net.minecraft.commands.Commands.argument("team", StringArgumentType.word())
										.executes(context -> revokeTeam(context.getSource(), StringArgumentType.getString(context, "team")))))
						.then(literal("clear")
								.then(net.minecraft.commands.Commands.argument("team", StringArgumentType.word())
										.executes(context -> clearTeam(context.getSource(), StringArgumentType.getString(context, "team")))))
						.then(literal("disable").executes(context -> setWaypointChangesDisabled(context.getSource(), true)))
						.then(literal("enable").executes(context -> setWaypointChangesDisabled(context.getSource(), false)))
						.then(literal("reset").executes(context -> resetAccess(context.getSource()))))
				.then(literal("waypoint")
						.then(literal("list").executes(context -> listWaypoints(context.getSource())))
						.then(literal("inspect")
								.then(net.minecraft.commands.Commands.argument("id", StringArgumentType.word())
										.executes(context -> inspectWaypoint(context.getSource(), StringArgumentType.getString(context, "id")))))
						.then(literal("delete")
								.then(net.minecraft.commands.Commands.argument("id", StringArgumentType.word())
										.executes(context -> deleteWaypoint(context.getSource(), StringArgumentType.getString(context, "id"))))))
				.then(literal("rebuild-loaded").executes(context -> {
					int generated = MapTileDebugRenderer.renderAndIndexLoadedPlayerChunks(context.getSource().getServer(),
							SharedMapServer.mapTiles(), SharedMapServer.tileData());
					context.getSource().sendSuccess(new TextComponent("Generated debug tiles for loaded chunks: " + generated), true);
					return generated;
				}))));
	}

	private static int accessStatus(net.minecraft.commands.CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		RegionKey key = currentRegion(source);
		SharedMapActor actor = actor(source);
		Optional<RegionAccessRule> rule = SharedMapServer.access().regions().get(key);
		String message = rule.map(value -> "defaultAllowed=" + value.defaultAllowed()
				+ ", disabled=" + value.waypointChangesDisabled()
				+ ", allowedTeams=" + value.allowedTeams()
				+ ", deniedTeams=" + value.deniedTeams()).orElse("defaultAllowed=true, no explicit rule");
		source.sendSuccess(new TextComponent("Shared map access " + describe(key) + ": " + message), false);
		SharedMapServer.access().audit().record(actor, "access.status", true, key, null, message);
		return 1;
	}

	private static int setPlayerBandwidth(net.minecraft.commands.CommandSourceStack source, int bytesPerTick) {
		SharedMapServer.networkBudget().setBytesPerPlayerPerTick(bytesPerTick);
		source.sendSuccess(new TextComponent("Shared map per-player network budget set to " + bytesPerTick + " bytes/tick."), true);
		return bytesPerTick;
	}

	private static int setGlobalBandwidth(net.minecraft.commands.CommandSourceStack source, int bytesPerTick) {
		SharedMapServer.networkBudget().setGlobalBytesPerTick(bytesPerTick);
		source.sendSuccess(new TextComponent("Shared map global network budget set to " + bytesPerTick + " bytes/tick."), true);
		return bytesPerTick;
	}

	private static int grantTeam(net.minecraft.commands.CommandSourceStack source, String teamName) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		RegionKey key = currentRegion(source);
		SharedMapActor actor = actor(source);
		try {
			SharedMapServer.access().regions().allowTeam(key, teamName);
			SharedMapServer.access().save(source.getServer());
			SharedMapServer.access().audit().record(actor, "access.grant", true, key, null, "team=" + teamName);
			source.sendSuccess(new TextComponent("Granted team " + teamName + " waypoint changes in " + describe(key) + "."), true);
			return 1;
		} catch (RuntimeException exception) {
			SharedMapServer.access().audit().record(actor, "access.grant", false, key, null, exception.getMessage());
			source.sendFailure(new TextComponent(exception.getMessage()));
			return 0;
		}
	}

	private static int revokeTeam(net.minecraft.commands.CommandSourceStack source, String teamName) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		RegionKey key = currentRegion(source);
		SharedMapActor actor = actor(source);
		try {
			SharedMapServer.access().regions().denyTeam(key, teamName);
			SharedMapServer.access().save(source.getServer());
			SharedMapServer.access().audit().record(actor, "access.revoke", true, key, null, "team=" + teamName);
			source.sendSuccess(new TextComponent("Revoked team " + teamName + " waypoint changes in " + describe(key) + "."), true);
			return 1;
		} catch (RuntimeException exception) {
			SharedMapServer.access().audit().record(actor, "access.revoke", false, key, null, exception.getMessage());
			source.sendFailure(new TextComponent(exception.getMessage()));
			return 0;
		}
	}

	private static int clearTeam(net.minecraft.commands.CommandSourceStack source, String teamName) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		RegionKey key = currentRegion(source);
		SharedMapActor actor = actor(source);
		try {
			SharedMapServer.access().regions().clearTeamRule(key, teamName);
			SharedMapServer.access().save(source.getServer());
			SharedMapServer.access().audit().record(actor, "access.clear", true, key, null, "team=" + teamName);
			source.sendSuccess(new TextComponent("Cleared explicit access for team " + teamName + " in " + describe(key) + "."), true);
			return 1;
		} catch (RuntimeException exception) {
			SharedMapServer.access().audit().record(actor, "access.clear", false, key, null, exception.getMessage());
			source.sendFailure(new TextComponent(exception.getMessage()));
			return 0;
		}
	}

	private static int setWaypointChangesDisabled(net.minecraft.commands.CommandSourceStack source, boolean disabled) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		RegionKey key = currentRegion(source);
		SharedMapActor actor = actor(source);
		SharedMapServer.access().regions().setWaypointChangesDisabled(key, disabled);
		SharedMapServer.access().save(source.getServer());
		SharedMapServer.access().audit().record(actor, disabled ? "access.disable" : "access.enable", true, key, null, null);
		source.sendSuccess(new TextComponent((disabled ? "Disabled" : "Enabled") + " waypoint changes in " + describe(key) + "."), true);
		return 1;
	}

	private static int resetAccess(net.minecraft.commands.CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		RegionKey key = currentRegion(source);
		SharedMapActor actor = actor(source);
		boolean changed = SharedMapServer.access().regions().reset(key);
		SharedMapServer.access().save(source.getServer());
		SharedMapServer.access().audit().record(actor, "access.reset", true, key, null, "changed=" + changed);
		source.sendSuccess(new TextComponent("Reset access in " + describe(key) + " to creator/OP defaults."), true);
		return changed ? 1 : 0;
	}

	private static int listWaypoints(net.minecraft.commands.CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		SharedMapActor actor = actor(source);
		int active = 0;
		int shown = 0;
		for (PublicWaypoint waypoint : SharedMapServer.waypoints().snapshot()) {
			if (waypoint.deleted()) {
				continue;
			}
			active++;
			if (shown++ < 20) {
				source.sendSuccess(new TextComponent(waypoint.id() + " " + waypoint.name() + " visibility=" + waypoint.visibility()
						+ " team=" + waypoint.teamName() + " creator=" + waypoint.creatorName()), false);
			}
		}
		SharedMapServer.access().audit().record(actor, "waypoint.list", true, null, null, "active=" + active);
		source.sendSuccess(new TextComponent("Shared waypoints active=" + active + ", showing=" + Math.min(active, 20) + "."), false);
		return active;
	}

	private static int inspectWaypoint(net.minecraft.commands.CommandSourceStack source, String id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		SharedMapActor actor = actor(source);
		UUID waypointId = parseUuid(source, id);
		if (waypointId == null) {
			return 0;
		}
		Optional<PublicWaypoint> found = SharedMapServer.waypoints().find(waypointId);
		if (found.isEmpty()) {
			SharedMapServer.access().audit().record(actor, "waypoint.inspect", false, null, waypointId, "not found");
			source.sendFailure(new TextComponent("Shared waypoint not found."));
			return 0;
		}
		PublicWaypoint waypoint = found.get();
		RegionKey region = SharedMapServer.permissions().regionOf(waypoint);
		source.sendSuccess(new TextComponent("Waypoint " + waypoint.id() + " name=" + waypoint.name()
				+ ", dimension=" + waypoint.dimension() + ", position=" + waypoint.x() + "," + waypoint.y() + "," + waypoint.z()
				+ ", visibility=" + waypoint.visibility() + ", team=" + waypoint.teamName()
				+ ", creator=" + waypoint.creatorName() + ", revision=" + waypoint.revision() + ", deleted=" + waypoint.deleted()), false);
		SharedMapServer.access().audit().record(actor, "waypoint.inspect", true, region, waypointId, null);
		return 1;
	}

	private static int deleteWaypoint(net.minecraft.commands.CommandSourceStack source, String id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		SharedMapActor actor = actor(source);
		UUID waypointId = parseUuid(source, id);
		if (waypointId == null) {
			return 0;
		}
		Optional<PublicWaypoint> found = SharedMapServer.waypoints().find(waypointId);
		if (found.isEmpty()) {
			SharedMapServer.access().audit().record(actor, "waypoint.admin_delete", false, null, waypointId, "not found");
			source.sendFailure(new TextComponent("Shared waypoint not found."));
			return 0;
		}
		PublicWaypoint current = found.get();
		RegionKey region = SharedMapServer.permissions().regionOf(current);
		try {
			SharedMapServer.permissions().validateDelete(actor, current);
			PublicWaypoint tombstone = SharedMapServer.waypoints().delete(waypointId, System.currentTimeMillis());
			if (tombstone == null) {
				throw new IllegalStateException("Waypoint no longer exists");
			}
			SharedMapServer.waypoints().save(source.getServer());
			SharedMapNetworking.broadcastWaypointDelete(source.getServer(), current, tombstone);
			SharedMapServer.access().audit().record(actor, "waypoint.admin_delete", true, region, waypointId, "revision=" + tombstone.revision());
			source.sendSuccess(new TextComponent("Deleted shared waypoint " + waypointId + "."), true);
			return 1;
		} catch (RuntimeException exception) {
			SharedMapServer.access().audit().record(actor, "waypoint.admin_delete", false, region, waypointId, exception.getMessage());
			source.sendFailure(new TextComponent(exception.getMessage()));
			return 0;
		}
	}

	private static UUID parseUuid(net.minecraft.commands.CommandSourceStack source, String value) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			source.sendFailure(new TextComponent("Invalid waypoint UUID."));
			return null;
		}
	}

	private static SharedMapActor actor(net.minecraft.commands.CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
			return SharedMapActors.from(player);
		}
		return new SharedMapActor(null, source.getTextName(), null, true);
	}

	private static String describe(RegionKey key) {
		return key.dimension() + " region " + key.regionX() + "," + key.regionZ();
	}

	private static RegionKey currentRegion(net.minecraft.commands.CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		net.minecraft.server.level.ServerPlayer player = source.getPlayerOrException();
		return RegionKey.fromBlock(player.getLevel().dimension().location().toString(), player.getBlockX(), player.getBlockZ());
	}

	private static String statusMessage() {
		return "Shared map clients=" + SharedMapServer.acceptedClientCount()
				+ ", mapSyncEnabled=" + cn.net.rms.xaeromapsync_r.config.SharedMapConfig.mapSyncEnabled()
				+ ", shadowMode=" + cn.net.rms.xaeromapsync_r.config.SharedMapConfig.mapSyncShadowMode()
				+ ", exploredChunks=" + SharedMapServer.exploredChunks().totalCount()
				+ ", mapTiles=" + SharedMapServer.mapTiles().totalCount()
				+ ", rootHash=" + Long.toUnsignedString(SharedMapServer.mapTiles().rootHash())
				+ ", dirtyChunks=" + SharedMapServer.dirtyChunks().totalCount()
				+ ", dirtyState=" + SharedMapServer.dirtyChunks().stateSummary()
				+ ", lastMspt=" + format(SharedMapServer.mapTasks().lastMspt())
				+ ", averageMspt=" + format(SharedMapServer.mapTasks().averageMspt())
				+ ", p95Mspt=" + format(SharedMapServer.mapTasks().p95Mspt())
				+ ", lastMapTaskMs=" + format(SharedMapServer.mapTasks().lastTaskMillis())
				+ ", completedTiles=" + SharedMapServer.mapTasks().completedTiles()
				+ ", mapTasksPaused=" + SharedMapServer.mapTasks().paused()
				+ ", activityRegions=" + SharedMapServer.activity().statistics().total()
				+ ", stormRegions=" + SharedMapServer.activity().statistics().storm()
				+ ", cooldownRegions=" + SharedMapServer.activity().statistics().cooldown()
				+ ", pendingTransfers=" + SharedMapServer.transfers().pendingCount()
				+ ", bandwidthPlayerBudget=" + SharedMapServer.networkBudget().bytesPerPlayerPerTick()
				+ ", bandwidthGlobalBudget=" + SharedMapServer.networkBudget().globalBytesPerTick()
				+ ", bandwidthLastTick=" + SharedMapServer.networkBudget().lastCompletedTickBytes()
				+ ", bandwidthP95=" + SharedMapServer.networkBudget().p95BytesPerTick()
				+ ", bandwidthTotal=" + SharedMapServer.networkBudget().totalBytes()
				+ ", bandwidthRejected=" + SharedMapServer.networkBudget().rejectedBytes()
				+ ", publicWaypoints=" + SharedMapServer.waypoints().activeCount()
				+ ", deletedWaypoints=" + SharedMapServer.waypoints().deletedCount();
	}

	private static int playerStatus(net.minecraft.commands.CommandSourceStack source,
			net.minecraft.server.level.ServerPlayer player) {
		String dimension = player.getLevel().dimension().location().toString();
		String state = SharedMapServer.clientState(player.getUUID()).map(value -> "accepted=" + value.accepted()
				+ ", traceId=" + value.traceId() + ", connectedMs=" + (System.currentTimeMillis() - value.connectedAtMillis()))
				.orElse("not-handshaken");
		int publishedPatches = SharedMapServer.patches().manifests(dimension).size();
		source.sendSuccess(new TextComponent("Shared map player=" + player.getGameProfile().getName() + ", " + state
				+ ", dimension=" + dimension + ", chunk=" + player.chunkPosition().x + "," + player.chunkPosition().z
				+ ", publishedPatches=" + publishedPatches + ", traceEnabled="
				+ SharedMapServer.traceEnabled(player.getUUID())), false);
		return 1;
	}

	private static int enableTrace(net.minecraft.commands.CommandSourceStack source,
			net.minecraft.server.level.ServerPlayer player, int seconds) {
		SharedMapServer.enableTrace(player.getUUID(), seconds);
		UUID traceId = SharedMapServer.clientState(player.getUUID()).map(value -> value.traceId()).orElse(null);
		source.sendSuccess(new TextComponent("Enabled detailed shared map trace for "
				+ player.getGameProfile().getName() + " for " + seconds + " seconds; traceId=" + traceId + "."), true);
		return seconds;
	}

	private static String format(double value) {
		return String.format(java.util.Locale.ROOT, "%.2f", value);
	}
}
