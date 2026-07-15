package cn.net.rms.xaeromapsync_r.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import cn.net.rms.xaeromapsync_r.client.SharedMapClient;
import cn.net.rms.xaeromapsync_r.client.SharedMapClientConfig;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;

public final class SharedMapScreen extends Screen {
	private static final int SIDE_MARGIN = 12;
	private static final int CONTROL_HEIGHT = 20;
	private static final int LIST_TOP = 148;
	private final SharedMapClientConfig config = SharedMapClientConfig.get();
	private EditBox searchBox;
	private PublicWaypointList waypointList;
	private long waypointSnapshotSignature = Long.MIN_VALUE;

	public SharedMapScreen() {
		super(new TranslatableComponent("screen.xaero-mapsync_r.title"));
	}

	@Override
	protected void init() {
		int contentWidth = Math.min(560, width - SIDE_MARGIN * 2);
		int left = (width - contentWidth) / 2;
		int gap = 4;
		int buttonWidth = (contentWidth - gap * 2) / 3;

		addToggle(left, 68, buttonWidth, "map_sync", config.mapSyncEnabled(),
				enabled -> config.setMapSyncEnabled(enabled));
		addToggle(left + buttonWidth + gap, 68, buttonWidth, "public_waypoints", config.publicWaypointsEnabled(),
				enabled -> config.setPublicWaypointsEnabled(enabled));
		addToggle(left + (buttonWidth + gap) * 2, 68, buttonWidth, "notifications", config.notificationsEnabled(),
				enabled -> config.setNotificationsEnabled(enabled));

		int actionWidth = (contentWidth - gap * 2) / 3;
		addRenderableWidget(new Button(left, 96, actionWidth, CONTROL_HEIGHT,
				new TranslatableComponent("screen.xaero-mapsync_r.add"), button -> minecraft.setScreen(new WaypointEditScreen(this, null))));
		addRenderableWidget(new Button(left + actionWidth + gap, 96, actionWidth, CONTROL_HEIGHT,
				new TranslatableComponent("screen.xaero-mapsync_r.edit"), button -> editSelected()));
		addRenderableWidget(new Button(left + (actionWidth + gap) * 2, 96, actionWidth, CONTROL_HEIGHT,
				new TranslatableComponent("screen.xaero-mapsync_r.delete"), button -> deleteSelected()));

		searchBox = new EditBox(font, left, 122, contentWidth, CONTROL_HEIGHT,
				new TranslatableComponent("screen.xaero-mapsync_r.search"));
		searchBox.setMaxLength(96);
		searchBox.setSuggestion(new TranslatableComponent("screen.xaero-mapsync_r.search").getString());
		searchBox.setResponder(query -> refreshWaypoints(query, false));
		addRenderableWidget(searchBox);

		int listBottom = Math.max(LIST_TOP + 24, height - 32);
		waypointList = new PublicWaypointList(minecraft, contentWidth, height, LIST_TOP, listBottom, 38);
		waypointList.setLeftPos(left);
		addRenderableWidget(waypointList);
		refreshWaypoints("", false);

		addRenderableWidget(new Button(width / 2 - 50, height - 27, 100, CONTROL_HEIGHT,
				new TranslatableComponent("gui.done"), button -> onClose()));
	}

	private void editSelected() {
		PublicWaypoint selected = waypointList.selectedWaypoint();
		if (selected != null) minecraft.setScreen(new WaypointEditScreen(this, selected));
	}

	private void deleteSelected() {
		PublicWaypoint selected = waypointList.selectedWaypoint();
		if (selected == null) return;
		minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(confirmed -> {
			if (confirmed) cn.net.rms.xaeromapsync_r.network.SharedMapNetworking.deleteWaypoint(selected);
			minecraft.setScreen(this);
		}, new TranslatableComponent("screen.xaero-mapsync_r.delete.confirm.title"),
				new TranslatableComponent("screen.xaero-mapsync_r.delete.confirm.message", selected.name())));
	}

	private void addToggle(int x, int y, int buttonWidth, String key, boolean initialValue,
			java.util.function.Consumer<Boolean> setter) {
		boolean[] enabled = {initialValue};
		Button toggle = new Button(x, y, buttonWidth, CONTROL_HEIGHT, toggleLabel(key, initialValue), button -> {
			enabled[0] = !enabled[0];
			setter.accept(enabled[0]);
			button.setMessage(toggleLabel(key, enabled[0]));
		});
		addRenderableWidget(toggle);
	}

	private Component toggleLabel(String key, boolean enabled) {
		return new TranslatableComponent("screen.xaero-mapsync_r.toggle." + key,
				new TranslatableComponent(enabled ? "options.on" : "options.off"));
	}

	private void refreshWaypoints(String query, boolean preserveScroll) {
		if (waypointList == null) {
			return;
		}
		List<PublicWaypoint> snapshot = SharedMapClient.waypointSnapshot();
		waypointSnapshotSignature = snapshotSignature(snapshot);
		String normalized = query.trim().toLowerCase(Locale.ROOT);
		List<PublicWaypoint> filtered = snapshot.stream()
				.filter(waypoint -> !waypoint.deleted())
				.filter(waypoint -> normalized.isEmpty() || searchableText(waypoint).contains(normalized))
				.collect(Collectors.toList());
		waypointList.setWaypoints(filtered, preserveScroll);
	}

	private static String searchableText(PublicWaypoint waypoint) {
		return String.join(" ", safe(waypoint.name()), safe(waypoint.creatorName()), safe(waypoint.dimension()),
				safe(waypoint.category()), Integer.toString((int) Math.floor(waypoint.x())),
				Integer.toString((int) Math.floor(waypoint.y())), Integer.toString((int) Math.floor(waypoint.z())))
				.toLowerCase(Locale.ROOT);
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}

	private static long snapshotSignature(List<PublicWaypoint> waypoints) {
		long signature = waypoints.size();
		for (PublicWaypoint waypoint : waypoints) {
			signature = signature * 31L + (waypoint.id() == null ? 0 : waypoint.id().hashCode());
			signature = signature * 31L + waypoint.revision();
			signature = signature * 31L + (waypoint.deleted() ? 1L : 0L);
		}
		return signature;
	}

	@Override
	public void tick() {
		searchBox.tick();
		List<PublicWaypoint> snapshot = SharedMapClient.waypointSnapshot();
		if (snapshotSignature(snapshot) != waypointSnapshotSignature) {
			refreshWaypoints(searchBox.getValue(), true);
		}
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float delta) {
		renderBackground(poseStack);
		drawCenteredString(poseStack, font, title, width / 2, 15, 0xFFFFFF);
		Component status = new TranslatableComponent(
				SharedMapClient.connectedToSharedMapServer()
						? "screen.xaero-mapsync_r.status.connected"
						: "screen.xaero-mapsync_r.status.disconnected");
		drawCenteredString(poseStack, font, status, width / 2, 34,
				SharedMapClient.connectedToSharedMapServer() ? 0x55FF55 : 0xFF5555);
		drawCenteredString(poseStack, font,
				new TranslatableComponent("screen.xaero-mapsync_r.metrics", SharedMapClient.waypointCount(),
						SharedMapClient.indexedTileCount(), SharedMapClient.tileCount(), SharedMapClient.pendingTileCount()),
				width / 2, 50, 0xCFCFCF);
		super.render(poseStack, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
