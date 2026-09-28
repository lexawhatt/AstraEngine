package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.solar.SolarStateClient;
import dev.lexawhatt.astraengine.client.solar.SolarVisual;
import java.io.IOException;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;

/**
 * Atmosphere-aware Overworld sky replacement. Astronomical pixels are presentation only;
 * Minecraft retains terrain, clouds, fog setup, weather and the day/night clock.
 */
public final class OverworldSkyRenderer implements AutoCloseable {
    private final CelestialBloomPipeline bloom = new CelestialBloomPipeline("overworld");
    private static final float SOLAR_RADIUS_RATIO = (float) (695_700_000.0 / 149_598_000_000.0);
    private final SolarStateClient state;
    private final RenderOptions options;
    private ShaderInstance shader;

    /** Uses the shared server-snapshot presentation and session-local environment controls. */
    public OverworldSkyRenderer(SolarStateClient state, RenderOptions options) {
        if (state == null || options == null) { throw new IllegalArgumentException("Sky services must not be null"); }
        this.state = state;
        this.options = options;
    }

    /** Shader reload/disposal belongs to Minecraft; a load failure restores the vanilla sky path. */
    public void registerShaders(RegisterShadersEvent event) {
        bloom.registerShaders(event);
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
    public void close() { bloom.close(); }

    /** Whether the reload-owned program is usable; fog and lightmap can share the same fallback. */
    public boolean available() { return shader != null; }

    /**
     * Implements DimensionSpecialEffects' sky hook on the render thread. False delegates to
     * Minecraft. True also represents an intentionally hidden sky in lava, powder snow,
     * thick fog, blindness or darkness, matching the host's suppression rules.
     */
    public boolean render(ClientLevel level, int ticks, float partialTick, Matrix4f modelView, Camera camera,
                          Matrix4f projection, boolean foggy, Runnable setupFog) {
        if (shader == null || !options.astronomicalOverworld() || !level.dimension().equals(Level.OVERWORLD)) {
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
        shader.safeGetUniform("InverseViewProjection").set(new Matrix4f(projection).mul(view).invert());
        float angle = level.getSunAngle(partialTick);
        shader.safeGetUniform("SunDirection").set(-(float) Math.sin(angle), (float) Math.cos(angle), 0.0f);
        shader.safeGetUniform("SunRadius").set(SOLAR_RADIUS_RATIO);
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
        if (!bloom.render(shader, options, options.exposure())) {
            shader.safeGetUniform("HdrOutput").set(0);
            try (var saved = new FullscreenPass()) { FullscreenPass.draw(shader); }
        }
        return true;
    }
}
