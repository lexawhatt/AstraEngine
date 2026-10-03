package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.sky.SkyVisibility;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.sky.SkyIllumination;
import dev.lexawhatt.astraengine.client.sky.SkyStateClient;
import dev.lexawhatt.astraengine.client.solar.SolarStateClient;
import dev.lexawhatt.astraengine.client.surface.EarthStateClient;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.ContinentalLandscape;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.SolidPlanetPalette;
import dev.lexawhatt.astraengine.client.surface.PlanetSkyState;
import dev.lexawhatt.astraengine.surface.EarthSurfacePalette;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/** Connection/render-thread owner of Earth's distant mesh and private depth. No host geometry is replaced. */
final class EarthLandscapeRenderer implements AutoCloseable {
    private final EarthStateClient earth;
    private final SkyStateClient seasons;
    private final SolarStateClient solar;
    private final RenderOptions options;
    private final CloudNoiseAtlas cloudNoise = new CloudNoiseAtlas();
    private ShaderInstance terrain;
    private ShaderInstance compose;
    private ContinentalLandscape mesh;
    private EarthSurfacePalette palette;
    private SolidPlanetPalette planetPalette;
    private PlanetSkyState planetSky;
    private VertexBuffer vertices;
    private RenderTarget target;
    private Pending pending;
    private ClientLevel level;
    private Depth frameDepth;
    private boolean failed;
    private boolean targetFailed;
    private long draws;

    EarthLandscapeRenderer(EarthStateClient earth, SkyStateClient seasons, SolarStateClient solar, RenderOptions options) {
        this.earth = earth; this.seasons = seasons; this.solar = solar; this.options = options;
    }

    void setPlanetSky(PlanetSkyState state) { planetSky = state; }

    void registerShaders(RegisterShadersEvent event) {
        close(); terrain = null; compose = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "earth_landscape"),
                    DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL), value -> terrain = value);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "earth_landscape_compose"),
                    DefaultVertexFormat.POSITION), value -> compose = value);
        } catch (IOException failure) {
            AstraEngine.LOGGER.error("Could not load distant Earth surface; retaining host terrain", failure);
        }
    }

    void clearFrame() { frameDepth = null; }
    Depth depth() { return frameDepth; }
    long draws() { return draws; }

    private CubeStorageChart chart() {
        var game = Minecraft.getInstance();
        if (game.level == null || terrain == null || compose == null
                || RenderCompatibility.shaderPackActive() || RenderCompatibility.shadowPass()) { return null; }
        return earth.cubeChart(game.level.dimension().location().toString()).orElse(null);
    }

    private static boolean sameLandscape(CubeStorageChart first, CubeStorageChart second) {
        return first != null && second != null && first.geographyId().equals(second.geographyId())
                && first.face() == second.face();
    }

    private static boolean visible() {
        var game = Minecraft.getInstance();
        var camera = game.gameRenderer.getMainCamera();
        return !SkyVisibility.underground(game.level, camera)
                && camera.getFluidInCamera() == FogType.NONE && !(camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS)));
    }

    void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || RenderCompatibility.shadowPass()) { return; }
        CubeStorageChart chart = chart();
        Minecraft game = Minecraft.getInstance();
        if (chart == null) { if (level != null) { close(); } return; }
        // Altitude bands observe the same immutable geographic mesh. Retain it through a prepared
        // host level handoff instead of blanking the horizon and resampling thousands of heights.
        if (mesh != null && !sameLandscape(mesh.chart(), chart)) { close(); }
        level = game.level;
        var camera = event.getCamera().getPosition();
        if (Math.abs(camera.x) > chart.radiusMeters() || Math.abs(camera.z) > chart.radiusMeters()) { return; }
        update(chart, camera.x, camera.z);
        if (vertices == null || mesh == null || targetFailed || !visible()) { return; }
        RenderTarget main = game.getMainRenderTarget();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var planet = chart instanceof PlanetChart && planetSky != null ? planetSky.sample(game.level, camera) : null;
        if (chart instanceof PlanetChart && planet == null) { return; }
        var visual = solar.visual();
        SpaceVector light;
        double altitude = camera.y + chart.altitudeOriginMeters();
        double atmosphereDensity;
        float flash;
        if (planet != null) {
            light = planet.lightColor(); atmosphereDensity = planet.atmosphereDensity();
            flash = planet.chart().profile().systemId().equals("sol") ? visual.flash() : 0;
        } else {
            SpaceVector sun = seasons.toHostDirection(game.level, seasons.sample(game.level, partial).sunDirection());
            light = SkyIllumination.skyLight(sun.y(), seasons.rain(game.level, partial),
                    seasons.thunder(game.level, partial), visual.luminosity());
            atmosphereDensity = Math.exp(-Math.max(0, altitude) / 8000); flash = visual.flash();
        }
        var observer = chart.tangentFrame(camera.x, camera.z, altitude);
        SpaceVector offset = observer.toLocalPoint(mesh.frame().originMeters());
        var rotation = observer.toLocalOrientation(mesh.frame().orientation());
        Matrix4f transform = new Matrix4f().translation((float) offset.x(), (float) offset.y(), (float) offset.z())
                .rotate(new org.joml.Quaternionf((float) rotation.x(), (float) rotation.y(),
                        (float) rotation.z(), (float) rotation.w()));
        Matrix4f projection = new Matrix4f(event.getProjectionMatrix());
        // Far terrain owns separate depth. Its range never changes the host near/far planes.
        float near = 1, far = 4_000_000;
        projection.m22(-(far + near) / (far - near)).m32(-2 * far * near / (far - near));
        try (var state = new FullscreenPass()) {
            if (target == null || target.width != main.width || target.height != main.height) {
                try {
                    RenderTarget replacement = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
                    replacement.setClearColor(0, 0, 0, 0);
                    if (target != null) { target.destroyBuffers(); }
                    target = replacement;
                } catch (RuntimeException failure) {
                    targetFailed = true;
                    AstraEngine.LOGGER.error("Could not allocate distant Earth target; retaining host terrain until reload", failure);
                    return;
                }
            }
            target.clear(Minecraft.ON_OSX); target.bindWrite(true);
            RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL11.GL_LEQUAL); RenderSystem.depthMask(true);
            RenderSystem.disableCull(); RenderSystem.disableBlend();
            vector(terrain, "LightColor", light);
            if (chart instanceof EarthChart) {
                cloudNoise.ensureUploaded();
                terrain.setSampler("CloudNoise", cloudNoise.texture());
                var sun = seasons.toHostDirection(game.level, seasons.sample(game.level, partial).sunDirection());
                EarthCloudUniforms.bind(terrain, EarthCloudUniforms.sample(game.level, partial, options, seasons,
                        visual.luminosity() + visual.flash() * .6f), observer.originMeters().multiply(.001), chart.radiusMeters() * .001,
                        observer.orientation(), observer.toBodyDirection(sun));
            } else { terrain.safeGetUniform("CloudPlanet").set(0.0f, 0.0f, 0.0f, 0.0f); }
            float[] fog = RenderSystem.getShaderFogColor();
            terrain.safeGetUniform("HazeColor").set(fog[0], fog[1], fog[2]);
            terrain.safeGetUniform("EyeAltitude").set((float) altitude);
            terrain.safeGetUniform("Flash").set(flash);
            terrain.safeGetUniform("AtmosphereDensity").set((float) atmosphereDensity);
            terrain.safeGetUniform("NearCoverage").set(nearCoverage(game, camera));
            // Keep the native clipping bound tied to actual host section visibility.
            terrain.safeGetUniform("HostFarPlane").set(Math.min(game.gameRenderer.getDepthFar(),
                    game.options.getEffectiveRenderDistance() * 64.0f));
            terrain.safeGetUniform("BandAltitude").set((float) (chart.altitudeOriginMeters() + chart.minY()),
                    (float) (chart.altitudeOriginMeters() + chart.minY() + chart.height()));
            terrain.safeGetUniform("LocalTransform").set(transform);
            terrain.safeGetUniform("FlatOffset").set((float) (mesh.centerX() - camera.x), (float) -altitude,
                    (float) (mesh.centerZ() - camera.z));
            terrain.safeGetUniform("MaterialOffset").set((float) (mesh.centerX() % 32768), (float) (mesh.centerZ() % 32768));
            vertices.bind();
            vertices.drawWithShader(event.getModelViewMatrix(), projection, terrain);
            VertexBuffer.unbind();
        }
        try (var state = new FullscreenPass()) {
            compose.setSampler("LandscapeColor", target.getColorTextureId());
            FullscreenPass.draw(compose);
        }
        frameDepth = new Depth(target.getDepthTextureId(), new Matrix4f(projection).mul(event.getModelViewMatrix()).invert());
        draws++;
    }

    private static float nearCoverage(Minecraft game, Vec3 camera) {
        int radius = Math.clamp((game.options.getEffectiveRenderDistance() - 3) * 16, 16, 48);
        int minX = Math.floorDiv((int) Math.floor(camera.x - radius), 16);
        int maxX = Math.floorDiv((int) Math.floor(camera.x + radius), 16);
        int minZ = Math.floorDiv((int) Math.floor(camera.z - radius), 16);
        int maxZ = Math.floorDiv((int) Math.floor(camera.z + radius), 16);
        var position = new BlockPos.MutableBlockPos();
        // Keep the procedural fallback while streaming or compiling nearby terrain. These bounded
        // read-only queries never request a chunk, and the host method also works with Sodium's renderer.
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                var chunk = game.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) { return 0; }
                for (int corner = 0; corner < 5; corner++) {
                    int bx = x * 16 + (corner == 4 ? 8 : (corner & 1) * 15);
                    int bz = z * 16 + (corner == 4 ? 8 : (corner >> 1) * 15);
                    int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                    if (y < game.level.getMinBuildHeight()
                            || !game.levelRenderer.isSectionCompiled(position.set(bx, y, bz))) { return 0; }
                }
            }
        }
        return radius;
    }

    /** Keeps the host terrain's haze consistent with the continuing geographic view; fluid/status fog stays host-owned. */
    void fog(ViewportEvent.RenderFog event) {
        if (chart() == null || vertices == null || targetFailed || !visible() || event.getMode() != net.minecraft.client.renderer.FogRenderer.FogMode.FOG_TERRAIN) { return; }
        event.setNearPlaneDistance(0);
        var current = chart();
        var planet = current instanceof PlanetChart && planetSky != null
                ? planetSky.sample(Minecraft.getInstance().level, event.getCamera().getPosition()) : null;
        event.setFarPlaneDistance(planet == null ? 24_000 : (float) Math.min(4_000_000,
                24_000 / Math.max(1e-6, planet.atmosphereDensity())));
        event.setCanceled(true);
    }

    private void update(CubeStorageChart chart, double x, double z) {
        if (pending != null && pending.future().isDone()) {
            Pending complete = pending; pending = null;
            try {
                var result = complete.future().join();
                if (!complete.cancelled().get() && sameLandscape(result.chart(), chart)
                        && Math.hypot(result.centerX() - x, result.centerZ() - z) <= 512) { install(result); }
            } catch (CancellationException ignored) {
                // Retired immutable CPU requests own no resources.
            } catch (CompletionException | IllegalStateException failure) {
                failed = true;
                AstraEngine.LOGGER.error("Distant Earth surface failed; retaining the last valid mesh", failure);
            }
        }
        if (pending == null && !failed && (mesh == null || Math.hypot(mesh.centerX() - x, mesh.centerZ() - z) > 128)) {
            double centerX = Math.clamp(Math.rint(x / 128) * 128, -chart.radiusMeters(), chart.radiusMeters());
            double centerZ = Math.clamp(Math.rint(z / 128) * 128, -chart.radiusMeters(), chart.radiusMeters());
            AtomicBoolean cancel = new AtomicBoolean();
            if (chart instanceof PlanetChart planetChart) {
                if (planetPalette == null) { planetPalette = EarthSurfaceMaterials.capturePlanet(); }
                var appearance = planetPalette;
                pending = new Pending(cancel, CompletableFuture.supplyAsync(() ->
                        ContinentalLandscape.bake(planetChart, centerX, centerZ, appearance, cancel::get), Util.backgroundExecutor()));
            } else if (chart instanceof EarthChart earthChart) {
                if (palette == null) { palette = EarthSurfaceMaterials.capture(); }
                var appearance = palette;
                pending = new Pending(cancel, CompletableFuture.supplyAsync(() ->
                        ContinentalLandscape.bake(earthChart, centerX, centerZ, appearance, cancel::get), Util.backgroundExecutor()));
            }
        }
    }

    private void install(ContinentalLandscape replacement) {
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        for (int i = 0; i < replacement.indexCount(); i++) {
            int index = replacement.vertexIndex(i);
            SpaceVector p = replacement.position(index), color = replacement.color(index), normal = replacement.normal(index);
            buffer.addVertex((float) p.x(), (float) p.y(), (float) p.z())
                    .setUv(replacement.joinWeight(index), replacement.liquid(index) ? 1 : 0)
                    .setColor((float) color.x(), (float) color.y(), (float) color.z(), 1)
                    .setNormal((float) normal.x(), (float) normal.y(), (float) normal.z());
        }
        var data = buffer.buildOrThrow();
        VertexBuffer next = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try {
            next.bind(); next.upload(data);
            if (vertices != null) { vertices.close(); }
            vertices = next; mesh = replacement;
        } catch (RuntimeException failure) {
            next.close(); throw failure;
        } finally { VertexBuffer.unbind(); }
    }

    private static void vector(ShaderInstance shader, String name, SpaceVector value) {
        shader.safeGetUniform(name).set((float) value.x(), (float) value.y(), (float) value.z());
    }

    /** Retires all per-view work/resources; registered program lifetime belongs to Minecraft. */
    @Override public void close() {
        palette = null; planetPalette = null;
        RenderSystem.assertOnRenderThread();
        cloudNoise.close();
        if (pending != null) { pending.cancelled().set(true); pending = null; }
        if (vertices != null) { vertices.close(); vertices = null; }
        if (target != null) { target.destroyBuffers(); target = null; }
        frameDepth = null; mesh = null; level = null; failed = false; targetFailed = false;
    }

    /** Borrowed depth valid only between this frame's sky and atmosphere composition. */
    record Depth(int texture, Matrix4f inverseViewProjection) {
        Depth { inverseViewProjection = new Matrix4f(inverseViewProjection); }
    }
    private record Pending(AtomicBoolean cancelled, CompletableFuture<ContinentalLandscape> future) { }
}
