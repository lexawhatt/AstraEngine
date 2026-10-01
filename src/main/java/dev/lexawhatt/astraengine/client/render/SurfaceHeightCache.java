package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.SurfaceGeography;
import dev.lexawhatt.astraengine.surface.SurfaceHeightTile;
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
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * Render-thread-owned Earth height cache. Three 513-square RGBA32F tiles consume about 12.1 MiB on the GPU.
 * At most one immutable bake request runs on Minecraft's worker pool. Completed data is uploaded only by
 * its still-owning request; close/reload cancel its token. No render callback waits for an unfinished task.
 */
final class SurfaceHeightCache implements AutoCloseable {
    private static final int SIZE = 513;
    private static final double[] SPACING = {4, 32, 256};
    private int[] textures = new int[3];
    private SurfaceHeightTile.Grid grid;
    private SurfaceGeography source;
    private double sourceRadius;
    private Pending pending;
    private boolean failed;

    /** Prepares a nearby body-fixed observation, retaining a previous valid tile while the next one bakes. */
    void update(SpaceVector observer, double radiusMeters, SurfaceGeography geography) {
        RenderSystem.assertOnRenderThread();
        if (observer == null || geography == null || geography.kind() != SurfaceGeography.Kind.EARTH) {
            throw new IllegalArgumentException("Earth cache requires a finite observer and Earth geography");
        }
        if (!geography.equals(source) || sourceRadius != radiusMeters) {
            close();
            source = geography;
            sourceRadius = radiusMeters;
        }
        if (pending != null && pending.future().isDone()) {
            Pending complete = pending;
            pending = null;
            try {
                SurfaceHeightTile[] tiles = complete.future().join();
                if (!complete.cancelled().get() && tiles[2].grid().contains(observer, 0.8)) {
                    install(tiles);
                }
            } catch (CancellationException exception) {
                // A retired request has no result to apply and owns no GL resources.
            } catch (CompletionException | IllegalStateException exception) {
                failed = true;
                AstraEngine.LOGGER.error("Earth height cache failed; retaining analytic terrain rendering", exception);
            }
        }
        if (pending == null && !failed && (grid == null || !grid.contains(observer, 0.45))) {
            AtomicBoolean cancelled = new AtomicBoolean();
            var desired = SurfaceHeightTile.Grid.at(observer, radiusMeters, SIZE, SPACING[0]);
            CompletableFuture<SurfaceHeightTile[]> future = CompletableFuture.supplyAsync(() -> {
                SurfaceHeightTile[] tiles = new SurfaceHeightTile[3];
                for (int i = 0; i < tiles.length; i++) {
                    var mapping = new SurfaceHeightTile.Grid(desired.up(), desired.east(), desired.south(),
                            radiusMeters, SIZE, SPACING[i]);
                    tiles[i] = SurfaceHeightTile.bake(mapping, geography, cancelled::get);
                }
                return tiles;
            }, Util.backgroundExecutor());
            pending = new Pending(cancelled, future);
        }
    }

    /** Binds metadata and sampler IDs for the owned Cosmos shader. Uniforms are reset even before first bake. */
    void bind(ShaderInstance shader) {
        shader.safeGetUniform("EarthHeightCacheEnabled").set(grid == null ? 0 : 1);
        for (int i = 0; i < textures.length; i++) { shader.setSampler("EarthHeightTile" + i, textures[i]); }
        if (grid == null) { return; }
        vector(shader, "EarthHeightUp", grid.up());
        vector(shader, "EarthHeightEast", grid.east());
        vector(shader, "EarthHeightSouth", grid.south());
        shader.safeGetUniform("EarthHeightGrid").set((float) grid.radiusMeters(), (float) SIZE,
                (float) SPACING[0], (float) SPACING[1]);
        shader.safeGetUniform("EarthHeightOuterSpacing").set((float) SPACING[2]);
    }

    private void install(SurfaceHeightTile[] tiles) {
        int[] replacement = new int[3];
        FloatBuffer upload = MemoryUtil.memAllocFloat(SIZE * SIZE * 4);
        try (FullscreenPass state = new FullscreenPass()) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            for (int i = 0; i < tiles.length; i++) {
                upload.clear(); tiles[i].writeTo(upload); upload.flip();
                replacement[i] = TextureUtil.generateTextureId();
                RenderSystem.bindTexture(replacement[i]);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, SIZE, SIZE, 0,
                        GL11.GL_RGBA, GL11.GL_FLOAT, upload);
                if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != SIZE) {
                    throw new IllegalStateException("Could not allocate Earth height texture");
                }
            }
            int[] previous = textures;
            textures = replacement;
            replacement = previous;
            grid = tiles[0].grid();
        } finally {
            MemoryUtil.memFree(upload);
            for (int texture : replacement) { if (texture != 0) { TextureUtil.releaseTextureId(texture); } }
        }
    }

    private static void vector(ShaderInstance shader, String name, SpaceVector value) {
        shader.safeGetUniform(name).set((float) value.x(), (float) value.y(), (float) value.z());
    }

    /** Cancels pending computation and releases only owned textures. Safe to repeat and reopen next session. */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (pending != null) {
            pending.cancelled().set(true);
            pending = null;
        }
        for (int i = 0; i < textures.length; i++) {
            if (textures[i] != 0) { TextureUtil.releaseTextureId(textures[i]); textures[i] = 0; }
        }
        source = null;
        sourceRadius = 0;
        grid = null;
        failed = false;
    }

    private record Pending(AtomicBoolean cancelled, CompletableFuture<SurfaceHeightTile[]> future) {}
}
