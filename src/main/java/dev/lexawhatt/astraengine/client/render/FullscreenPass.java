package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
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
    private final int[] textures = new int[3];
    private final ShaderInstance previousShader = RenderSystem.getShader();

    /** Captures the state before allocating or drawing into intermediate targets. Render thread only. */
    public FullscreenPass() {
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
        if (writeDepth) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
        } else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthMask(writeDepth);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
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
