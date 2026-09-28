package dev.lexawhatt.astraengine.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.SystemSnapshot;
import dev.lexawhatt.astraengine.client.environment.EnvironmentProfile;
import dev.lexawhatt.astraengine.client.environment.EnvironmentProfiles;
import dev.lexawhatt.astraengine.client.lighting.CollectSceneLightsEvent;
import dev.lexawhatt.astraengine.client.lighting.LightCollector;
import dev.lexawhatt.astraengine.client.lighting.LightVector;
import dev.lexawhatt.astraengine.client.lighting.SceneLight;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import dev.lexawhatt.astraengine.client.render.RenderOptions;
import dev.lexawhatt.astraengine.client.render.WorldLightingPipeline;
import dev.lexawhatt.astraengine.client.render.SceneShapeRenderer;
import dev.lexawhatt.astraengine.client.editor.SceneEditor;
import dev.lexawhatt.astraengine.network.SystemPayload;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.network.SystemSnapshotReceivedEvent;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;

/** Client-only renderer; the celestial sky has no block/entity representation and ignores camera translation. */
public final class SpaceRenderer {
    private ShaderInstance shader;
    private SystemPayload latest;
    private long receivedAtTick;
    private final EnvironmentProfiles profiles;
    private final RenderOptions options;
    private final WorldLightingPipeline pipeline = new WorldLightingPipeline();
    private final SceneShapeRenderer shapes = new SceneShapeRenderer();
    private final SceneEditor editor;
    private Frame frame;

    /** Owns one client rendering session; profile and option services are injected at registration. */
    public SpaceRenderer(EnvironmentProfiles profiles, RenderOptions options, SceneEditor editor) {
        this.profiles = profiles;
        this.options = options;
        this.editor = editor;
    }

    /** Shader replacement is owned by Minecraft's reload lifecycle, including disposal of old instances. */
    public void registerShaders(RegisterShadersEvent event) {
        frame = null;
        pipeline.registerShaders(event);
        shapes.registerShaders(event);
        editor.shaders().close();
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "space"), DefaultVertexFormat.POSITION),
                    loaded -> shader = loaded);
        } catch (IOException exception) {
            shader = null;
            AstraEngine.LOGGER.error("Could not load AstraEngine sky shader; retaining the black void fallback", exception);
        }
    }

    /** Accepts a server snapshot only for the current dimension and a non-stale revision. */
    public void receive(SystemSnapshotReceivedEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        SystemPayload payload = event.payload();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(payload.dimension())) { return; }
        if (latest != null && latest.dimension().equals(payload.dimension())
                && latest.snapshot().descriptor().id().equals(payload.snapshot().descriptor().id())
                && latest.snapshot().revision() > payload.snapshot().revision()) { return; }
        latest = payload;
        receivedAtTick = minecraft.level.getGameTime();
    }

    /** Clears per-connection data so integrated and dedicated sessions never share celestial state. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        latest = null;
        frame = null;
        if (RenderSystem.isOnRenderThread()) { pipeline.close(); shapes.close(); }
        else { RenderSystem.recordRenderCall(() -> { pipeline.close(); shapes.close(); }); }
    }

    private boolean visible() {
        Minecraft minecraft = Minecraft.getInstance();
        return latest != null && minecraft.level != null
                && latest.dimension().equals(minecraft.level.dimension().location());
    }

    /** Runs sky, opaque lighting and final composition at separate NeoForge stages. */
    public void render(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level.dimension().equals(RocketService.FLIGHT)) {
            if (frame != null) {
                try (var state = new FullscreenPass()) { pipeline.close(); shapes.close(); }
                frame = null;
            }
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) { editor.shaders().render(event); }
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            frame = buildFrame(event);
            if (frame != null && frame.drawSky && shader != null) { renderSky(event); }
        } else if (frame != null && minecraft.level != null
                && frame.dimension.equals(minecraft.level.dimension().location())) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
                shapes.render(event, editor.visibleObjects());
                pipeline.light(event, frame.profile, frame.lights, frame.skyExposure, frame.daylight, options);
            } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                pipeline.compose(frame.profile, options);
            }
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) { editor.shaders().render(event); }
    }

    private Frame buildFrame(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        EnvironmentProfile profile = minecraft.level == null ? null
                : profiles.resolve(options.environment(), minecraft.level.dimension().location(), visible());
        boolean drawSky = profile != null;
        if (profile == null && !options.environment().equals("off") && !editor.visibleObjects().isEmpty()) {
            profile = EnvironmentProfile.SPACE;
        }
        if (profile == null) {
            if (frame != null) { try (var state = new FullscreenPass()) { pipeline.close(); } }
            shapes.close();
            options.reportLights(0);
            return null;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        LightVector sun = profile.sunDirection(minecraft.level.getDayTime() % 24000 + partial);
        float daylight = profile.planetary() ? (float) Math.clamp(sun.y() * 4 + 0.1, 0, 1) : 1;
        var position = event.getCamera().getPosition();
        LightVector camera = new LightVector(position.x, position.y, position.z);
        BlockPos eye = BlockPos.containing(position);
        float exposure = 1;
        if (profile.planetary()) {
            exposure = minecraft.level.dimensionType().hasSkyLight()
                    ? minecraft.level.getBrightness(LightLayer.SKY, eye) / 15.0f
                    : (minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, eye.getX(), eye.getZ()) <= eye.getY() ? 1 : 0);
        }
        LightCollector collector = new LightCollector(camera, options.quality().lights,
                minecraft.options.getEffectiveRenderDistance() * 16.0);
        float warm = profile.planetary() ? (float) Math.clamp(sun.y() * 2, 0, 1) : 0.8f;
        float remnant = visible() && latest.snapshot().remainingResource() == 0 ? 0.2f : 1;
        collector.add(SceneLight.directional("astraengine:sun", sun, new LightVector(1, 0.4 + warm * 0.5, 0.2 + warm * 0.8),
                profile.sunStrength() * daylight * exposure * remnant));
        if (options.flashlight()) {
            var direction = event.getCamera().getLookVector();
            collector.add(new SceneLight("astraengine:flashlight", SceneLight.Kind.SPOT, camera,
                    new LightVector(direction.x(), direction.y(), direction.z()), new LightVector(1, 0.91, 0.75),
                    3.5f, 28, 12, 23, false));
        }
        NeoForge.EVENT_BUS.post(new CollectSceneLightsEvent(minecraft.level, camera, collector, partial));
        List<SceneLight> lights = collector.seal();
        options.reportLights(lights.size());
        return new Frame(minecraft.level.dimension().location(), profile, sun, daylight, exposure, lights, drawSky);
    }

    private void renderSky(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        SystemSnapshot snapshot = visible() ? latest.snapshot() : null;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float elapsed = snapshot == null ? 0 : Math.min(5, Math.max(0, minecraft.level.getGameTime() - receivedAtTick)) + partial;
        Matrix4f view = new Matrix4f(event.getModelViewMatrix()).setTranslation(0, 0, 0);
        shader.safeGetUniform("InverseViewProjection").set(new Matrix4f(event.getProjectionMatrix()).mul(view).invert());
        shader.safeGetUniform("Time").set(snapshot == null ? (minecraft.level.getGameTime() % 24000 + partial) / 20.0f
                : (snapshot.activeTicks() % 24000 + elapsed) / 20.0f);
        shader.safeGetUniform("Seed").set(snapshot == null ? 271.0f : (float) Math.floorMod(snapshot.descriptor().seed(), 8192));
        shader.safeGetUniform("Temperature").set(snapshot == null ? 5778.0f : (float) snapshot.descriptor().temperatureKelvin());
        shader.safeGetUniform("Resource").set(snapshot == null ? 1.0f : (float) snapshot.resourceFraction());
        shader.safeGetUniform("Stage").set(snapshot == null || frame.profile.planetary() ? 0 : snapshot.stage().ordinal());
        shader.safeGetUniform("Planets").set(frame.profile.planetary() ? 0 : snapshot == null ? 3 : snapshot.descriptor().planetCount());
        boolean transit = visible() && latest.transitTicks() > 0;
        shader.safeGetUniform("Transit").set(transit ? 1.0f : 0.0f);
        shader.safeGetUniform("TravelProgress").set(transit ? Math.min(1, 1 - (latest.transitTicks() - elapsed) / 80.0f) : 0.0f);
        shader.safeGetUniform("SunDirection").set((float) frame.sun.x(), (float) frame.sun.y(), (float) frame.sun.z());
        shader.safeGetUniform("Daylight").set(frame.daylight);
        shader.safeGetUniform("Planetary").set(frame.profile.planetary() ? 1 : 0);
        shader.safeGetUniform("Rings").set(frame.profile.rings() ? 1 : 0);
        shader.safeGetUniform("RingTilt").set(frame.profile.ringTilt());
        try (var state = new FullscreenPass()) { FullscreenPass.draw(shader); }
    }

    private record Frame(ResourceLocation dimension, EnvironmentProfile profile, LightVector sun,
                         float daylight, float skyExposure, List<SceneLight> lights, boolean drawSky) {}

    /** Small diagnostic HUD for the first slice; SolarTech owns its future gameplay screens. */
    public void renderHud(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!visible() || minecraft.options.hideGui) { return; }
        SystemSnapshot snapshot = latest.snapshot();
        Component label = latest.transitTicks() > 0
                ? Component.translatable("astraengine.hud.transit", snapshot.descriptor().id())
                : Component.translatable("astraengine.hud.system", snapshot.descriptor().id(),
                        Component.translatable("astraengine.stage." + snapshot.stage().name().toLowerCase(Locale.ROOT)));
        event.getGuiGraphics().fill(9, 9, 286, 43, 0xAA070A10);
        event.getGuiGraphics().drawString(minecraft.font, label, 17, 15, 0xFFE7EDF2);
        event.getGuiGraphics().drawString(minecraft.font, Component.translatable("astraengine.hud.resource",
                String.format(Locale.ROOT, "%.1f", snapshot.resourceFraction() * 100)), 17, 29, 0xFF85C8D9);
    }
}
