package cn.net.rms.xaeromapsync_r.client.gui;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.xaero.XaeroWaypointIdentity;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.GuiComponent;

public final class XaeroWaypointLockRenderer {
	private static final Map<Class<?>, Method> NAME_METHODS = new ConcurrentHashMap<>();
	private static final int LOCK_COLOR = 0xFFFFC94A;
	private static final int LOCK_HIGHLIGHT = 0xFFFFE18A;
	private static final int LOCK_SHADOW = 0xFF4A390C;
	private static final int KEYHOLE_COLOR = 0xFF2B2414;

	private XaeroWaypointLockRenderer() {
	}

	public static void render(PoseStack matrices, Object waypoint, int slotX, int slotY) {
		if (!isManaged(waypoint)) {
			return;
		}
		int x = slotX + 99;
		int y = slotY + 1;
		GuiComponent.fill(matrices, x + 2, y, x + 6, y + 1, LOCK_HIGHLIGHT);
		GuiComponent.fill(matrices, x + 1, y + 1, x + 2, y + 5, LOCK_COLOR);
		GuiComponent.fill(matrices, x + 6, y + 1, x + 7, y + 5, LOCK_SHADOW);
		GuiComponent.fill(matrices, x + 2, y + 1, x + 6, y + 2, LOCK_COLOR);
		GuiComponent.fill(matrices, x, y + 4, x + 8, y + 10, LOCK_COLOR);
		GuiComponent.fill(matrices, x, y + 9, x + 8, y + 10, LOCK_SHADOW);
		GuiComponent.fill(matrices, x + 3, y + 6, x + 5, y + 9, KEYHOLE_COLOR);
	}

	private static boolean isManaged(Object waypoint) {
		if (waypoint == null) {
			return false;
		}
		try {
			Method getName = NAME_METHODS.computeIfAbsent(waypoint.getClass(), type -> {
				try {
					return type.getMethod("getName");
				} catch (NoSuchMethodException exception) {
					throw new MissingNameMethod(exception);
				}
			});
			return XaeroWaypointIdentity.isManagedName((String) getName.invoke(waypoint));
		} catch (ReflectiveOperationException | MissingNameMethod exception) {
			XaeroMapsync_r.LOGGER.debug("Unable to inspect Xaero waypoint lock state", exception);
			return false;
		}
	}

	private static final class MissingNameMethod extends RuntimeException {
		private MissingNameMethod(NoSuchMethodException cause) {
			super(cause);
		}
	}
}
