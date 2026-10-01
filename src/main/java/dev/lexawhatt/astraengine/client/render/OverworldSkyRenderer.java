package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.solar.SolarStateClient;
import dev.lexawhatt.astraengine.client.solar.SolarVisual;
import dev.lexawhatt.astraengine.client.sky.SkyStateClient;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.io.IOException;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Atmosphere-aware Overworld sky replacement. Astronomical pixels are presentation only;
 * Minecraft retains terrain, precipitation, fog setup, weather and the day/night clock.
 */
public final class OverworldSkyRenderer implements AutoCloseable {
    private final CelestialBloomPipeline bloom = new CelestialBloomPipeline("overworld");
    private final AtmosphereVolumeRenderer volumes = new AtmosphereVolumeRenderer();
    private static final float SOLAR_RADIUS_RATIO = (float) (695_700_000.0 / 149_598_000_000.0);
    private final SolarStateClient state;
    private final RenderOptions options;
    private final SkyStateClient seasons;
    private ShaderInstance shader;
    private boolean yieldedToShaderPack;

    /** Uses the shared server-snapshot presentation and session-local environment controls. */
    public OverworldSkyRenderer(SolarStateClient state, RenderOptions options, SkyStateClient seasons) {
        if (state == null || options == null || seasons == null) {
            throw new IllegalArgumentException("Sky services must not be null");
        }
        this.state = state;
        this.options = options;
        this.seasons = seasons;
    }

    /** Shader reload/disposal belongs to Minecraft; a load failure restores the vanilla sky path. */
    public void registerShaders(RegisterShadersEvent event) {
        bloom.registerShaders(event);
        volumes.registerShaders(event);
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "overworld_sky"),
                    DefaultVertexFormat.POSITION), loaded -> shader = loaded);
        } catch (IOException exception) {
            shader = null;
            AstraEngine.LOGGER.error("Could not load the Overworld sky shader; using vanilla sky", exception);
        }
    }

    /** Releases owned HDR/bloom attachments; safe to repeat on the render thread. */
    @Override
    public void close() { bloom.close(); volumes.close(); }

    /** Called after world composition and before the editor's final effect; preserves terrain depth. */
    public void renderAtmosphere(RenderLevelStageEvent event) {
        if (RenderCompatibility.shadowPass()) { return; }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) { return; }
        if (RenderCompatibility.shaderPackActive()) { yieldToShaderPack(); return; }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !seasons.surfaceLevel(level) || !options.astronomicalSurface(level.dimension().location().toString())) {
            try (var saved = new FullscreenPass()) { volumes.close(); }
            return;
        }
        volumes.world();
    }

    /** Whether the reload-owned program is usable; fog and lightmap can share the same fallback. */
    public boolean available() { return shader != null; }

    /**
     * Implements DimensionSpecialEffects' sky hook on the render thread. False delegates to
     * Minecraft. True also represents an intentionally hidden sky in lava, powder snow,
     * thick fog, blindness or darkness, matching the host's suppression rules.
     */
    public boolean render(ClientLevel level, int ticks, float partialTick, Matrix4f modelView, Camera camera,
                          Matrix4f projection, boolean foggy, Runnable setupFog) {
        if (RenderCompatibility.shadowPass()) { return false; }
        volumes.clearFrame();
        if (RenderCompatibility.shaderPackActive()) {
            // The pack owns Overworld atmosphere, fog and lighting. Do not layer two skies.
            yieldToShaderPack();
            return false;
        }
        yieldedToShaderPack = false;
        if (shader == null || !options.astronomicalSurface(level.dimension().location().toString()) || !seasons.surfaceLevel(level)) {
            return false;
        }
        setupFog.run();
        FogType fluid = camera.getFluidInCamera();
        if (foggy || fluid == FogType.LAVA || fluid == FogType.POWDER_SNOW
                || camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) {
            return true;
        }
        Matrix4f view = new Matrix4f(modelView).setTranslation(0, 0, 0);
        Matrix4f inverseViewProjection = new Matrix4f(projection).mul(view).invert();
        shader.safeGetUniform("InverseViewProjection").set(inverseViewProjection);
        var sample = seasons.sample(level, partialTick);
        var sun = seasons.toHostDirection(level, sample.sunDirection());
        var localProfile = seasons.localProfile(level);
        shader.safeGetUniform("SunDirection").set((float) sun.x(), (float) sun.y(), (float) sun.z());
        shader.safeGetUniform("SunRadius").set((float) (SOLAR_RADIUS_RATIO
                * localProfile.sunSizeMultiplier() / sample.orbitalDistanceAu()));
        double latitude = Math.toRadians(localProfile.latitudeDegrees());
        double spin = sample.siderealAngleRadians();
        double sinSpin = Math.sin(spin);
        double cosSpin = Math.cos(spin);
        double sinLatitude = Math.sin(latitude);
        double cosLatitude = Math.cos(latitude);
        vector("SkyNorth", seasons.toHostDirection(level, new SpaceVector(-sinSpin,
                cosLatitude * cosSpin, sinLatitude * cosSpin)));
        vector("SkyEast", seasons.toHostDirection(level, new SpaceVector(cosSpin,
                cosLatitude * sinSpin, sinLatitude * sinSpin)));
        vector("SkyPole", seasons.toHostDirection(level, new SpaceVector(0, sinLatitude, -cosLatitude)));
        float warmth = (float) (0.5 + 0.5 * Math.sin(sample.seasonPhase() * Math.PI * 2)
                * Math.clamp(localProfile.latitudeDegrees() / 30, -1, 1));
        float aerosol = 0.65f + warmth * 0.65f + level.getRainLevel(partialTick) * 0.7f;
        float cloudCover = Minecraft.getInstance().options.getCloudsType() == CloudStatus.OFF ? 0
                : options.cloudCover(0.38f + (1 - warmth) * 0.09f + level.getRainLevel(partialTick) * 0.48f);
        shader.safeGetUniform("AtmosphereParams").set(aerosol, cloudCover,
                seasons.pollution(), (float) sample.seasonPhase());
        shader.safeGetUniform("CloudOffset").set((float) (camera.getPosition().x / 1800),
                (float) (camera.getPosition().z / 1800));
        // Periodic drift is continuous at the bounded phase wrap, unlike wrapping translated noise coordinates.
        double windAngle = (Math.floorMod(level.getGameTime(), 432_000) + partialTick) / 432_000 * Math.PI * 2;
        shader.safeGetUniform("CloudWind").set((float) (12 * Math.sin(windAngle)),
                (float) (12 * (Math.cos(windAngle) - 1)));
        shader.safeGetUniform("Time").set((level.getGameTime() % 1_310_720 + partialTick) / 20.0f);
        shader.safeGetUniform("MoonPhase").set(level.getMoonPhase());
        shader.safeGetUniform("Weather").set(level.getRainLevel(partialTick), level.getThunderLevel(partialTick));
        shader.safeGetUniform("Detail").set(options.quality().ordinal() + 3);
        var sky = level.getSkyColor(camera.getPosition(), partialTick);
        shader.safeGetUniform("SkyColor").set((float) sky.x, (float) sky.y, (float) sky.z);
        float[] fog = RenderSystem.getShaderFogColor();
        shader.safeGetUniform("AtmosphereFog").set(fog[0], fog[1], fog[2]);
        var window = Minecraft.getInstance().getWindow();
        shader.safeGetUniform("ScreenSize").set((float) window.getWidth(), (float) window.getHeight());
        SolarVisual visual = state.visual();
        shader.safeGetUniform("Evolution").set(visual.depletion(), visual.collapse(),
                visual.explosionSeconds(), visual.remnant());
        shader.safeGetUniform("SolarLight").set(visual.luminosity(), visual.flash(), visual.radiusScale(), 0.0f);
        shader.safeGetUniform("Exposure").set(options.exposure());
        var position = camera.getPosition();
        float night = 1 - (float) Math.clamp((sun.y() + 0.19) / 0.135, 0, 1);
        float moonlight = (float) Math.max(0, Math.cos(level.getMoonPhase() * Math.PI / 4))
                * night * visual.luminosity();
        float skyAccess = level.getBrightness(LightLayer.SKY, BlockPos.containing(position)) / 15.0f;
        var volumeFrame = new AtmosphereVolumeRenderer.Frame(level, inverseViewProjection,
                new SpaceVector(wrappedKm(position.x), seasons.altitudeMeters(level, position.y) / 1000, wrappedKm(position.z)), sun,
                (float) (12 * Math.sin(windAngle)), (float) (12 * (Math.cos(windAngle) - 1)),
                cloudCover, aerosol, visual.luminosity(), moonlight, level.getRainLevel(partialTick),
                level.getThunderLevel(partialTick), skyAccess, options.exposure(), options.shafts(), options.quality(),
                (float) seasons.cloudBaseKm(level), (float) seasons.cloudTopKm(level));
        boolean volume = fluid == FogType.NONE && volumes.sky(volumeFrame, shader);
        shader.safeGetUniform("VolumeEnabled").set(volume ? 1 : 0);
        if (!bloom.render(shader, options, options.exposure())) {
            shader.safeGetUniform("HdrOutput").set(0);
            try (var saved = new FullscreenPass()) { FullscreenPass.draw(shader); }
        }
        return true;
    }

    private void vector(String name, SpaceVector value) {
        shader.safeGetUniform(name).set((float) value.x(), (float) value.y(), (float) value.z());
    }

    private void yieldToShaderPack() {
        if (!yieldedToShaderPack) {
            try (var saved = new FullscreenPass()) { close(); }
            yieldedToShaderPack = true;
        }
    }

    private static double wrappedKm(double worldMeters) {
        double remainder = worldMeters % 64_000;
        return (remainder < 0 ? remainder + 64_000 : remainder) / 1000;
    }
}
