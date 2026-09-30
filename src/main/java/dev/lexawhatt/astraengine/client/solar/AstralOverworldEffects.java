package dev.lexawhatt.astraengine.client.solar;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lexawhatt.astraengine.client.render.OverworldSkyRenderer;
import dev.lexawhatt.astraengine.client.render.RenderOptions;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.sky.SkyIllumination;
import dev.lexawhatt.astraengine.client.sky.SkyStateClient;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Seasonal sky presentation; host precipitation, terrain and server block light retain their ownership. */
public final class AstralOverworldEffects extends DimensionSpecialEffects.OverworldEffects {
    private final OverworldSkyRenderer renderer;
    private final SolarStateClient solar;
    private final RenderOptions options;
    private final SkyStateClient seasons;
    private SolarVisual lightFrame = SolarVisual.HEALTHY;
    private SpaceVector skylightFrame = SpaceVector.ZERO;
    private double sunHeight;

    /** Receives connection-scoped services; this effects object never advances a simulation clock. */
    public AstralOverworldEffects(OverworldSkyRenderer renderer, SolarStateClient solar, RenderOptions options,
                                 SkyStateClient seasons) {
        this.renderer = renderer;
        this.solar = solar;
        this.options = options;
        this.seasons = seasons;
    }

    /** The connection's immutable profile and derived sky can be inspected on the client thread. */
    public SkyStateClient skyState() { return seasons; }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelView,
            Camera camera, Matrix4f projection, boolean foggy, Runnable setupFog) {
        return renderer.render(level, ticks, partialTick, modelView, camera, projection, foggy, setupFog);
    }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack poseStack,
                                double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
        // The atmospheric shader supplies clouds. A missing/disabled sky restores host clouds as well.
        return active(level);
    }

    @Override
    public float[] getSunriseColor(float timeOfDay, float partialTicks) {
        return active(Minecraft.getInstance().level) ? null : super.getSunriseColor(timeOfDay, partialTicks);
    }

    /** Applies seasonal haze after host fog extraction while preserving fluid and status-effect visibility. */
    public void fogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft game = Minecraft.getInstance();
        ClientLevel level = game.level;
        Camera camera = event.getCamera();
        if (!active(level) || camera.getFluidInCamera() != FogType.NONE
                || camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS)
                    || living.hasEffect(MobEffects.NIGHT_VISION))) { return; }
        float partial = (float) event.getPartialTick();
        var sun = seasons.sample(level, partial).sunDirection();
        var look = camera.getLookVector();
        SolarVisual visual = solar.visual();
        SpaceVector haze = SkyIllumination.fog(sun.y(), sun.x() * look.x + sun.y() * look.y + sun.z() * look.z,
                level.getRainLevel(partial), level.getThunderLevel(partial), visual.luminosity(), visual.flash(),
                seasons.pollution());
        double voidLight = Math.clamp((camera.getPosition().y - level.getMinBuildHeight())
                * level.getLevelData().getClearColorScale(), 0, 1);
        double boss = game.gameRenderer.getDarkenWorldAmount(partial);
        double dim = voidLight * voidLight;
        event.setRed((float) (haze.x() * dim * (1 - boss * 0.3)));
        event.setGreen((float) (haze.y() * dim * (1 - boss * 0.4)));
        event.setBlue((float) (haze.z() * dim * (1 - boss * 0.4)));
    }

    @Override
    public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken,
            float blockLightRedFlicker, float skyLight, int pixelX, int pixelY, Vector3f colors) {
        if (!active(level) || !options.lighting()) { return; }
        if (pixelX == 0 && pixelY == 0) {
            lightFrame = solar.visual();
            sunHeight = seasons.sample(level, partialTicks).sunDirection().y();
            skylightFrame = SkyIllumination.skyLight(sunHeight, level.getRainLevel(partialTicks),
                    level.getThunderLevel(partialTicks), lightFrame.luminosity());
            if (level.getSkyFlashTime() > 0) { skylightFrame = new SpaceVector(1, 1, 1); }
        }
        // Replace only the pre-gamma sky summand, including the host's ambient and boss transforms.
        // Pixel Y=0 contributes no sky in the Overworld: enclosed block emission stays exactly unchanged.
        float darken = Minecraft.getInstance().gameRenderer.getDarkenWorldAmount(partialTicks);
        float skyAccess = LightTexture.getBrightness(level.dimensionType(), pixelY);
        float hostRG = skyDarken * 0.65f + 0.35f;
        colors.add((float) (skyAccess * skylightFrame.x() - skyLight * hostRG) * 0.96f * (1 - darken * 0.3f),
                (float) (skyAccess * skylightFrame.y() - skyLight * hostRG) * 0.96f * (1 - darken * 0.4f),
                (float) (skyAccess * skylightFrame.z() - skyLight) * 0.96f * (1 - darken * 0.4f));
        float aboveHorizon = (float) Math.clamp((sunHeight + 0.06) / 0.2, 0, 1);
        float flash = lightFrame.flash() * skyAccess * aboveHorizon;
        colors.add(flash * 0.6f, flash * 0.7f, flash * 0.9f);
        colors.set(Math.max(0, colors.x), Math.max(0, colors.y), Math.max(0, colors.z));
    }

    private boolean active(ClientLevel level) {
        return !RenderCompatibility.shaderPackActive() && !RenderCompatibility.shadowPass()
                && level != null && renderer.available() && level.dimension().equals(Level.OVERWORLD)
                && options.astronomicalOverworld();
    }
}
