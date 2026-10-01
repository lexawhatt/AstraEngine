package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.ContinentalMap;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
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
 * Render-thread-owned continental globe and four local height/climate tiles (about 24.1 MiB RGBA32F).
 * One cancellable immutable CPU bake runs on the host worker pool. A completed result is uploaded only
 * while still owned; movement retains the last valid data. No callback waits for an unfinished request.
 */
final class ContinentalSurfaceCache implements AutoCloseable {
    private ContinentalTerrain terrain;
    private static final int SIZE = 513;
    private static final double[] SPACING = {4, 32, 256, 1024};
    private int globe;
    private int[] tiles = new int[4];
    private SurfaceHeightTile.Grid grid;
    private Pending pending;
    private boolean failed;

    /** Null requests the global map only; otherwise the body-fixed observer also requests local detail. */
    void update(int terrainVersion, SpaceVector nearbyObserver) {
        RenderSystem.assertOnRenderThread();
        if (terrain == null || terrain.version() != terrainVersion) {
            close();
            terrain = new ContinentalTerrain(terrainVersion, ContinentalTerrain.SEED);
        }
        ContinentalTerrain source = terrain;
        if (nearbyObserver == null) { retireTiles(); }
        if (pending != null && pending.future().isDone()) {
            Pending complete = pending;
            pending = null;
            try {
                ContinentalMap[] maps = complete.future().join();
                if (!complete.cancelled().get()) {
                    if (complete.grid() == null) { installGlobe(maps[0]); }
                    else if (nearbyObserver != null && outer(complete.grid()).contains(nearbyObserver, .8)) {
                        installTiles(maps, complete.grid());
                    }
                }
            } catch (CancellationException exception) {
                // The discarded request owns no textures or world resources.
            } catch (CompletionException | IllegalStateException exception) {
                failed = true;
                AstraEngine.LOGGER.error("Continental surface bake failed; retaining the last valid map", exception);
            }
        }
        if (pending != null || failed) { return; }
        if (globe == 0) {
            AtomicBoolean cancelled = new AtomicBoolean();
            pending = new Pending(null, cancelled, CompletableFuture.supplyAsync(() ->
                    new ContinentalMap[] {ContinentalMap.globe(source, SIZE, cancelled::get)},
                    Util.backgroundExecutor()));
        } else if (nearbyObserver != null && (grid == null || !grid.contains(nearbyObserver, .45))) {
            var desired = SurfaceHeightTile.Grid.at(nearbyObserver, ContinentalTerrain.RADIUS_METERS, SIZE, SPACING[0]);
            AtomicBoolean cancelled = new AtomicBoolean();
            pending = new Pending(desired, cancelled, CompletableFuture.supplyAsync(() -> {
                ContinentalMap[] maps = new ContinentalMap[4];
                // Broad coverage is uploaded with the fine levels as one consistent basis.
                for (int i = 0; i < maps.length; i++) {
                    var mapping = new SurfaceHeightTile.Grid(desired.up(), desired.east(), desired.south(),
                            desired.radiusMeters(), SIZE, SPACING[i]);
                    maps[i] = ContinentalMap.tile(source, mapping, cancelled::get);
                }
                return maps;
            }, Util.backgroundExecutor()));
        }
    }

    /** Sets all readiness flags each frame; no unready texture is sampled by the shader. */
    void bind(ShaderInstance shader) {
        shader.safeGetUniform("ContinentalReady").set(globe == 0 ? 0 : 1);
        shader.safeGetUniform("ContinentalTilesReady").set(grid == null ? 0 : 1);
        shader.setSampler("ContinentalGlobe", globe);
        for (int i = 0; i < tiles.length; i++) { shader.setSampler("ContinentalTile" + i, tiles[i]); }
        if (grid == null) { return; }
        vector(shader, "ContinentalUp", grid.up());
        vector(shader, "ContinentalEast", grid.east());
        vector(shader, "ContinentalSouth", grid.south());
        shader.safeGetUniform("ContinentalSpacing").set((float) SPACING[0], (float) SPACING[1],
                (float) SPACING[2], (float) SPACING[3]);
    }

    private static SurfaceHeightTile.Grid outer(SurfaceHeightTile.Grid basis) {
        return new SurfaceHeightTile.Grid(basis.up(), basis.east(), basis.south(), basis.radiusMeters(), SIZE, SPACING[3]);
    }

    private void installGlobe(ContinentalMap map) {
        int replacement = upload(map);
        if (globe != 0) { TextureUtil.releaseTextureId(globe); }
        globe = replacement;
    }

    private void installTiles(ContinentalMap[] maps, SurfaceHeightTile.Grid replacementGrid) {
        int[] replacement = new int[4];
        try {
            for (int i = 0; i < maps.length; i++) { replacement[i] = upload(maps[i]); }
            int[] previous = tiles;
            tiles = replacement;
            replacement = previous;
            grid = replacementGrid;
        } finally {
            for (int texture : replacement) { if (texture != 0) { TextureUtil.releaseTextureId(texture); } }
        }
    }

    private static int upload(ContinentalMap map) {
        int texture = 0;
        FloatBuffer buffer = MemoryUtil.memAllocFloat(map.width() * map.height() * 4);
        try (FullscreenPass state = new FullscreenPass()) {
            map.writeTo(buffer); buffer.flip();
            texture = TextureUtil.generateTextureId();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, map.width(), map.height(), 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
            if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != map.width()) {
                throw new IllegalStateException("Could not allocate continental surface texture");
            }
            int result = texture;
            texture = 0;
            return result;
        } finally {
            MemoryUtil.memFree(buffer);
            if (texture != 0) { TextureUtil.releaseTextureId(texture); }
        }
    }

    private static void vector(ShaderInstance shader, String name, SpaceVector value) {
        shader.safeGetUniform(name).set((float) value.x(), (float) value.y(), (float) value.z());
    }

    private void retireTiles() {
        if (pending != null && pending.grid() != null) { pending.cancelled().set(true); pending = null; }
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] != 0) { TextureUtil.releaseTextureId(tiles[i]); tiles[i] = 0; }
        }
        grid = null;
    }

    /** Cancels pending work and releases owned GL resources on reload, logout or source retirement. */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        retireTiles();
        if (pending != null) { pending.cancelled().set(true); pending = null; }
        if (globe != 0) { TextureUtil.releaseTextureId(globe); globe = 0; }
        failed = false;
        terrain = null;
    }

    private record Pending(SurfaceHeightTile.Grid grid, AtomicBoolean cancelled,
            CompletableFuture<ContinentalMap[]> future) {}
}
