package cn.net.rms.xaeromapsync_r.client.gui;

import cn.net.rms.xaeromapsync_r.XaeroMapsync_r;
import cn.net.rms.xaeromapsync_r.client.SharedMapClient;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;

public final class XaeroWaypointScreenIntegration {
	private static final String XAERO_WAYPOINT_SCREEN_CLASS = "xaero.common.gui.GuiWaypoints";
	private static final int BUTTON_HEIGHT = 20;
	private static final int BOTTOM_BAR_CLEARANCE = 78;
	private static final int HORIZONTAL_MARGIN = 6;
	private static final int MAX_ROW_WIDTH = 308;
	private static final List<String> LOCKED_XAERO_BUTTON_FIELDS = List.of(
			"deleteButton", "editButton", "disableEnableButton", "clearButton", "shareButton");

	private XaeroWaypointScreenIntegration() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!XAERO_WAYPOINT_SCREEN_CLASS.equals(screen.getClass().getName())) {
				return;
			}
			addWaypointActions(client, screen, scaledWidth, scaledHeight);
		});
	}

	private static void addWaypointActions(net.minecraft.client.Minecraft client, Screen screen, int screenWidth,
			int screenHeight) {
		List<Button> actions = new ArrayList<>();
		Button status = actionButton(0, 0, 1, "screen.xaero-mapsync_r.share.status.not_shared", button -> {});
		status.active = false;
		actions.add(status);
		Button publicButton = actionButton(0, 0, 1, "screen.xaero-mapsync_r.share.public",
				button -> SharedMapClient.shareSelectedXaeroWaypoint(screen, WaypointVisibility.PUBLIC));
		actions.add(publicButton);
		Button teamButton = actionButton(0, 0, 1, "screen.xaero-mapsync_r.share.team",
				button -> SharedMapClient.shareSelectedXaeroWaypoint(screen, WaypointVisibility.TEAM));
		teamButton.visible = client.player != null && client.player.getTeam() != null;
		actions.add(teamButton);
		Button unshareButton = actionButton(0, 0, 1, "screen.xaero-mapsync_r.share.remove",
				button -> confirmDelete(screen));
		actions.add(unshareButton);
		List<LockableButton> lockedXaeroButtons = lockedXaeroButtons(screen);

		int availableWidth = Math.max(3, screenWidth - HORIZONTAL_MARGIN * 2);
		int rowWidth = Math.min(Math.max(MAX_ROW_WIDTH, actions.size() * 96), availableWidth);
		int gap = rowWidth >= 152 ? 4 : 2;
		int buttonWidth = Math.max(1, (rowWidth - gap * (actions.size() - 1)) / actions.size());
		int usedWidth = buttonWidth * actions.size() + gap * (actions.size() - 1);
		int left = (screenWidth - usedWidth) / 2;
		int top = Math.max(4, screenHeight - BOTTOM_BAR_CLEARANCE);

		for (int index = 0; index < actions.size(); index++) {
			Button button = actions.get(index);
			button.x = left + index * (buttonWidth + gap);
			button.y = top;
			button.setWidth(buttonWidth);
			Screens.getButtons(screen).add(button);
		}
		ScreenEvents.afterRender(screen).register((renderedScreen, matrices, mouseX, mouseY, tickDelta) ->
				updateStatus(status, publicButton, teamButton, unshareButton, lockedXaeroButtons, renderedScreen));
	}

	private static void updateStatus(Button status, Button publicButton, Button teamButton, Button unshareButton,
			List<LockableButton> lockedXaeroButtons, Screen screen) {
		Optional<WaypointVisibility> visibility = SharedMapClient.selectedXaeroWaypointVisibility(screen);
		boolean selected = visibility.isPresent();
		boolean shared = visibility.filter(value -> value == WaypointVisibility.PUBLIC
				|| value == WaypointVisibility.TEAM).isPresent();
		String key = visibility.map(value -> value == WaypointVisibility.TEAM
				? "screen.xaero-mapsync_r.share.status.team"
				: value == WaypointVisibility.PUBLIC
						? "screen.xaero-mapsync_r.share.status.public"
						: "screen.xaero-mapsync_r.share.status.not_shared")
				.orElse("screen.xaero-mapsync_r.share.status.select");
		status.setMessage(new TranslatableComponent(key));
		publicButton.active = selected && !shared;
		boolean hasTeam = net.minecraft.client.Minecraft.getInstance().player != null
				&& net.minecraft.client.Minecraft.getInstance().player.getTeam() != null;
		teamButton.visible = hasTeam;
		teamButton.active = hasTeam && selected && !shared;
		unshareButton.active = shared;
		for (LockableButton button : lockedXaeroButtons) {
			button.setLocked(screen, shared);
		}
	}

	private static List<LockableButton> lockedXaeroButtons(Screen screen) {
		List<LockableButton> result = new ArrayList<>();
		for (String fieldName : LOCKED_XAERO_BUTTON_FIELDS) {
			try {
				Field field = screen.getClass().getDeclaredField(fieldName);
				field.setAccessible(true);
				Button original = (Button) field.get(screen);
				Button locked = new Button(original.x, original.y, original.getWidth(), BUTTON_HEIGHT,
						original.getMessage(), button -> {});
				locked.active = false;
				result.add(new LockableButton(original, locked));
			} catch (ReflectiveOperationException | ClassCastException exception) {
				XaeroMapsync_r.LOGGER.warn("Unable to lock Xaero waypoint button {}", fieldName, exception);
			}
		}
		return result;
	}

	private static Button actionButton(int x, int y, int width, String translationKey, Button.OnPress onPress) {
		return new Button(x, y, width, BUTTON_HEIGHT, new TranslatableComponent(translationKey), onPress);
	}

	private static void confirmDelete(Screen parent) {
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		minecraft.setScreen(new ConfirmScreen(confirmed -> {
			minecraft.setScreen(parent);
			if (confirmed) {
				SharedMapClient.unshareSelectedXaeroWaypoint(parent);
			}
		}, new TranslatableComponent("screen.xaero-mapsync_r.share.delete_confirm.title"),
				new TranslatableComponent("screen.xaero-mapsync_r.share.delete_confirm.message")));
	}

	private static final class LockableButton {
		private final Button original;
		private final Button locked;

		private LockableButton(Button original, Button locked) {
			this.original = original;
			this.locked = locked;
		}

		private void setLocked(Screen screen, boolean lock) {
			List<AbstractWidget> buttons = Screens.getButtons(screen);
			if (lock) {
				locked.setMessage(original.getMessage());
				buttons.remove(original);
				if (!buttons.contains(locked)) {
					buttons.add(locked);
				}
			} else if (!buttons.contains(original)) {
				buttons.remove(locked);
				buttons.add(original);
			}
		}
	}
}
