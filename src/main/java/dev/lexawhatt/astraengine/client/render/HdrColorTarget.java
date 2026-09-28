package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/** Owned linear RGBA16F color attachment, without depth; used only on the render thread. */
final class HdrColorTarget implements AutoCloseable {
    final int width;
    final int height;
    private int framebuffer;
    private int texture;

    /** Allocation changes framebuffer/texture state and must be enclosed by FullscreenPass. */
    HdrColorTarget(int width, int height) {
        RenderSystem.assertOnRenderThread();
        this.width = width;
        this.height = height;
        try {
            texture = TextureUtil.generateTextureId();
            RenderSystem.bindTexture(texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, 0L);
            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texture, 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete celestial HDR framebuffer: " + status);
            }
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    int texture() { return texture; }

    void bind() {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        RenderSystem.viewport(0, 0, width, height);
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (framebuffer != 0) { GL30.glDeleteFramebuffers(framebuffer); framebuffer = 0; }
        if (texture != 0) { TextureUtil.releaseTextureId(texture); texture = 0; }
    }
}
