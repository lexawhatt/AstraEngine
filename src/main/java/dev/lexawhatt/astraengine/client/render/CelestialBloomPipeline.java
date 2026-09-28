package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/**
 * Render-thread celestial radiance, soft-knee bloom pyramid and one display transform.
 * Each renderer owns its attachments. Minecraft owns the uniquely named registered programs.
 * The pass runs before terrain and HUD; it does not reinterpret Minecraft's LDR materials.
 */
public final class CelestialBloomPipeline implements AutoCloseable {
    private final String shaderPrefix;
    private ShaderInstance downsample;
    private ShaderInstance upsample;
    private ShaderInstance composite;
    private HdrColorTarget scene;
    private HdrColorTarget[] down = new HdrColorTarget[0];
    private HdrColorTarget[] up = new HdrColorTarget[0];
    private boolean allocationFailed;

    /** A distinct resource prefix prevents duplicate shader registrations from leaking programs. */
    public CelestialBloomPipeline(String shaderPrefix) {
        this.shaderPrefix = shaderPrefix;
    }

    /** Releases owned attachments on reload; shader disposal remains Minecraft's responsibility. */
    public void registerShaders(RegisterShadersEvent event) {
        close();
        allocationFailed = false;
        downsample = null;
        upsample = null;
        composite = null;
        try {
            event.registerShader(load(event, "down"), loaded -> downsample = loaded);
            event.registerShader(load(event, "up"), loaded -> upsample = loaded);
            event.registerShader(load(event, "compose"), loaded -> composite = loaded);
        } catch (IOException failure) {
            AstraEngine.LOGGER.error("Could not load {} celestial HDR pipeline; using direct sky", shaderPrefix, failure);
        }
    }

    private ShaderInstance load(RegisterShadersEvent event, String suffix) throws IOException {
        return new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, shaderPrefix + "_bloom_" + suffix),
                DefaultVertexFormat.POSITION);
    }

    /**
     * Draws celestial ShaderInstance with HdrOutput=1, then composes into the exact incoming
     * framebuffer/viewport. Returns false if unavailable, so the owner can draw its display fallback.
     * Exposure is the final exposure, not an additional multiplier of the shared options value.
     */
    public boolean render(ShaderInstance celestial, RenderOptions options, float exposure) {
        if (options == null || allocationFailed || downsample == null || upsample == null || composite == null) {
            return false;
        }
        try (var saved = new FullscreenPass(); var masks = new ColorState()) {
            int destination = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int[] viewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            if (viewport[2] <= 0 || viewport[3] <= 0) { return false; }
            int levels = options.quality() == RenderOptions.Quality.LOW ? 4
                    : options.quality() == RenderOptions.Quality.HIGH ? 6 : 5;
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            try {
                ensureTargets(viewport[2], viewport[3], levels);
            } catch (IllegalStateException failure) {
                close();
                allocationFailed = true;
                AstraEngine.LOGGER.error("Could not allocate celestial HDR targets; using direct sky until reload", failure);
                return false;
            }
            scene.bind();
            celestial.safeGetUniform("HdrOutput").set(1);
            FullscreenPass.draw(celestial);
            boolean bloom = options.bloom() && options.bloomStrength() > 0;
            if (bloom) {
                HdrColorTarget source = scene;
                for (int i = 0; i < down.length; i++) {
                    down[i].bind();
                    downsample.setSampler("Source", source.texture());
                    downsample.safeGetUniform("SourceTexel").set(1.0f / source.width, 1.0f / source.height);
                    downsample.safeGetUniform("Extract").set(i == 0 ? 1 : 0);
                    downsample.safeGetUniform("Threshold").set(options.bloomThreshold());
                    FullscreenPass.draw(downsample);
                    source = down[i];
                }
                // Each level combines normalized energy from its own band and a wider band.
                // No additive blending or framebuffer feedback is required.
                for (int i = down.length - 2; i >= 0; i--) {
                    up[i].bind();
                    upsample.setSampler("Source", source.texture());
                    upsample.setSampler("DetailColor", down[i].texture());
                    upsample.safeGetUniform("SourceTexel").set(1.0f / source.width, 1.0f / source.height);
                    upsample.safeGetUniform("Scatter").set(0.35f + options.bloomRadius() * 0.55f);
                    FullscreenPass.draw(upsample);
                    source = up[i];
                }
            }
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            composite.setSampler("SceneColor", scene.texture());
            composite.setSampler("BloomColor", bloom ? up[0].texture() : scene.texture());
            composite.safeGetUniform("BloomStrength").set(bloom ? options.bloomStrength() : 0);
            composite.safeGetUniform("Exposure").set(Math.clamp(exposure, 0.1f, 4));
            FullscreenPass.draw(composite);
            return true;
        }
    }

    private void ensureTargets(int width, int height, int levels) {
        if (scene != null && scene.width == width && scene.height == height && down.length == levels) { return; }
        close();
        scene = new HdrColorTarget(width, height);
        down = new HdrColorTarget[levels];
        up = new HdrColorTarget[levels - 1];
        for (int i = 0; i < levels; i++) {
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
            down[i] = new HdrColorTarget(width, height);
            if (i < levels - 1) { up[i] = new HdrColorTarget(width, height); }
        }
    }

    /** Releases only owned GPU attachments. May be repeated on resize, reload, logout and shutdown. */
    @Override
    public void close() {
        if (scene != null) { scene.close(); scene = null; }
        for (var target : down) { if (target != null) { target.close(); } }
        for (var target : up) { if (target != null) { target.close(); } }
        down = new HdrColorTarget[0];
        up = new HdrColorTarget[0];
    }

    /** Extra state beyond FullscreenPass: explicit gamma, unrestricted viewport and all color channels. */
    private static final class ColorState implements AutoCloseable {
        private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final boolean srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
        private final boolean[] mask = new boolean[4];
        private final int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        private final int destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        private final int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        private final int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);

        private ColorState() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer values = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, values);
                for (int i = 0; i < mask.length; i++) { mask[i] = values.get(i) != 0; }
            }
            RenderSystem.disableScissor();
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            RenderSystem.colorMask(true, true, true, true);
        }

        @Override
        public void close() {
            RenderSystem.colorMask(mask[0], mask[1], mask[2], mask[3]);
            RenderSystem.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            if (scissor) { GlStateManager._enableScissorTest(); }
            if (srgb) { GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB); }
        }
    }
}
