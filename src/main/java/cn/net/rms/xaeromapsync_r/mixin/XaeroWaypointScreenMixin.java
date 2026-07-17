package cn.net.rms.xaeromapsync_r.mixin;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentSkipListSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "xaero.common.gui.GuiWaypoints", remap = false)
public abstract class XaeroWaypointScreenMixin {
	@Shadow(remap = false)
	private ConcurrentSkipListSet<Integer> selectedListSet;

	@Inject(method = "getSelectedWaypointsList", at = @At("RETURN"), remap = false, require = 0)
	private void xaeroMapsync$removeStaleSelection(CallbackInfoReturnable<ArrayList<Object>> callbackInfo) {
		ArrayList<Object> selectedWaypoints = callbackInfo.getReturnValue();
		if (selectedWaypoints != null && selectedWaypoints.removeIf(java.util.Objects::isNull)) {
			selectedListSet.clear();
		}
	}
}
