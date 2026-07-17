package cn.net.rms.xaeromapsync_r.mixin;

import cn.net.rms.xaeromapsync_r.server.SharedMapServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ThreadedLevelLightEngine.class)
public abstract class LightEngineActivityMixin {
	@Shadow @Final private ChunkMap chunkMap;

	@Inject(method = "checkBlock", at = @At("HEAD"))
	private void xaeroMapSync$recordLightUpdate(BlockPos pos, CallbackInfo callbackInfo) {
		ServerLevel level = ((ChunkMapAccessor) chunkMap).xaeroMapSync$getLevel();
		SharedMapServer.recordLightUpdate(
				level.dimension().location().toString(),
				pos.getX(),
				pos.getZ());
	}
}
