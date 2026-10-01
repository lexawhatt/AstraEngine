package dev.lexawhatt.astraengine.client.surface;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Calibration-only sky ownership. Unsupported views and active packs retain the ordinary host path. */
public final class HorizonEffects extends DimensionSpecialEffects {
    private final HorizonRenderer renderer;

    public HorizonEffects(HorizonRenderer renderer) {
        super(192, true, SkyType.NORMAL, false, false);
        this.renderer = renderer;
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 color, float brightness) {
        return color.multiply(brightness * 0.94 + 0.06, brightness * 0.94 + 0.06, brightness * 0.91 + 0.09);
    }

    @Override
    public boolean isFoggyAt(int x, int y) { return false; }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelView,
            Camera camera, Matrix4f projection, boolean foggy, Runnable setupFog) { return renderer.active(); }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack stack,
            double x, double y, double z, Matrix4f modelView, Matrix4f projection) { return renderer.active(); }
}
