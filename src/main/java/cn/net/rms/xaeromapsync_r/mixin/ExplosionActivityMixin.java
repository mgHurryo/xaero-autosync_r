package cn.net.rms.xaeromapsync_r.mixin;

import cn.net.rms.xaeromapsync_r.server.SharedMapServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Explosion.class)
public abstract class ExplosionActivityMixin {
	@Shadow @Final private Level level;
	@Shadow @Final private double x;
	@Shadow @Final private double z;

	@Inject(method = "explode", at = @At("HEAD"))
	private void xaeroMapSync$recordExplosion(CallbackInfo callbackInfo) {
		if (!(level instanceof ServerLevel)) {
			return;
		}
		ServerLevel serverLevel = (ServerLevel) level;
		SharedMapServer.recordExplosion(
				serverLevel.dimension().location().toString(),
				Mth.floor(x),
				Mth.floor(z));
	}
}
