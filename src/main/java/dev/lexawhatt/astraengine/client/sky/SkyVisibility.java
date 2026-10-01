package dev.lexawhatt.astraengine.client.sky;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

/** Read-only camera exposure for atmospheric presentation; never loads a missing client chunk. */
public final class SkyVisibility {
    private SkyVisibility() { }

    /** Deep covered observers must not see the underside of the exterior LOD through the host far plane. */
    public static boolean underground(ClientLevel level, Camera camera) {
        BlockPos eye = BlockPos.containing(camera.getPosition());
        return level != null && level.hasChunkAt(eye) && level.getBrightness(LightLayer.SKY, eye) == 0
                && level.getHeight(Heightmap.Types.MOTION_BLOCKING, eye.getX(), eye.getZ()) > eye.getY() + 32;
    }
}
