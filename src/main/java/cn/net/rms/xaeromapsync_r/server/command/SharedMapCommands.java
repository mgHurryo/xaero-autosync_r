package cn.net.rms.xaeromapsync_r.server.command;

import static net.minecraft.commands.Commands.literal;

import cn.net.rms.xaeromapsync_r.map.MapTileDebugRenderer;
import cn.net.rms.xaeromapsync_r.server.SharedMapServer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.network.chat.TextComponent;

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
					SharedMapServer.dirtyChunks().setPaused(true);
					context.getSource().sendSuccess(new TextComponent("Shared map heavy tasks paused."), true);
					return 1;
				}))
				.then(literal("resume").executes(context -> {
					SharedMapServer.dirtyChunks().setPaused(false);
					context.getSource().sendSuccess(new TextComponent("Shared map heavy tasks resumed."), true);
					return 1;
				}))
				.then(literal("flush").executes(context -> {
					int flushed = SharedMapServer.dirtyChunks().flushStableDirtyChunks();
					context.getSource().sendSuccess(new TextComponent("Flushed stable dirty chunks: " + flushed), true);
					return flushed;
				}))
				.then(literal("bandwidth")
						.then(net.minecraft.commands.Commands.argument("bytesPerTick", IntegerArgumentType.integer(1024, 1048576))
								.executes(context -> {
									int bytesPerTick = IntegerArgumentType.getInteger(context, "bytesPerTick");
									SharedMapServer.networkBudget().setBytesPerPlayerPerTick(bytesPerTick);
									context.getSource().sendSuccess(new TextComponent("Shared map per-player network budget set to " + bytesPerTick + " bytes/tick."), true);
									return bytesPerTick;
								})))
				.then(literal("rebuild-loaded").executes(context -> {
					int generated = MapTileDebugRenderer.renderAndIndexLoadedPlayerChunks(context.getSource().getServer(), SharedMapServer.mapTiles());
					context.getSource().sendSuccess(new TextComponent("Generated debug tiles for loaded chunks: " + generated), true);
					return generated;
				}))));
	}

	private static String statusMessage() {
		return "Shared map clients=" + SharedMapServer.acceptedClientCount()
				+ ", exploredChunks=" + SharedMapServer.exploredChunks().totalCount()
				+ ", mapTiles=" + SharedMapServer.mapTiles().totalCount()
				+ ", rootHash=" + Long.toUnsignedString(SharedMapServer.mapTiles().rootHash())
				+ ", dirtyChunks=" + SharedMapServer.dirtyChunks().totalCount()
				+ ", dirtyState=" + SharedMapServer.dirtyChunks().stateSummary()
				+ ", bandwidthBytesPerTick=" + SharedMapServer.networkBudget().bytesPerPlayerPerTick()
				+ ", publicWaypoints=" + SharedMapServer.waypoints().activeCount()
				+ ", deletedWaypoints=" + SharedMapServer.waypoints().deletedCount();
	}
}
