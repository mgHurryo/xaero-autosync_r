package cn.net.rms.xaeromapsync_r.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	@Accessor("level")
	ServerLevel xaeroMapSync$getLevel();
}
