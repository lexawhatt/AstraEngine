package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.environment.EnvironmentProfile;
import dev.lexawhatt.astraengine.client.lighting.SceneLight;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Owns intermediate color targets, never the main framebuffer or Minecraft's spectator post chain. */
public final class WorldLightingPipeline implements AutoCloseable {
    private ShaderInstance lighting;
    private ShaderInstance blur;
    private ShaderInstance composite;
    private RenderTarget scene;
    private RenderTarget bloomA;
    private RenderTarget bloomB;

    /** Minecraft owns shader disposal; this pipeline releases only its own framebuffer attachments. */
    public void registerShaders(RegisterShadersEvent event) {
        close();
        lighting = null;
        blur = null;
        composite = null;
        try {
            event.registerShader(load(event, "lighting"), shader -> lighting = shader);
            event.registerShader(load(event, "bloom"), shader -> blur = shader);
            event.registerShader(load(event, "composite"), shader -> composite = shader);
        } catch (IOException exception) {
            AstraEngine.LOGGER.error("Could not load AstraEngine lighting pipeline; affected passes remain disabled", exception);
        }
    }

    private static ShaderInstance load(RegisterShadersEvent event, String name) throws IOException {
        return new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, name),
                DefaultVertexFormat.POSITION);
    }

    /** Adds directional/point/spot illumination to visible opaque geometry while preserving sky and world depth. */
    public void light(RenderLevelStageEvent event, EnvironmentProfile profile, List<SceneLight> lights,
                      float skyExposure, float daylight, RenderOptions options) {
        if (lighting == null || !options.lighting()) { return; }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        try (var state = new FullscreenPass()) {
            ensureTargets(main);
            copy(main, scene, true);
            main.bindWrite(true);
            lighting.setSampler("SceneColor", scene.getColorTextureId());
            lighting.setSampler("SceneDepth", scene.getDepthTextureId());
            Matrix4f viewProjection = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
            lighting.safeGetUniform("InverseViewProjection").set(new Matrix4f(viewProjection).invert());
            lighting.safeGetUniform("ViewProjection").set(viewProjection);
            lighting.safeGetUniform("ScreenSize").set((float) main.width, (float) main.height);
            lighting.safeGetUniform("BaseGain").set(profile.planetary()
                    ? profile.caveFloor() + profile.ambient() * skyExposure * (0.06f + daylight * 0.94f)
                    : profile.ambient());
            lighting.safeGetUniform("LightCount").set(lights.size());
            lighting.safeGetUniform("ShadowSteps").set(options.quality().shadowSteps);
            var camera = event.getCamera().getPosition();
            for (int i = 0; i < lights.size(); i++) {
                SceneLight light = lights.get(i);
                boolean directional = light.kind() == SceneLight.Kind.DIRECTIONAL;
                lighting.safeGetUniform("LightPosition[" + i + "]").set(
                        (float) (light.position().x() - camera.x), (float) (light.position().y() - camera.y),
                        (float) (light.position().z() - camera.z), directional ? 0 : light.range());
                lighting.safeGetUniform("LightDirection[" + i + "]").set((float) light.direction().x(),
                        (float) light.direction().y(), (float) light.direction().z(), light.kind() == SceneLight.Kind.SPOT
                                ? (float) Math.cos(Math.toRadians(light.outerDegrees())) : -1);
                lighting.safeGetUniform("LightColor[" + i + "]").set((float) light.color().x(),
                        (float) light.color().y(), (float) light.color().z(), light.intensity());
                lighting.safeGetUniform("LightParameters[" + i + "]").set(
                        (float) Math.cos(Math.toRadians(light.innerDegrees())), light.contactShadows() ? 1 : 0);
            }
            FullscreenPass.draw(lighting);
        }
    }

    /** Half-resolution separable bloom and bounded exposure; first-person hand and HUD render afterward. */
    public void compose(EnvironmentProfile profile, RenderOptions options) {
        if (blur == null || composite == null || (!options.bloom() && profile.exposure() == 1)) { return; }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        try (var state = new FullscreenPass()) {
            ensureTargets(main);
            copy(main, scene, false);
            bloomA.bindWrite(true);
            blur.setSampler("Source", scene.getColorTextureId());
            blur.safeGetUniform("TexelStep").set(1.0f / main.width, 0);
            blur.safeGetUniform("Extract").set(1);
            FullscreenPass.draw(blur);
            bloomB.bindWrite(true);
            blur.setSampler("Source", bloomA.getColorTextureId());
            blur.safeGetUniform("TexelStep").set(0, 1.0f / bloomA.height);
            blur.safeGetUniform("Extract").set(0);
            FullscreenPass.draw(blur);
            main.bindWrite(true);
            composite.setSampler("SceneColor", scene.getColorTextureId());
            composite.setSampler("BloomColor", bloomB.getColorTextureId());
            composite.safeGetUniform("BloomStrength").set(options.bloom() ? profile.bloom() : 0);
            composite.safeGetUniform("Exposure").set(profile.exposure());
            FullscreenPass.draw(composite);
        }
    }

    private void ensureTargets(RenderTarget main) {
        if (scene != null && scene.width == main.width && scene.height == main.height
                && scene.isStencilEnabled() == main.isStencilEnabled()) { return; }
        close();
        scene = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        if (main.isStencilEnabled()) { scene.enableStencil(); }
        bloomA = new TextureTarget(Math.max(1, main.width / 2), Math.max(1, main.height / 2), false, Minecraft.ON_OSX);
        bloomB = new TextureTarget(bloomA.width, bloomA.height, false, Minecraft.ON_OSX);
        bloomA.setFilterMode(GL11.GL_LINEAR);
        bloomB.setFilterMode(GL11.GL_LINEAR);
    }

    private static void copy(RenderTarget from, RenderTarget to, boolean depth) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, from.width, from.height, 0, 0, to.width, to.height,
                GL11.GL_COLOR_BUFFER_BIT | (depth ? GL11.GL_DEPTH_BUFFER_BIT : 0), GL11.GL_NEAREST);
    }

    /** Releases only owned GPU attachments; safe to call repeatedly on the render thread. */
    @Override
    public void close() {
        if (scene != null) { scene.destroyBuffers(); scene = null; }
        if (bloomA != null) { bloomA.destroyBuffers(); bloomA = null; }
        if (bloomB != null) { bloomB.destroyBuffers(); bloomB = null; }
    }
}
