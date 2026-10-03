package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.surface.EarthAtmosphereOptics;
import java.nio.FloatBuffer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.Util;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** Render-thread owner of one176KiB optical/irradiance texture and one cancellable, radius-only CPU request. */
final class EarthAtmosphereCache implements AutoCloseable {
    private double radiusKm;
    private int texture;
    private boolean failed;
    private Pending pending;

    /** Zero retires the active atmosphere. No update blocks for unfinished work or retains a host level. */
    void update(double requestedRadiusKm) {
        RenderSystem.assertOnRenderThread();
        if (requestedRadiusKm != radiusKm) { close(); radiusKm = requestedRadiusKm; }
        if (radiusKm <= 0 || failed) { return; }
        if (pending != null && pending.future.isDone()) {
            Pending complete = pending; pending = null;
            try {
                EarthAtmosphereOptics optics = complete.future.join();
                if (!complete.cancelled.get()) {
                    int replacement = upload(optics);
                    if (texture != 0) { TextureUtil.releaseTextureId(texture); }
                    texture = replacement;
                }
            } catch (CancellationException ignored) {
                // A retired immutable request owns no GPU or world resource.
            } catch (CompletionException | IllegalStateException failure) {
                failed = true;
                AstraEngine.LOGGER.error("Atmospheric optical bake failed; retaining bounded direct integration", failure);
            }
        }
        if (texture == 0 && pending == null && !failed) {
            double radius = radiusKm;
            var cancelled = new AtomicBoolean();
            pending = new Pending(cancelled, CompletableFuture.supplyAsync(
                    () -> EarthAtmosphereOptics.bake(radius, cancelled::get), Util.backgroundExecutor()));
        }
    }

    /** Binds readiness every frame. The shader uses a bounded direct-column fallback until the texture is ready. */
    void bind(ShaderInstance shader) {
        shader.safeGetUniform("EarthOpticsReady").set(texture == 0 ? 0 : 1);
        shader.safeGetUniform("EarthOpticsRadius").set((float) radiusKm);
        shader.setSampler("EarthOpticalColumns", texture);
    }

    private static int upload(EarthAtmosphereOptics optics) {
        int texture = 0;
        int previousBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] parameters = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS,
                GL11.GL_UNPACK_SKIP_PIXELS, GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_UNPACK_SKIP_IMAGES,
                GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST};
        int[] previous = new int[parameters.length];
        for (int i = 0; i < parameters.length; i++) { previous[i] = GL11.glGetInteger(parameters[i]); }
        FloatBuffer buffer = MemoryUtil.memAllocFloat(EarthAtmosphereOptics.WIDTH * EarthAtmosphereOptics.TEXTURE_HEIGHT * 4);
        try (FullscreenPass state = new FullscreenPass()) {
            optics.writeTo(buffer); buffer.flip();
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int parameter : parameters) { GL11.glPixelStorei(parameter, parameter == GL11.GL_UNPACK_ALIGNMENT ? 4 : 0); }
            texture = TextureUtil.generateTextureId();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0); RenderSystem.bindTexture(texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, EarthAtmosphereOptics.WIDTH,
                    EarthAtmosphereOptics.TEXTURE_HEIGHT, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
            if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != EarthAtmosphereOptics.WIDTH) {
                throw new IllegalStateException("Could not allocate atmospheric optical-depth texture");
            }
            int result = texture; texture = 0; return result;
        } finally {
            MemoryUtil.memFree(buffer);
            if (texture != 0) { TextureUtil.releaseTextureId(texture); }
            for (int i = 0; i < parameters.length; i++) { GL11.glPixelStorei(parameters[i], previous[i]); }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, previousBuffer);
        }
    }

    /** Cancels the immutable worker request and releases owned GPU storage on radius change/reload/logout. */
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (pending != null) { pending.cancelled.set(true); pending = null; }
        if (texture != 0) { TextureUtil.releaseTextureId(texture); texture = 0; }
        radiusKm = 0; failed = false;
    }

    private record Pending(AtomicBoolean cancelled, CompletableFuture<EarthAtmosphereOptics> future) { }
}
