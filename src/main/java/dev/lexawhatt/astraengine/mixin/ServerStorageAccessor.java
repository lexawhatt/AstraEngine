package dev.lexawhatt.astraengine.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The host exposes dynamic level insertion but keeps its shared storage lease protected. No second lease is opened. */
@Mixin(MinecraftServer.class)
public interface ServerStorageAccessor {
    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess astraengine$storageSource();
}
