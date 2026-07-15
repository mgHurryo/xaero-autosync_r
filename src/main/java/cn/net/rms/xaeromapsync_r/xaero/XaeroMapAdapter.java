package cn.net.rms.xaeromapsync_r.xaero;

import cn.net.rms.xaeromapsync_r.map.MapTile;

public interface XaeroMapAdapter {
	boolean isAvailable();

	boolean apply(MapTile tile);
}
