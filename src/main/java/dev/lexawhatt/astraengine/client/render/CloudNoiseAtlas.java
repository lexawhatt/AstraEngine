package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.client.sky.CloudNoiseField;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** One atmosphere owner's immutable CPU field and reloadable RG8 texture. No host texture is modified. */
final class CloudNoiseAtlas implements AutoCloseable {
    private final CloudNoiseField field = new CloudNoiseField();
    private int texture;

    /** Render thread; caller encloses allocation in FullscreenPass to retain texture bindings. */
    void ensureUploaded() {
        RenderSystem.assertOnRenderThread();
        if (texture != 0) { return; }
        int previousBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] parameters = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS,
                GL11.GL_UNPACK_SKIP_PIXELS, GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_UNPACK_SKIP_IMAGES};
        int[] previous = new int[parameters.length];
        for (int i = 0; i < parameters.length; i++) { previous[i] = GL11.glGetInteger(parameters[i]); }
        byte[] data = field.copyAtlas();
        ByteBuffer upload = MemoryUtil.memAlloc(data.length);
        int created = 0;
        try {
            upload.put(data).flip();
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int p : parameters) { GL11.glPixelStorei(p, p == GL11.GL_UNPACK_ALIGNMENT ? 1 : 0); }
            // FullscreenPass captures the owned sampler range, not an arbitrary previously active unit.
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            created = TextureUtil.generateTextureId();
            RenderSystem.bindTexture(created);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG8, CloudNoiseField.ATLAS_SIZE,
                    CloudNoiseField.ATLAS_SIZE, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, upload);
            if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != CloudNoiseField.ATLAS_SIZE) {
                throw new IllegalStateException("Could not allocate cloud-noise texture");
            }
            texture = created; created = 0;
        } finally {
            if (created != 0) { TextureUtil.releaseTextureId(created); }
            MemoryUtil.memFree(upload);
            for (int i = 0; i < parameters.length; i++) { GL11.glPixelStorei(parameters[i], previous[i]); }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, previousBuffer);
        }
    }

    int texture() { return texture; }

    /** Render thread; retains only immutable numeric source data so reopening needs no world access. */
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (texture != 0) { TextureUtil.releaseTextureId(texture); texture = 0; }
    }
}
