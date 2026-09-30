package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Composites an owned celestial sky into clear-depth pixels of the finalized host image.
 * Use on the render thread at AFTER_LEVEL, after shader-pack world finalization and outside
 * shadow passes. The caller owns that scheduling decision. This is an LDR display composition;
 * it does not inject celestial materials into a shader pack's G-buffers, shadows or history.
 * Each instance owns its attachments; Minecraft owns the registered shader program.
 */
public final class LateSkyRenderer implements AutoCloseable {
    private final ResourceLocation shaderId;
    private ShaderInstance compose;
    private RenderTarget scene;
    private RenderTarget sky;
    private boolean allocationFailed;

    /** Each owner must supply a distinct prefix with a matching {@code <prefix>_late_sky.json} asset. */
    public LateSkyRenderer(String shaderPrefix) {
        if (shaderPrefix == null || shaderPrefix.isBlank()) {
            throw new IllegalArgumentException("Late sky shader prefix must not be blank");
        }
        shaderId = ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, shaderPrefix + "_late_sky");
    }

    /** Registers once per owner per resource reload; releases only owned attachments. */
    public void registerShaders(RegisterShadersEvent event) {
        close();
        compose = null;
        allocationFailed = false;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), shaderId,
                    DefaultVertexFormat.POSITION), loaded -> compose = loaded);
        } catch (IOException failure) {
            AstraEngine.LOGGER.error("Could not load {} late sky composition; retaining host sky", shaderId, failure);
        }
    }

    /**
     * Runs a complete opaque, display-encoded sky draw into an owned target, then replaces only
     * clear-depth host pixels. The callback must draw to the bound target and must not bind the
     * Minecraft main target. Existing celestial HDR/bloom passes may use their own intermediates.
     * Returns false without invoking the callback when unavailable. Callback failures propagate
     * after restoring host bindings and GL state; the original main color/depth remains intact
     * until composition. The host depth attachment is never changed.
     *
     * @param skyDraw non-null render-thread callback producing the full sky image
     */
    public boolean render(Runnable skyDraw) {
        RenderSystem.assertOnRenderThread();
        if (skyDraw == null) {
            throw new IllegalArgumentException("Late sky draw callback must not be null");
        }
        if (compose == null || allocationFailed) { return false; }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (main.width <= 0 || main.height <= 0) { return false; }
        try (var saved = new FullscreenPass(); var clipping = new ClippingState();
                var colors = new CelestialBloomPipeline.ColorState()) {
            try {
                try {
                    RenderSystem.activeTexture(GL13.GL_TEXTURE0);
                    ensureTargets(main);
                } catch (RuntimeException failure) {
                    // RenderTarget.checkStatus throws plain RuntimeException for framebuffer allocation failures.
                    releaseTargets();
                    allocationFailed = true;
                    AstraEngine.LOGGER.error("Could not allocate {} late sky targets; retaining host sky until reload",
                            shaderId, failure);
                    return false;
                }
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scene.frameBufferId);
                GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, scene.width, scene.height,
                        GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
                // RenderTarget.bindWrite also tells Iris that this is a mod-owned offscreen target.
                sky.bindWrite(true);
                GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[] {0, 0, 0, 1});
                skyDraw.run();
                main.bindWrite(true);
                compose.setSampler("SceneColor", scene.getColorTextureId());
                compose.setSampler("SceneDepth", scene.getDepthTextureId());
                compose.setSampler("SkyColor", sky.getColorTextureId());
                FullscreenPass.draw(compose);
                return true;
            } finally {
                // Raw framebuffer restoration alone would leave Iris's logical main-bound flag false.
                main.bindWrite(false);
            }
        }
    }

    private void ensureTargets(RenderTarget main) {
        if (scene != null && sky != null && scene.width == main.width && scene.height == main.height
                && scene.isStencilEnabled() == main.isStencilEnabled()) { return; }
        releaseTargets();
        scene = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        if (main.isStencilEnabled()) { scene.enableStencil(); }
        sky = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
    }

    /** Releases owned targets on resize, resource reload, disconnect or shutdown; safe to repeat. */
    @Override
    public void close() {
        if (scene == null && sky == null) { return; }
        try (var saved = new FullscreenPass()) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            try {
                releaseTargets();
            } finally {
                Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
            }
        }
    }

    /** Caller captures bindings; used within render allocation to avoid restoring a released partial target. */
    private void releaseTargets() {
        if (scene != null) { scene.destroyBuffers(); scene = null; }
        if (sky != null) { sky.destroyBuffers(); sky = null; }
    }

    /** Restores clip state even if the sky callback changes its scissor rectangle. */
    private static final class ClippingState implements AutoCloseable {
        private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        private final int[] rectangle = new int[4];

        private ClippingState() {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, rectangle);
            RenderSystem.disableScissor();
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }

        @Override
        public void close() {
            RenderSystem.enableScissor(rectangle[0], rectangle[1], rectangle[2], rectangle[3]);
            if (!scissor) { RenderSystem.disableScissor(); }
            if (stencil) { GL11.glEnable(GL11.GL_STENCIL_TEST); }
            else { GL11.glDisable(GL11.GL_STENCIL_TEST); }
        }
    }
}
