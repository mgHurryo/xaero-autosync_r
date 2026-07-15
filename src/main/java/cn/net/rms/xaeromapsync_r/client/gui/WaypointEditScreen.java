package cn.net.rms.xaeromapsync_r.client.gui;

import cn.net.rms.xaeromapsync_r.network.SharedMapNetworking;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import cn.net.rms.xaeromapsync_r.waypoint.WaypointVisibility;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.UUID;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

final class WaypointEditScreen extends Screen {
	private final Screen parent;
	private final PublicWaypoint original;
	private EditBox name;
	private EditBox category;
	private EditBox x;
	private EditBox y;
	private EditBox z;
	private String validationError = "";

	WaypointEditScreen(Screen parent, PublicWaypoint original) {
		super(new TranslatableComponent(original == null
				? "screen.xaero-mapsync_r.add.title" : "screen.xaero-mapsync_r.edit.title"));
		this.parent = parent;
		this.original = original;
	}

	@Override
	protected void init() {
		int left = width / 2 - 110;
		name = field(left, 54, 220, original == null ? "" : original.name(), 64);
		category = field(left, 88, 220, original == null ? "" : safe(original.category()), 64);
		double initialX = original == null ? minecraft.player.getX() : original.x();
		double initialY = original == null ? minecraft.player.getY() : original.y();
		double initialZ = original == null ? minecraft.player.getZ() : original.z();
		x = field(left, 122, 68, coordinate(initialX), 24);
		y = field(left + 76, 122, 68, coordinate(initialY), 24);
		z = field(left + 152, 122, 68, coordinate(initialZ), 24);
		addRenderableWidget(new Button(left, 160, 106, 20, new TranslatableComponent("gui.done"), button -> submit()));
		addRenderableWidget(new Button(left + 114, 160, 106, 20, new TranslatableComponent("gui.cancel"), button -> onClose()));
		setInitialFocus(name);
	}

	private EditBox field(int x, int y, int width, String value, int maxLength) {
		EditBox field = new EditBox(font, x, y, width, 20, TextComponent.EMPTY);
		field.setMaxLength(maxLength);
		field.setValue(value);
		addRenderableWidget(field);
		return field;
	}

	private void submit() {
		try {
			String dimension = original == null
					? minecraft.level.dimension().location().toString() : original.dimension();
			PublicWaypoint waypoint = new PublicWaypoint(
					original == null ? UUID.randomUUID() : original.id(),
					original == null ? null : original.creatorId(),
					original == null ? null : original.creatorName(),
					name.getValue().trim(), dimension,
					Double.parseDouble(x.getValue()), Double.parseDouble(y.getValue()), Double.parseDouble(z.getValue()),
					original == null ? null : original.symbol(), original == null ? 0x55FFFF : original.color(),
					category.getValue().trim(), WaypointVisibility.PUBLIC,
					original == null ? 0L : original.revision(), false,
					original == null ? 0L : original.createdAtMillis(), original == null ? 0L : original.updatedAtMillis());
			waypoint.validate();
			if (original == null) SharedMapNetworking.createWaypoint(waypoint);
			else SharedMapNetworking.updateWaypoint(waypoint);
			minecraft.setScreen(parent);
		} catch (RuntimeException exception) {
			validationError = exception.getMessage() == null ? "Invalid waypoint" : exception.getMessage();
		}
	}

	@Override
	public void tick() {
		name.tick();
		category.tick();
		x.tick();
		y.tick();
		z.tick();
	}

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float delta) {
		renderBackground(poseStack);
		drawCenteredString(poseStack, font, title, width / 2, 18, 0xFFFFFF);
		font.draw(poseStack, new TranslatableComponent("screen.xaero-mapsync_r.name"), width / 2 - 110, 42, 0xA0A0A0);
		font.draw(poseStack, new TranslatableComponent("screen.xaero-mapsync_r.category"), width / 2 - 110, 76, 0xA0A0A0);
		font.draw(poseStack, new TranslatableComponent("screen.xaero-mapsync_r.coordinates"), width / 2 - 110, 110, 0xA0A0A0);
		if (!validationError.isEmpty()) drawCenteredString(poseStack, font, validationError, width / 2, 190, 0xFF5555);
		super.render(poseStack, mouseX, mouseY, delta);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	private static String safe(String value) { return value == null ? "" : value; }
	private static String coordinate(double value) { return Long.toString((long) Math.floor(value)); }
}
