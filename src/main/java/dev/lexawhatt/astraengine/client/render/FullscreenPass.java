package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.ARBImaging;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Fullscreen drawing and explicit restoration of the GL state changed by owned passes. */
public final class FullscreenPass implements AutoCloseable {
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int[] viewport = new int[4];
    private final int[] textures;
    private final ShaderInstance previousShader = RenderSystem.getShader();

    /** Captures the state before allocating or drawing into intermediate targets. Render thread only. */
    public FullscreenPass() { this(3); }

    /** Captures the first1..12 texture units used by a bounded owned shader. Render thread only. */
    public FullscreenPass(int samplerCount) {
        if (samplerCount < 1 || samplerCount > 12) {
            throw new IllegalArgumentException("Fullscreen sampler count must be in [1,12]");
        }
        textures = new int[samplerCount];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        for (int i = 0; i < textures.length; i++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
            textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        RenderSystem.activeTexture(activeTexture);
    }

    /** Draws a clip-space triangle; caller binds the destination and sets shader uniforms/samplers first. */
    public static void draw(ShaderInstance shader) {
        draw(shader, false);
    }

    /**
     * Draws a clip-space triangle, optionally replacing depth through the fragment shader.
     * Depth-writing shaders must supply valid depth for every surviving fragment. Call inside
     * a captured FullscreenPass so the temporary ALWAYS depth function is restored afterward.
     */
    public static void draw(ShaderInstance shader, boolean writeDepth) {
        draw(shader, writeDepth, 1);
    }

    /** Blends a finished display image into the current target without changing its source radiance or alpha metadata. */
    public static void drawOpacity(ShaderInstance shader, float opacity) {
        if (!Float.isFinite(opacity) || opacity < 0 || opacity > 1) {
            throw new IllegalArgumentException("Fullscreen display opacity must be in [0,1]");
        }
        draw(shader, false, opacity);
    }

    private static void draw(ShaderInstance shader, boolean writeDepth, float opacity) {
        if (writeDepth) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
        } else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthMask(writeDepth);
        RenderSystem.disableCull();
        if (opacity < 1) {
            float[] color = new float[4];
            GL11.glGetFloatv(ARBImaging.GL_BLEND_COLOR, color);
            int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
            int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
            try {
                RenderSystem.enableBlend();
                GL14.glBlendColor(0, 0, 0, opacity);
                GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                RenderSystem.blendFuncSeparate(GL14.GL_CONSTANT_ALPHA, GL14.GL_ONE_MINUS_CONSTANT_ALPHA,
                        GL11.GL_ONE, GL11.GL_ZERO);
                triangle(shader);
            } finally {
                GL14.glBlendColor(color[0], color[1], color[2], color[3]);
                RenderSystem.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
                GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            }
            return;
        }
        RenderSystem.disableBlend();
        triangle(shader);
    }

    private static void triangle(ShaderInstance shader) {
        RenderSystem.setShader(() -> shader);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
        buffer.addVertex(-1, -1, 0);
        buffer.addVertex(3, -1, 0);
        buffer.addVertex(-1, 3, 0);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    @Override
    public void close() {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        for (int i = 0; i < textures.length; i++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
            RenderSystem.bindTexture(textures[i]);
        }
        RenderSystem.activeTexture(activeTexture);
        RenderSystem.setShader(() -> previousShader);
        GlStateManager._glUseProgram(program);
        if (depth) { RenderSystem.enableDepthTest(); } else { RenderSystem.disableDepthTest(); }
        if (cull) { RenderSystem.enableCull(); } else { RenderSystem.disableCull(); }
        if (blend) { RenderSystem.enableBlend(); } else { RenderSystem.disableBlend(); }
        RenderSystem.depthFunc(depthFunction);
        RenderSystem.depthMask(depthMask);
    }
}
