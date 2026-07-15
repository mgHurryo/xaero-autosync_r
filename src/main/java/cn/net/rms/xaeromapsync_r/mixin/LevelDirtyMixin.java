package cn.net.rms.xaeromapsync_r.mixin;

import cn.net.rms.xaeromapsync_r.server.SharedMapServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelDirtyMixin {
	@Inject(method = "setBlock", at = @At("RETURN"))
	private void xaeroMapSync$markDirty(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> callbackInfo) {
		if (!callbackInfo.getReturnValueZ() || !((Object) this instanceof ServerLevel)) {
			return;
		}
		ServerLevel level = (ServerLevel) (Object) this;
		SharedMapServer.recordBlockChange(level.dimension().location().toString(), pos);
	}
}
