package cn.net.rms.xaeromapsync_r.mixin;

import cn.net.rms.xaeromapsync_r.client.gui.XaeroWaypointLockRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "xaero.common.gui.GuiWaypoints$List", remap = false)
public abstract class XaeroWaypointListMixin {
	@Inject(method = "drawWaypointSlot", at = @At("TAIL"), remap = false, require = 0)
	private void xaeroMapsync$renderLockIcon(PoseStack matrices, @Coerce Object waypoint, int slotX, int slotY,
			CallbackInfo callbackInfo) {
		XaeroWaypointLockRenderer.render(matrices, waypoint, slotX, slotY);
	}
}
