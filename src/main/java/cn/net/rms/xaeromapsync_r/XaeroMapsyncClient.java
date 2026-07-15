package cn.net.rms.xaeromapsync_r;

import cn.net.rms.xaeromapsync_r.client.SharedMapClient;
import cn.net.rms.xaeromapsync_r.client.SharedMapClientConfig;
import cn.net.rms.xaeromapsync_r.client.gui.SharedMapScreen;
import cn.net.rms.xaeromapsync_r.network.SharedMapNetworking;
import cn.net.rms.xaeromapsync_r.xaero.XaeroDetector;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class XaeroMapsyncClient implements ClientModInitializer {
	private static final KeyMapping OPEN_SHARED_MAP_SCREEN = new KeyMapping(
			"key.xaero-mapsync_r.open_manager",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_RIGHT_BRACKET,
			"key.categories.xaero-mapsync_r");

	@Override
	public void onInitializeClient() {
		XaeroDetector.detect();
		SharedMapClient.register();
		SharedMapNetworking.registerClientReceivers();
		SharedMapClientConfig.get().load();
		KeyBindingHelper.registerKeyBinding(OPEN_SHARED_MAP_SCREEN);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_SHARED_MAP_SCREEN.consumeClick()) {
				client.setScreen(new SharedMapScreen());
			}
		});
		XaeroMapsync_r.LOGGER.info("{} client initialized", XaeroMapsync_r.MOD_NAME);
	}
}
