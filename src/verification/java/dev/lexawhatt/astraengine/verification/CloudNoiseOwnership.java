package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/** Native allocation/lifetime probe with foreign texture-unit and pixel-unpack state. Never shipped. */
final class CloudNoiseOwnership {
    private CloudNoiseOwnership() {}

    static void verify() throws Exception {
        Class<?> type = Class.forName("dev.lexawhatt.astraengine.client.render.CloudNoiseAtlas");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        var upload = type.getDeclaredMethod("ensureUploaded"); upload.setAccessible(true);
        var texture = type.getDeclaredMethod("texture"); texture.setAccessible(true);
        AutoCloseable atlas = (AutoCloseable) constructor.newInstance();
        int[] keys = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS,
                GL11.GL_UNPACK_SKIP_PIXELS, GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_UNPACK_SKIP_IMAGES};
        int[] poison = {8, 17, 2, 3, 19, 1};
        int[] previous = new int[keys.length];
        for (int i = 0; i < keys.length; i++) { previous[i] = GL11.glGetInteger(keys[i]); }
        int oldBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int buffer = GL15.glGenBuffers();
        int foreignTexture = TextureUtil.generateTextureId();
        try (var saved = new FullscreenPass(8)) {
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
                for (int i = 0; i < keys.length; i++) { GL11.glPixelStorei(keys[i], poison[i]); }
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + 7);
                RenderSystem.bindTexture(foreignTexture);
                for (int reopen = 0; reopen < 2; reopen++) {
                    try (var allocation = new FullscreenPass(4)) { upload.invoke(atlas); }
                    int owned = (Integer) texture.invoke(atlas);
                    require(owned != 0 && GL11.glIsTexture(owned), "Cloud texture was not allocated");
                    require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE0 + 7
                            && GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == foreignTexture,
                            "Cloud upload changed the foreign texture unit");
                    require(GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) == buffer,
                            "Cloud upload changed the foreign unpack buffer");
                    for (int i = 0; i < keys.length; i++) {
                        require(GL11.glGetInteger(keys[i]) == poison[i], "Cloud upload changed unpack parameter " + keys[i]);
                    }
                    try (var repeated = new FullscreenPass(4)) { upload.invoke(atlas); }
                    require((Integer) texture.invoke(atlas) == owned, "Cloud texture was rebuilt without retirement");
                    atlas.close();
                    require((Integer) texture.invoke(atlas) == 0 && !GL11.glIsTexture(owned),
                            "Cloud texture survived owner retirement");
                    require(GL11.glIsTexture(foreignTexture), "Cloud retirement deleted a foreign texture");
                    require(GL11.glGetError() == GL11.GL_NO_ERROR, "Cloud allocation produced an OpenGL error");
                }
            } finally {
                atlas.close();
                for (int i = 0; i < keys.length; i++) { GL11.glPixelStorei(keys[i], previous[i]); }
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, oldBuffer);
                GL15.glDeleteBuffers(buffer);
                TextureUtil.releaseTextureId(foreignTexture);
            }
        }
        var output = Minecraft.getInstance().gameDirectory.toPath().resolve("evidence/cloud-noise-ownership.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "Two allocation/retirement cycles preserve foreign unit7 and all six unpack parameters.\n"
                + "Repeated upload retains its texture; retirement deletes only its owned texture. OpenGL error=0.\n");
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
