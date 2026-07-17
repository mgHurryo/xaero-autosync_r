package cn.net.rms.xaeromapsync_r.mixin;

import cn.net.rms.xaeromapsync_r.server.SharedMapServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PrimedTnt.class)
public abstract class PrimedTntActivityMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void xaeroMapSync$recordTntDensity(CallbackInfo callbackInfo) {
		PrimedTnt tnt = (PrimedTnt) (Object) this;
		if (!(tnt.level instanceof ServerLevel)) {
			return;
		}
		ServerLevel level = (ServerLevel) tnt.level;
		BlockPos pos = tnt.blockPosition();
		SharedMapServer.recordTntEntity(
				level.dimension().location().toString(),
				pos.getX(),
				pos.getZ());
	}
}
