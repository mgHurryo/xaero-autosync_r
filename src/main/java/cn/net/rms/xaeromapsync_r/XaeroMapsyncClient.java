package cn.net.rms.xaeromapsync_r;

import cn.net.rms.xaeromapsync_r.client.SharedMapClient;
import cn.net.rms.xaeromapsync_r.client.SharedMapClientConfig;
import cn.net.rms.xaeromapsync_r.client.gui.XaeroWaypointScreenIntegration;
import cn.net.rms.xaeromapsync_r.network.SharedMapNetworking;
import cn.net.rms.xaeromapsync_r.xaero.XaeroDetector;
import net.fabricmc.api.ClientModInitializer;

public class XaeroMapsyncClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		XaeroDetector.detect();
		SharedMapClient.register();
		SharedMapNetworking.registerClientReceivers();
		SharedMapClientConfig.get().load();
		XaeroWaypointScreenIntegration.register();
		XaeroMapsync_r.LOGGER.info("{} client initialized", XaeroMapsync_r.MOD_NAME);
	}
}
