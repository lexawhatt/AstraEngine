package dev.lexawhatt.astraengine.client;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Black void environment; all celestial imagery is drawn by the owned procedural sky pass. */
public final class SpaceEffects extends DimensionSpecialEffects {
    public SpaceEffects() { super(Float.NaN, false, SkyType.NONE, true, false); }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 color, float brightness) { return Vec3.ZERO; }

    @Override
    public boolean isFoggyAt(int x, int y) { return false; }

    @Override
    public float[] getSunriseColor(float time, float partialTick) { return null; }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelView,
                             Camera camera, Matrix4f projection, boolean foggy, Runnable setupFog) {
        return true;
    }
}
