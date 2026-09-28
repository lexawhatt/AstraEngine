package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.solar.SolarVisual;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Bounded analytic astronomical sky. Coordinates use system-local meters on the CPU;
 * the shader receives unit directions and physical radius/distance ratios, never world
 * positions measured in astronomical meters. Relative distances support the nearest
 * black hole's analytic celestial lens; local voxel geometry remains host-rendered.
 * Minecraft owns registered shader disposal.
 */
public final class CosmosRenderer implements AutoCloseable {
    private final CelestialBloomPipeline bloom = new CelestialBloomPipeline("cosmos");
    private RenderOptions options;
    private ShaderInstance shader;
    private int quality = 1;
    private int bodyCount;
    private long galaxySeed;
    private SolarVisual solar = SolarVisual.HEALTHY;

    /** Registers the reload-owned shader; an unavailable program leaves the host's black sky. */
    public void registerShaders(RegisterShadersEvent event) {
        bloom.registerShaders(event);
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "cosmos"), DefaultVertexFormat.POSITION),
                    loaded -> shader = loaded);
        } catch (IOException exception) {
            shader = null;
            AstraEngine.LOGGER.error("Could not load the astronomical sky shader", exception);
        }
    }

    /** Shared session-local bloom controls; this renderer never multiplies exposure a second time. */
    public void setBloomOptions(RenderOptions options) {
        if (options == null) { throw new IllegalArgumentException("Render options must not be null"); }
        this.options = options;
    }

    /** Releases owned HDR/bloom buffers, leaving registered shader disposal to Minecraft. */
    @Override
    public void close() { bloom.close(); galaxySeed = 0; }

    /** Sets the procedural detail budget: 0 low, 1 balanced, 2 high. Render thread only. */
    public void setQuality(int value) {
        if (value < 0 || value > 2) { throw new IllegalArgumentException("Cosmos quality must be in 0..2"); }
        quality = value;
    }

    /** Shared Sol presentation snapshot. This never mutates a canonical body descriptor. */
    public void setSolarVisual(SolarVisual visual) {
        if (visual == null) { throw new IllegalArgumentException("Solar visual state must not be null"); }
        solar = visual;
    }

    /** Sets the current connection's galaxy seed on the render thread; system changes never reseed the background. */
    public void setGalaxySeed(long value) {
        galaxySeed = value;
    }

    /** Number of bodies extracted for the latest sky frame, including subpixel physical discs. */
    public int bodyCount() { return bodyCount; }

    /**
     * Draws only at AFTER_SKY on the render thread. Camera coordinates are virtual
     * system-local meters; time is simulation seconds. Exposure in 0.1..4 affects presentation only.
     * Warp is retained as a compatibility input without transition streaks. Physical body sizes remain unchanged.
     */
    public void render(RenderLevelStageEvent event, CosmosSystem system, SpaceVector cameraMeters,
                       double timeSeconds, float warp, float exposure) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || shader == null) { return; }
        if (system == null || cameraMeters == null || !Double.isFinite(timeSeconds)
                || !Float.isFinite(warp) || !Float.isFinite(exposure)) {
            throw new IllegalArgumentException("Cosmos rendering requires a finite camera and presentation state");
        }
        Matrix4f view = new Matrix4f(event.getModelViewMatrix()).setTranslation(0, 0, 0);
        shader.safeGetUniform("InverseViewProjection").set(new Matrix4f(event.getProjectionMatrix()).mul(view).invert());
        shader.safeGetUniform("Time").set((float) (timeSeconds % 65536.0));
        shader.safeGetUniform("Seed").set((float) Math.floorMod(system.seed(), 4096));
        GalacticFrame galaxy = GalacticFrame.extract(system, cameraMeters, galaxySeed);
        shader.safeGetUniform("GalaxyObserver").set((float) galaxy.observerRadii().x(),
                (float) galaxy.observerRadii().y(), (float) galaxy.observerRadii().z());
        shader.safeGetUniform("GalaxySeed").set(galaxy.backgroundSeed());
        shader.safeGetUniform("Exposure").set(Math.clamp(exposure, 0.1f, 4));
        shader.safeGetUniform("Detail").set(quality + 3);
        shader.safeGetUniform("Supernova").set(system.kind() == CosmosSystem.Kind.SUPERNOVA ? 1 : 0);
        var window = Minecraft.getInstance().getWindow();
        shader.safeGetUniform("ScreenSize").set((float) window.getWidth(), (float) window.getHeight());

        CelestialFrame celestialFrame = CelestialFrame.extract(system, cameraMeters, timeSeconds);
        var frames = celestialFrame.bodies();
        bodyCount = frames.size();
        shader.safeGetUniform("BodyCount").set(bodyCount);
        shader.safeGetUniform("LensIndex").set(celestialFrame.lensIndex());
        int evolutionIndex = -1;
        shader.safeGetUniform("Evolution").set(solar.depletion(), solar.collapse(), solar.explosionSeconds(), solar.remnant());
        shader.safeGetUniform("SolarLight").set(solar.luminosity(), solar.flash(), solar.radiusScale(), 0.0f);
        for (int i = 0; i < bodyCount; i++) {
            CelestialFrame.Body frame = frames.get(i);
            CelestialBody body = frame.descriptor();
            if (system.id().equals("sol") && body.id().equals("sun")) { evolutionIndex = i; }
            SpaceVector color = body.color();
            SpaceVector light = lightDirection(system, frame.position(), body.id(), timeSeconds);
            float seed = Math.floorMod(body.id().hashCode() ^ (int) system.seed(), 1024);
            shader.safeGetUniform("BodyDirectionRadius[" + i + "]").set((float) frame.direction().x(),
                    (float) frame.direction().y(), (float) frame.direction().z(), frame.radiusRatio());
            shader.safeGetUniform("BodyDistanceRatio[" + i + "]").set(celestialFrame.distanceRatio(i));
            shader.safeGetUniform("BodyColorKind[" + i + "]").set((float) color.x(), (float) color.y(),
                    (float) color.z(), (float) body.kind().ordinal());
            shader.safeGetUniform("BodySurface[" + i + "]").set(seed, body.atmosphere(),
                    body.ringInnerRatio(), body.ringOuterRatio());
            shader.safeGetUniform("BodyLightTilt[" + i + "]").set((float) light.x(), (float) light.y(),
                    (float) light.z(), (float) body.axialTiltRadians());
            // Rotation is an artistic material animation; only orbital positions are model state.
            double rotationSeconds = body.kind() == CelestialBody.Kind.GAS_GIANT ? 36000 : 86400;
            shader.safeGetUniform("BodySpin[" + i + "]").set((float) ((timeSeconds % rotationSeconds)
                    / rotationSeconds * Math.PI * 2));
        }
        shader.safeGetUniform("EvolutionIndex").set(evolutionIndex);
        if (!bloom.render(shader, options, exposure)) {
            shader.safeGetUniform("HdrOutput").set(0);
            try (var state = new FullscreenPass()) { FullscreenPass.draw(shader); }
        }
    }

    private SpaceVector lightDirection(CosmosSystem system, SpaceVector position, String id, double seconds) {
        SpaceVector direction = new SpaceVector(0.3, 0.6, 0.7).normalized();
        double strongest = -1;
        for (CelestialBody source : system.bodies()) {
            if (source.id().equals(id) || source.kind() != CelestialBody.Kind.STAR) { continue; }
            SpaceVector delta = source.positionAt(seconds).subtract(position);
            double distance = delta.length();
            if (distance <= 0) { continue; }
            double strength = source.radiusMeters() / distance;
            if (strength > strongest) {
                direction = delta.multiply(1.0 / distance);
                strongest = strength;
            }
        }
        return direction;
    }

}
