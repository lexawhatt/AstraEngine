package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.sky.CloudNoiseField;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Render-thread cloud/air transport. Owns attachments; registered programs remain Minecraft-owned. */
final class AtmosphereVolumeRenderer implements AutoCloseable {
    private final CloudNoiseAtlas noise = new CloudNoiseAtlas();
    private ShaderInstance transport;
    private ShaderInstance compose;
    private HdrColorTarget sky;
    private HdrColorTarget geometry;
    private RenderTarget scene;
    private boolean failed;
    private boolean terrainDepthCaptured;
    private Frame frame;

    /** One sky extraction shared by the later geometry pass; no simulation is advanced here. */
    record Frame(ClientLevel level, Matrix4f inverseViewProjection, SpaceVector originKm, SpaceVector sun,
                 float windX, float windZ, float coverage, float aerosol, float incident, float moonlight,
                 float rain, float thunder, float skyAccess, float exposure, boolean shafts,
                 RenderOptions.Quality quality, float cloudBaseKm, float cloudTopKm) {
        Frame { inverseViewProjection = new Matrix4f(inverseViewProjection); }
    }

    void registerShaders(RegisterShadersEvent event) {
        close();
        failed = false;
        transport = null;
        compose = null;
        try {
            event.registerShader(load(event, "atmosphere_volume"), value -> transport = value);
            event.registerShader(load(event, "atmosphere_compose"), value -> compose = value);
        } catch (IOException failure) {
            AstraEngine.LOGGER.error("Could not load volumetric atmosphere; retaining layered sky fallback", failure);
        }
    }

    private static ShaderInstance load(RegisterShadersEvent event, String name) throws IOException {
        return new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, name), DefaultVertexFormat.POSITION);
    }

    void clearFrame() { frame = null; terrainDepthCaptured = false; }

    /** Preserve actual opaque depth before Fabulous resolves transparency with a fullscreen depth-writing quad. */
    void captureTerrainDepth() {
        Minecraft game = Minecraft.getInstance();
        if (frame == null || frame.level != game.level || failed || scene == null) { return; }
        RenderTarget main = game.getMainRenderTarget();
        if (scene.width != main.width || scene.height != main.height) { return; }
        try (var saved = new FullscreenPass()) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scene.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, scene.width, scene.height,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            terrainDepthCaptured = true;
        }
    }

    /** Produces a reduced-resolution linear transport image before celestial HDR composition. */
    boolean sky(Frame extracted, ShaderInstance skyProgram) {
        frame = null;
        if (transport == null || compose == null || failed || extracted.coverage <= 0) { return false; }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        try (var saved = new FullscreenPass(4); var masks = new CelestialBloomPipeline.ColorState()) {
            try {
                noise.ensureUploaded();
                ensureTargets(main, extracted.quality);
            } catch (RuntimeException failure) {
                close();
                failed = true;
                AstraEngine.LOGGER.error("Could not allocate volumetric atmosphere; using layered sky until reload", failure);
                return false;
            }
            uniforms(extracted, main);
            sky.bind();
            transport.safeGetUniform("GeometryPass").set(0);
            distantUniforms(transport, null);
            transport.setSampler("SceneDepth", main.getDepthTextureId());
            FullscreenPass.draw(transport);
            skyProgram.setSampler("CloudTransport", sky.texture());
            frame = extracted;
            return true;
        }
    }

    /** Clips scattering to copied world depth, leaving the already-composited sky and host depth unchanged. */
    void world(EarthLandscapeRenderer.Depth distant) {
        Frame current = frame;
        frame = null;
        Minecraft game = Minecraft.getInstance();
        if (current == null || current.level != game.level || failed || scene == null || !terrainDepthCaptured) { return; }
        terrainDepthCaptured = false;
        RenderTarget main = game.getMainRenderTarget();
        if (scene.width != main.width || scene.height != main.height) { return; }
        try (var saved = new FullscreenPass(4); var masks = new CelestialBloomPipeline.ColorState()) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scene.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, scene.width, scene.height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            uniforms(current, main);
            geometry.bind();
            transport.safeGetUniform("GeometryPass").set(1);
            distantUniforms(transport, distant);
            transport.setSampler("SceneDepth", scene.getDepthTextureId());
            FullscreenPass.draw(transport);
            main.bindWrite(true);
            distantUniforms(compose, distant);
            compose.setSampler("SceneColor", scene.getColorTextureId());
            compose.setSampler("SceneDepth", scene.getDepthTextureId());
            compose.setSampler("Transport", geometry.texture());
            compose.safeGetUniform("InverseViewProjection").set(current.inverseViewProjection);
            compose.safeGetUniform("TransportSize").set((float) geometry.width, (float) geometry.height);
            compose.safeGetUniform("Exposure").set(current.exposure);
            FullscreenPass.draw(compose);
        }
    }

    private static void distantUniforms(ShaderInstance shader, EarthLandscapeRenderer.Depth depth) {
        shader.safeGetUniform("DistantReady").set(depth == null ? 0 : 1);
        shader.setSampler("DistantDepth", depth == null ? 0 : depth.texture());
        shader.safeGetUniform("DistantInverseViewProjection").set(depth == null ? new Matrix4f() : depth.inverseViewProjection());
    }

    private void uniforms(Frame value, RenderTarget main) {
        transport.setSampler("CloudNoise", noise.texture());
        transport.safeGetUniform("CloudNoiseLayout").set((float) CloudNoiseField.PERIOD, (float) CloudNoiseField.TILE_SIZE,
                (float) CloudNoiseField.ATLAS_SIZE, (float) CloudNoiseField.TILES_PER_ROW);
        transport.safeGetUniform("InverseViewProjection").set(value.inverseViewProjection);
        transport.safeGetUniform("ObserverKm").set((float) value.originKm.x(), (float) value.originKm.y(),
                (float) value.originKm.z());
        transport.safeGetUniform("SunDirection").set((float) value.sun.x(), (float) value.sun.y(), (float) value.sun.z());
        transport.safeGetUniform("CloudWind").set(value.windX, value.windZ);
        transport.safeGetUniform("CloudLayer").set(value.cloudBaseKm, value.cloudTopKm);
        transport.safeGetUniform("CloudParams").set(value.coverage, value.aerosol, value.incident, value.moonlight);
        transport.safeGetUniform("Weather").set(value.rain, value.thunder);
        transport.safeGetUniform("ViewSteps").set(32 + value.quality.ordinal() * 16);
        transport.safeGetUniform("ShadowSteps").set(4 + value.quality.ordinal());
        transport.safeGetUniform("ShaftStrength").set(value.shafts ? 1.0f : 0.0f);
        transport.safeGetUniform("SkyAccess").set(value.skyAccess);
        transport.safeGetUniform("SceneTexel").set(1.0f / main.width, 1.0f / main.height);
        transport.safeGetUniform("VolumeTexel").set(1.0f / sky.width, 1.0f / sky.height);
    }

    private void ensureTargets(RenderTarget main, RenderOptions.Quality quality) {
        int divisor = switch (quality) { case LOW -> 6; case BALANCED -> 4; case HIGH -> 2; };
        int width = Math.max(1, main.width / divisor);
        int height = Math.max(1, main.height / divisor);
        if (scene != null && scene.width == main.width && scene.height == main.height
                && scene.isStencilEnabled() == main.isStencilEnabled() && sky.width == width && sky.height == height) { return; }
        closeTargets();
        scene = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        if (main.isStencilEnabled()) { scene.enableStencil(); }
        sky = new HdrColorTarget(width, height);
        geometry = new HdrColorTarget(width, height);
    }

    @Override
    public void close() {
        closeTargets();
        noise.close();
    }

    private void closeTargets() {
        frame = null; terrainDepthCaptured = false;
        if (sky != null) { sky.close(); sky = null; }
        if (geometry != null) { geometry.close(); geometry = null; }
        if (scene != null) { scene.destroyBuffers(); scene = null; }
    }
}
