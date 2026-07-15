package cn.net.rms.xaeromapsync_r.server.command;

import static net.minecraft.commands.Commands.literal;

import cn.net.rms.xaeromapsync_r.map.MapTileDebugRenderer;
import cn.net.rms.xaeromapsync_r.server.SharedMapServer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.network.chat.TextComponent;
import cn.net.rms.xaeromapsync_r.server.activity.RegionKey;

public final class SharedMapCommands {
	private SharedMapCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(literal("sharedmap")
				.requires(source -> source.hasPermission(2))
				.then(literal("status").executes(context -> {
					context.getSource().sendSuccess(new TextComponent(statusMessage()), false);
					return 1;
				}))
				.then(literal("save").executes(context -> {
					SharedMapServer.waypoints().save(context.getSource().getServer());
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
						.then(net.minecraft.commands.Commands.argument("bytesPerTick", IntegerArgumentType.integer(1024, 1048576))
								.executes(context -> {
									int bytesPerTick = IntegerArgumentType.getInteger(context, "bytesPerTick");
									SharedMapServer.networkBudget().setBytesPerPlayerPerTick(bytesPerTick);
									context.getSource().sendSuccess(new TextComponent("Shared map per-player network budget set to " + bytesPerTick + " bytes/tick."), true);
									return bytesPerTick;
								})))
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
				.then(literal("rebuild-loaded").executes(context -> {
					int generated = MapTileDebugRenderer.renderAndIndexLoadedPlayerChunks(context.getSource().getServer(),
							SharedMapServer.mapTiles(), SharedMapServer.tileData());
					context.getSource().sendSuccess(new TextComponent("Generated debug tiles for loaded chunks: " + generated), true);
					return generated;
				}))));
	}

	private static RegionKey currentRegion(net.minecraft.commands.CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		net.minecraft.server.level.ServerPlayer player = source.getPlayerOrException();
		return RegionKey.fromBlock(player.getLevel().dimension().location().toString(), player.getBlockX(), player.getBlockZ());
	}

	private static String statusMessage() {
		return "Shared map clients=" + SharedMapServer.acceptedClientCount()
				+ ", exploredChunks=" + SharedMapServer.exploredChunks().totalCount()
				+ ", mapTiles=" + SharedMapServer.mapTiles().totalCount()
				+ ", rootHash=" + Long.toUnsignedString(SharedMapServer.mapTiles().rootHash())
				+ ", dirtyChunks=" + SharedMapServer.dirtyChunks().totalCount()
				+ ", dirtyState=" + SharedMapServer.dirtyChunks().stateSummary()
				+ ", averageMspt=" + String.format(java.util.Locale.ROOT, "%.2f", SharedMapServer.mapTasks().averageMspt())
				+ ", mapTasksPaused=" + SharedMapServer.mapTasks().paused()
				+ ", activityRegions=" + SharedMapServer.activity().statistics().total()
				+ ", stormRegions=" + SharedMapServer.activity().statistics().storm()
				+ ", cooldownRegions=" + SharedMapServer.activity().statistics().cooldown()
				+ ", pendingTransfers=" + SharedMapServer.transfers().pendingCount()
				+ ", bandwidthBytesPerTick=" + SharedMapServer.networkBudget().bytesPerPlayerPerTick()
				+ ", publicWaypoints=" + SharedMapServer.waypoints().activeCount()
				+ ", deletedWaypoints=" + SharedMapServer.waypoints().deletedCount();
	}
}
