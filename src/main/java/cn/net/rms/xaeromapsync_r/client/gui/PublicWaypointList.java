package cn.net.rms.xaeromapsync_r.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import cn.net.rms.xaeromapsync_r.waypoint.PublicWaypoint;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;

final class PublicWaypointList extends ObjectSelectionList<PublicWaypointList.Entry> {
	PublicWaypointList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
		super(minecraft, width, height, top, bottom, itemHeight);
		setRenderSelection(true);
	}

	PublicWaypoint selectedWaypoint() {
		Entry selected = getSelected();
		return selected == null ? null : selected.waypoint;
	}

	void setWaypoints(List<PublicWaypoint> waypoints, boolean preserveScroll) {
		double previousScroll = getScrollAmount();
		clearEntries();
		for (PublicWaypoint waypoint : waypoints) {
			addEntry(new Entry(minecraft, waypoint));
		}
		setScrollAmount(preserveScroll ? Math.min(previousScroll, getMaxScroll()) : 0.0D);
	}

	@Override
	public int getRowWidth() {
		return Math.max(100, width - 18);
	}

	final class Entry extends ObjectSelectionList.Entry<Entry> {
		private final Minecraft minecraft;
		private final PublicWaypoint waypoint;

		private Entry(Minecraft minecraft, PublicWaypoint waypoint) {
			this.minecraft = minecraft;
			this.waypoint = waypoint;
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) return false;
			PublicWaypointList.this.setSelected(this);
			return true;
		}

		@Override
		public void render(PoseStack poseStack, int index, int top, int left, int rowWidth, int rowHeight,
				int mouseX, int mouseY, boolean hovered, float delta) {
			if (hovered) {
				fill(poseStack, left - 2, top, left + rowWidth + 2, top + rowHeight - 2, 0x30FFFFFF);
			}
			String name = minecraft.font.plainSubstrByWidth(safe(waypoint.name()), rowWidth - 8);
			minecraft.font.draw(poseStack, name, left + 2, top + 4, 0xFFFFFF);

			String details = new TranslatableComponent("screen.xaero-mapsync_r.waypoint.details",
					(int) Math.floor(waypoint.x()), (int) Math.floor(waypoint.y()), (int) Math.floor(waypoint.z()),
					shortDimension(waypoint.dimension()), safe(waypoint.creatorName())).getString();
			minecraft.font.draw(poseStack, minecraft.font.plainSubstrByWidth(details, rowWidth - 8),
					left + 2, top + 18, 0xA0A0A0);
		}

		@Override
		public Component getNarration() {
			return new TranslatableComponent("screen.xaero-mapsync_r.waypoint.narration", safe(waypoint.name()),
					(int) Math.floor(waypoint.x()), (int) Math.floor(waypoint.y()), (int) Math.floor(waypoint.z()),
					shortDimension(waypoint.dimension()), safe(waypoint.creatorName()));
		}

		private static String shortDimension(String dimension) {
			if (dimension == null) {
				return "";
			}
			int separator = dimension.indexOf(':');
			return separator >= 0 ? dimension.substring(separator + 1) : dimension;
		}

		private static String safe(String value) {
			return value == null ? "" : value;
		}
	}
}
