package cn.net.rms.xaeromapsync_r.mixin;

import cn.net.rms.xaeromapsync_r.server.SharedMapServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
public abstract class PistonActivityMixin {
	@Inject(
			method = "moveBlocks(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)Z",
			at = @At("RETURN"))
	private void xaeroMapSync$recordPistonAction(
			Level level,
			BlockPos pos,
			Direction direction,
			boolean extending,
			CallbackInfoReturnable<Boolean> callbackInfo) {
		if (!callbackInfo.getReturnValueZ() || !(level instanceof ServerLevel)) {
			return;
		}
		ServerLevel serverLevel = (ServerLevel) level;
		SharedMapServer.recordPistonAction(
				serverLevel.dimension().location().toString(),
				pos.getX(),
				pos.getZ());
	}
}
