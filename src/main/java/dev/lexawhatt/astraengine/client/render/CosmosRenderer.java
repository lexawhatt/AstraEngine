package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.solar.SolarVisual;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.GalaxyDescriptor;
import dev.lexawhatt.astraengine.cosmos.UniverseGenerator;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Bounded analytic astronomical sky. Coordinates use system-local meters on the CPU;
 * the shader receives unit directions and physical radius/distance ratios, never world
 * positions measured in astronomical meters. Relative distances support the nearest
 * black hole's analytic celestial lens; local voxel geometry remains host-rendered.
 * Minecraft owns registered shader disposal.
 */
public final class CosmosRenderer implements AutoCloseable {
    private final CatalogStarField catalogStars = new CatalogStarField();
    private final CelestialBloomPipeline bloom = new CelestialBloomPipeline("cosmos");
    private final LateSkyRenderer lateSky = new LateSkyRenderer("cosmos");
    private RenderOptions options;
    private ShaderInstance shader;
    private int quality = 1;
    private int bodyCount;
    private long galaxySeed;
    private List<GalaxyDescriptor> galaxyDefinitions = List.of();
    private List<UniverseFrame.RegionSource> regionDefinitions = List.of();
    private int galaxyCount;
    private int regionCount;
    private SolarVisual solar = SolarVisual.HEALTHY;

    /** Registers the reload-owned shader; an unavailable program leaves the host's black sky. */
    public void registerShaders(RegisterShadersEvent event) {
        bloom.registerShaders(event);
        lateSky.registerShaders(event);
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
    public void close() {
        bloom.close();
        lateSky.close();
        galaxySeed = 0;
        galaxyDefinitions = List.of();
        regionDefinitions = List.of();
        catalogStars.clear();
        galaxyCount = 0;
        regionCount = 0;
    }

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
        if (!galaxyDefinitions.isEmpty() && galaxySeed == value) { return; }
        galaxySeed = value;
        catalogStars.clear();
        galaxyDefinitions = UniverseGenerator.galaxies(value);
        List<UniverseFrame.RegionSource> regions = new ArrayList<>();
        for (GalaxyDescriptor galaxy : galaxyDefinitions) {
            UniverseGenerator.regions(value, galaxy.index()).forEach(region ->
                    regions.add(new UniverseFrame.RegionSource(galaxy, region)));
        }
        regionDefinitions = List.copyOf(regions);
    }

    /** Number of spatial galaxy descriptors extracted for the latest frame. */
    public int galaxyCount() { return galaxyCount; }

    /** Number of named regions retained by the latest bounded, smoothly weighted extraction. */
    public int regionCount() { return regionCount; }

    /** Number of bodies extracted for the latest sky frame, including subpixel physical discs. */
    public int bodyCount() { return bodyCount; }

    /**
     * Draws at AFTER_SKY, or after Iris final composition at AFTER_LEVEL. Camera coordinates are virtual
     * system-local meters; time is simulation seconds. Exposure in 0.1..4 affects presentation only.
     * Warp is retained as a compatibility input without transition streaks. Physical body sizes remain unchanged.
     */
    public void render(RenderLevelStageEvent event, CosmosSystem system, SpaceVector cameraMeters,
                       double timeSeconds, float warp, float exposure) {
        renderFrame(event, system, cameraMeters, timeSeconds, warp, exposure, FlightOrientation.IDENTITY, false);
    }

    /**
     * Draws a planet-fixed ground sky through the same owned HDR pipeline as flight.
     * The immutable orientation maps Minecraft local east/up/south directions into system space;
     * observer meters and time are derived from the authoritative surface binding and clock.
     * Real terrain retains its depth and occludes the distant curved planet.
     */
    public void renderSurface(RenderLevelStageEvent event, CosmosSystem system, SpaceVector cameraMeters,
                              double timeSeconds, float exposure, FlightOrientation localToSystem) {
        if (localToSystem == null) { throw new IllegalArgumentException("Surface orientation must not be null"); }
        renderFrame(event, system, cameraMeters, timeSeconds, 0, exposure, localToSystem, true);
    }

    private void renderFrame(RenderLevelStageEvent event, CosmosSystem system, SpaceVector cameraMeters,
                             double timeSeconds, float warp, float exposure, FlightOrientation localToSystem,
                             boolean surfaceView) {
        if (RenderCompatibility.shadowPass()) { return; }
        boolean late = RenderCompatibility.lateWorldPasses();
        var stage = late ? RenderLevelStageEvent.Stage.AFTER_LEVEL : RenderLevelStageEvent.Stage.AFTER_SKY;
        if (event.getStage() != stage || shader == null) { return; }
        if (system == null || cameraMeters == null || !Double.isFinite(timeSeconds)
                || !Float.isFinite(warp) || !Float.isFinite(exposure)) {
            throw new IllegalArgumentException("Cosmos rendering requires a finite camera and presentation state");
        }
        Matrix4f view = new Matrix4f(event.getModelViewMatrix()).setTranslation(0, 0, 0);
        SpaceVector east = localToSystem.left();
        SpaceVector up = localToSystem.up();
        SpaceVector south = localToSystem.forward();
        Matrix4f basis = new Matrix4f().setColumn(0, new Vector4f((float) east.x(), (float) east.y(), (float) east.z(), 0))
                .setColumn(1, new Vector4f((float) up.x(), (float) up.y(), (float) up.z(), 0))
                .setColumn(2, new Vector4f((float) south.x(), (float) south.y(), (float) south.z(), 0));
        shader.safeGetUniform("InverseViewProjection").set(basis.mul(
                new Matrix4f(event.getProjectionMatrix()).mul(view).invert()));
        shader.safeGetUniform("Time").set((float) (timeSeconds % 65536.0));
        shader.safeGetUniform("Seed").set((float) Math.floorMod(system.seed(), 4096));
        uploadUniverse(system, cameraMeters);
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
        int atmosphereIndex = -1;
        int nucleusIndex = -1;
        boolean atlasNucleus = system.id().startsWith("u_")
                && UniverseGenerator.isAtlasSystemId(system.id()) && system.id().endsWith("_0");
        if (atlasNucleus) {
            uploadVector("NucleusAxis", galaxyDefinitions.get(UniverseGenerator.galaxyIndex(system.id())).orientation().up());
        }
        shader.safeGetUniform("Evolution").set(solar.depletion(), solar.collapse(), solar.explosionSeconds(), solar.remnant());
        shader.safeGetUniform("SolarLight").set(solar.luminosity(), solar.flash(), solar.radiusScale(), 0.0f);
        for (int i = 0; i < bodyCount; i++) {
            CelestialFrame.Body frame = frames.get(i);
            CelestialBody body = frame.descriptor();
            SurfaceDefinition definition = SurfaceDefinition.find(system.id(), body.id()).orElse(null);
            if (system.id().equals("sol") && body.id().equals("sun")) { evolutionIndex = i; }
            if (atlasNucleus && body.id().equals("primary") && body.kind() == CelestialBody.Kind.BLACK_HOLE) {
                nucleusIndex = i;
            }
            SpaceVector color = body.color();
            SpaceVector light = lightDirection(system, frame.position(), body.id(), timeSeconds);
            float seed = Math.floorMod(body.id().hashCode() ^ (int) system.seed(), 1024);
            float renderRadius = frame.radiusRatio();
            if (definition != null && frame.distance() < body.radiusMeters() + 100_000) {
                var fixed = definition.frame(system, timeSeconds, timeSeconds * 20);
                SpaceVector bodyPoint = fixed.toBodyPoint(cameraMeters);
                double height = bodyPoint.length() > 1 ? definition.geography().sample(bodyPoint).heightMeters() : 0;
                if (body.atmosphere() > 0) { height = Math.max(0, height); }
                double blend = Math.clamp((body.radiusMeters() + 100_000 - frame.distance()) / 90_000, 0, 1);
                renderRadius = (float) ((body.radiusMeters() + height * blend) / frame.distance());
            }
            shader.safeGetUniform("BodyDirectionRadius[" + i + "]").set((float) frame.direction().x(),
                    (float) frame.direction().y(), (float) frame.direction().z(), renderRadius);
            shader.safeGetUniform("BodyDistanceRatio[" + i + "]").set(celestialFrame.distanceRatio(i));
            shader.safeGetUniform("BodyColorKind[" + i + "]").set((float) color.x(), (float) color.y(),
                    (float) color.z(), (float) body.kind().ordinal());
            shader.safeGetUniform("BodySurface[" + i + "]").set(seed, body.atmosphere(),
                    body.ringInnerRatio(), body.ringOuterRatio());
            shader.safeGetUniform("BodyLightTilt[" + i + "]").set((float) light.x(), (float) light.y(),
                    (float) light.z(), (float) body.axialTiltRadians());
            shader.safeGetUniform("BodyGeography[" + i + "]").set(definition == null ? 0 : definition.geography().kind().ordinal() + 1,
                    0, (float) body.radiusMeters(), 0);
            shader.safeGetUniform("BodyGeographySeed[" + i + "]").set(
                    definition == null ? 0 : definition.geography().shaderSeed());
            if (definition != null && body.atmosphere() > 0) {
                SpaceVector relativeKm = cameraMeters.subtract(frame.position()).multiply(0.001);
                if (relativeKm.length() < body.radiusMeters() * 0.03) {
                    atmosphereIndex = i;
                    shader.safeGetUniform("AtmosphereObserver").set((float) relativeKm.x(), (float) relativeKm.y(),
                            (float) relativeKm.z(), (float) (body.radiusMeters() * 0.001));
                }
            }
            // Material/beam animation consumes the occupied catalog clock, never wall time.
            // Pulsar spin is deliberately slowed for readable visuals; this is not a physical period.
            double rotationSeconds = body.kind() == CelestialBody.Kind.PULSAR
                    ? 1.2 + seed / 1024.0 * 2.0
                    : body.kind() == CelestialBody.Kind.GAS_GIANT ? 36000 : 86400;
            shader.safeGetUniform("BodySpin[" + i + "]").set(definition == null
                    ? (float) ((timeSeconds % rotationSeconds) / rotationSeconds * Math.PI * 2)
                    : (float) definition.spinRadians(timeSeconds, timeSeconds * 20));
        }
        shader.safeGetUniform("EvolutionIndex").set(evolutionIndex);
        shader.safeGetUniform("AtmosphereBodyIndex").set(atmosphereIndex);
        shader.safeGetUniform("SurfaceHorizon").set((float) up.x(), (float) up.y(), (float) up.z(),
                surfaceView && atmosphereIndex >= 0 && !late ? 1.0f : 0.0f);
        float[] fog = RenderSystem.getShaderFogColor();
        shader.safeGetUniform("SurfaceFog").set(fog[0], fog[1], fog[2]);
        shader.safeGetUniform("NucleusBodyIndex").set(nucleusIndex);
        if (late) {
            lateSky.render(() -> drawSky(exposure));
        } else {
            lateSky.close();
            drawSky(exposure);
        }
    }

    private void drawSky(float exposure) {
        if (!bloom.render(shader, options, exposure)) {
            shader.safeGetUniform("HdrOutput").set(0);
            try (var state = new FullscreenPass()) { FullscreenPass.draw(shader); }
        }
    }

    private void uploadUniverse(CosmosSystem system, SpaceVector cameraMeters) {
        if (galaxyDefinitions.isEmpty()) { setGalaxySeed(galaxySeed); }
        SpaceVector observer = system.galaxyPosition().add(cameraMeters.multiply(1.0 / CosmosGenerator.LIGHT_YEAR));
        UniverseFrame frame = UniverseFrame.extract(galaxyDefinitions, regionDefinitions, observer, quality);
        galaxyCount = frame.galaxies().size();
        regionCount = frame.regions().size();
        shader.safeGetUniform("GalaxySeed").set(UniverseFrame.shaderSeed(galaxySeed));
        shader.safeGetUniform("GalaxyCount").set(galaxyCount);
        shader.safeGetUniform("RegionCount").set(regionCount);
        for (int index = 0; index < galaxyCount; index++) {
            UniverseFrame.Galaxy galaxy = frame.galaxies().get(index);
            GalaxyDescriptor descriptor = galaxy.descriptor();
            SpaceVector origin = galaxy.observerRadii();
            shader.safeGetUniform("GalaxyObserver[" + index + "]").set((float) origin.x(), (float) origin.y(),
                    (float) origin.z(), galaxy.seed());
            shader.safeGetUniform("GalaxyShape[" + index + "]").set(
                    (float) (descriptor.thicknessLightYears() / descriptor.radiusLightYears()),
                    (float) (descriptor.coreRadiusLightYears() / descriptor.radiusLightYears()),
                    (float) descriptor.kind().ordinal(), (float) descriptor.armCount());
            shader.safeGetUniform("GalaxyStructure[" + index + "]").set((float) descriptor.armTwist(),
                    (float) descriptor.radiusLightYears(), descriptor.activeNucleus() ? 1.0f : 0.0f, 1.0f);
            uploadVector("GalaxyAxisX[" + index + "]", descriptor.orientation().left());
            uploadVector("GalaxyAxisY[" + index + "]", descriptor.orientation().up());
            uploadVector("GalaxyAxisZ[" + index + "]", descriptor.orientation().forward());
        }
        List<CatalogStarField.Star> stars = catalogStars.extract(galaxySeed, system, observer, galaxyDefinitions);
        shader.safeGetUniform("CatalogStarCount").set(stars.size());
        for (int index = 0; index < stars.size(); index++) {
            CatalogStarField.Star star = stars.get(index);
            SpaceVector direction = star.direction();
            SpaceVector color = star.color();
            shader.safeGetUniform("CatalogStarDirection[" + index + "]").set((float) direction.x(),
                    (float) direction.y(), (float) direction.z(), star.intensity());
            uploadVector("CatalogStarColor[" + index + "]", color);
        }
        for (int index = 0; index < regionCount; index++) {
            UniverseFrame.Region region = frame.regions().get(index);
            SpaceVector origin = region.observerRadii();
            SpaceVector color = region.descriptor().color();
            shader.safeGetUniform("RegionObserver[" + index + "]").set((float) origin.x(), (float) origin.y(),
                    (float) origin.z(), (float) region.descriptor().kind().ordinal());
            shader.safeGetUniform("RegionColor[" + index + "]").set((float) color.x(), (float) color.y(),
                    (float) color.z(), (float) region.descriptor().strength() * region.visibility());
            shader.safeGetUniform("RegionStructure[" + index + "]").set(region.seed(), (float) region.galaxySlot(),
                    (float) region.descriptor().radiusLightYears(), 0.0f);
        }
    }

    private void uploadVector(String name, SpaceVector vector) {
        shader.safeGetUniform(name).set((float) vector.x(), (float) vector.y(), (float) vector.z());
    }

    private SpaceVector lightDirection(CosmosSystem system, SpaceVector position, String id, double seconds) {
        SpaceVector direction = new SpaceVector(0.3, 0.6, 0.7).normalized();
        double strongest = -1;
        for (CelestialBody source : system.bodies()) {
            if (source.id().equals(id) || (source.kind() != CelestialBody.Kind.STAR
                    && source.kind() != CelestialBody.Kind.PULSAR)) { continue; }
            SpaceVector delta = system.positionAt(source, seconds).subtract(position);
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
