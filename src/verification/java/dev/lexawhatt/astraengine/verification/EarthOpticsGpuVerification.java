package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import dev.lexawhatt.astraengine.surface.EarthAtmosphereOptics;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;

/** Isolated real-GPU upload check. Deliberately unusual host pixel-transfer state must survive intact. */
final class EarthOpticsGpuVerification {
    private EarthOpticsGpuVerification() { }

    static String verify() throws Exception {
        RenderSystem.assertOnRenderThread();
        int[] unpack = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS,
                GL11.GL_UNPACK_SKIP_PIXELS, GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_UNPACK_SKIP_IMAGES,
                GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST};
        int[] unusual = {8, 199, 2, 3, 11, 1, 1, 1};
        int[] pack = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS,
                GL11.GL_PACK_SKIP_PIXELS, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES,
                GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST};
        int[] savedUnpack = snapshot(unpack), savedPack = snapshot(pack);
        int savedUnpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int savedPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int buffer = 0, texture = 0;
        var readback = MemoryUtil.memAllocFloat(EarthAtmosphereOptics.WIDTH * EarthAtmosphereOptics.TEXTURE_HEIGHT * 4);
        try (var state = new FullscreenPass()) {
            buffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, 64, GL15.GL_STATIC_DRAW);
            restore(unpack, unusual);
            var type = Class.forName("dev.lexawhatt.astraengine.client.render.EarthAtmosphereCache");
            var upload = type.getDeclaredMethod("upload", EarthAtmosphereOptics.class);
            upload.setAccessible(true);
            texture = (int) upload.invoke(null, EarthAtmosphereOptics.bake(6371, () -> false));
            require(GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) == buffer,
                    "Optical upload replaced the host unpack buffer binding");
            for (int i = 0; i < unpack.length; i++) {
                require(GL11.glGetInteger(unpack[i]) == unusual[i], "Optical upload changed host pixel-unpack state");
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int parameter : pack) { GL11.glPixelStorei(parameter, parameter == GL11.GL_PACK_ALIGNMENT ? 4 : 0); }
            RenderSystem.activeTexture(GL13.GL_TEXTURE0); RenderSystem.bindTexture(texture);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, readback);
            require(Math.abs(readback.get(0) - 8 * (1 - Math.exp(-10))) < .0001
                            && Math.abs(readback.get(1) - 1.2) < .001 && Math.abs(readback.get(2) - 15) < .0001
                            && readback.get(3) == 1,
                    "Actual optical texture does not contain the analytic vertical density columns");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Optical upload or readback left a GL error");
            return "GPU optics: analytic RGBA32F columns; bound unpack PBO; alignment/row/skips/swap restored PASS";
        } finally {
            if (texture != 0) { TextureUtil.releaseTextureId(texture); }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, savedUnpackBuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, savedPackBuffer);
            restore(unpack, savedUnpack); restore(pack, savedPack);
            if (buffer != 0) { GL15.glDeleteBuffers(buffer); }
            MemoryUtil.memFree(readback);
        }
    }

    private static int[] snapshot(int[] parameters) {
        int[] values = new int[parameters.length];
        for (int i = 0; i < parameters.length; i++) { values[i] = GL11.glGetInteger(parameters[i]); }
        return values;
    }
    private static void restore(int[] parameters, int[] values) {
        for (int i = 0; i < parameters.length; i++) { GL11.glPixelStorei(parameters[i], values[i]); }
    }
    private static void require(boolean valid, String message) { if (!valid) { throw new IllegalStateException(message); } }
}
