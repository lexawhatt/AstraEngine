package dev.lexawhatt.astraengine.client.solar;

import dev.lexawhatt.astraengine.client.render.OverworldSkyRenderer;
import dev.lexawhatt.astraengine.client.render.RenderOptions;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Overworld presentation only: host weather/clouds and server block light keep their normal ownership. */
public final class AstralOverworldEffects extends DimensionSpecialEffects.OverworldEffects {
    private final OverworldSkyRenderer renderer;
    private final SolarStateClient solar;
    private final RenderOptions options;
    private SolarVisual lightFrame = SolarVisual.HEALTHY;

    public AstralOverworldEffects(OverworldSkyRenderer renderer, SolarStateClient solar, RenderOptions options) {
        this.renderer = renderer; this.solar = solar; this.options = options;
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelView,
            Camera camera, Matrix4f projection, boolean foggy, Runnable setupFog) {
        return renderer.render(level, ticks, partialTick, modelView, camera, projection, foggy, setupFog);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 color, float brightness) {
        Vec3 base = super.getBrightnessDependentFogColor(color, brightness);
        ClientLevel level = Minecraft.getInstance().level;
        if (!active(level)) { return base; }
        SolarVisual visual = solar.visual();
        float radiance = Math.min(1.3f, 0.07f + visual.luminosity() * 0.93f);
        return base.scale(radiance).add(visual.flash() * 0.12, visual.flash() * 0.14, visual.flash() * 0.18);
    }

    @Override
    public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken,
            float blockLightRedFlicker, float skyLight, int pixelX, int pixelY, Vector3f colors) {
        if (!active(level) || !options.lighting()) { return; }
        if (pixelX == 0 && pixelY == 0) { lightFrame = solar.visual(); }
        if (lightFrame.equals(SolarVisual.HEALTHY)) { return; }
        // Subtract only the host sky contribution, after its 4% ambient lerp and boss-darkening scale.
        // Block emission is not multiplied by the star's luminosity, so torches still light enclosed rooms.
        float darken = Minecraft.getInstance().gameRenderer.getDarkenWorldAmount(partialTicks);
        float skyRG = skyDarken * 0.65f + 0.35f;
        float reduction = skyLight * 0.96f * (1 - Math.min(lightFrame.luminosity(), 1));
        colors.add(-reduction * skyRG * (1 - darken * 0.3f),
                -reduction * skyRG * (1 - darken * 0.4f), -reduction * (1 - darken * 0.4f));
        float aboveHorizon = Math.clamp(((float) Math.cos(level.getSunAngle(partialTicks)) + 0.06f) / 0.2f, 0, 1);
        float flash = lightFrame.flash() * LightTexture.getBrightness(level.dimensionType(), pixelY) * aboveHorizon;
        colors.add(flash * 0.6f, flash * 0.7f, flash * 0.9f);
        colors.set(Math.max(0, colors.x), Math.max(0, colors.y), Math.max(0, colors.z));
    }

    private boolean active(ClientLevel level) {
        return level != null && renderer.available() && level.dimension().equals(Level.OVERWORLD)
                && options.astronomicalOverworld();
    }
}
