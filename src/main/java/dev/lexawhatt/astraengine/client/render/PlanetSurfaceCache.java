package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.SolidPlanetMap;
import dev.lexawhatt.astraengine.surface.SolidPlanetPalette;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import dev.lexawhatt.astraengine.surface.SolidPlanetTerrain;
import dev.lexawhatt.astraengine.surface.SurfaceHeightTile;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * Render-thread owner of 12 bounded globe atlas slots and 3 nearby relief tiles. At most one immutable CPU
 * bake runs on Minecraft's worker pool; stale profile/request results are rejected. Reload/logout releases
 * every owned texture and retires work. Missing data retains the existing analytic body material.
 */
final class PlanetSurfaceCache implements AutoCloseable {
    private static final int GLOBE_HEIGHT = 257;
    private static final int GLOBE_WIDTH = GLOBE_HEIGHT * 2 - 1;
    private static final int TILE_SIZE = 513;
    private static final double[] SPACING = {8, 128, 1024};
    private final Map<SolidPlanetProfile, Slot> slots = new HashMap<>();
    private int atlas;
    private int tileAtlas;
    private SolidPlanetPalette palette;
    private SolidPlanetProfile focus;
    private SurfaceHeightTile.Grid grid;
    private Pending pending;
    private boolean failed;

    void update(List<SolidPlanetProfile> visible, SolidPlanetProfile nearest, SpaceVector observer) {
        RenderSystem.assertOnRenderThread();
        var wanted = new HashSet<>(visible); wanted.remove(null);
        slots.keySet().removeIf(profile -> !wanted.contains(profile));
        if (wanted.isEmpty()) { close(); return; }
        if (palette == null) { palette = EarthSurfaceMaterials.capturePlanet(); }
        if (!java.util.Objects.equals(focus, nearest)) { retireTiles(); focus = nearest; }
        if (pending != null && pending.future().isDone()) {
            var complete = pending; pending = null;
            try {
                var maps = complete.future().join();
                if (!complete.cancelled().get() && wanted.contains(complete.profile())) {
                    if (complete.grid() == null) {
                        var slot = slots.get(complete.profile());
                        if (slot != null) { installGlobe(slot.index(), maps[0]); slots.put(complete.profile(), new Slot(slot.index(), true)); }
                    } else if (complete.profile().equals(focus) && observer != null && outer(complete.grid()).contains(observer, .8)) {
                        installTiles(maps, complete.grid());
                    }
                }
            } catch (CancellationException ignored) {
                // Retired numeric work owns no GL resources.
            } catch (CompletionException | IllegalStateException exception) {
                failed = true; AstraEngine.LOGGER.error("Planet surface map failed; retaining valid body materials", exception);
            }
        }
        if (pending != null || failed) { return; }
        for (var profile : wanted) {
            if (!slots.containsKey(profile)) {
                int slot = 0;
                while (used(slot)) { slot++; }
                if (slot >= 12) { throw new IllegalArgumentException("Planet atlas exceeded visible body budget"); }
                slots.put(profile, new Slot(slot, false));
            }
        }
        // Closest body is prepared first; its coarse and detailed maps remain one saved realization.
        SolidPlanetProfile missing = nearest != null && slots.containsKey(nearest) && !slots.get(nearest).ready()
                ? nearest : visible.stream().filter(profile -> profile != null && !slots.get(profile).ready()).findFirst().orElse(null);
        var appearance = palette;
        if (missing != null) {
            var terrain = new SolidPlanetTerrain(missing); var cancelled = new AtomicBoolean();
            pending = new Pending(missing, null, cancelled, CompletableFuture.supplyAsync(() ->
                    new SolidPlanetMap[] {SolidPlanetMap.globe(terrain, GLOBE_HEIGHT, appearance, cancelled::get)}, Util.backgroundExecutor()));
        } else if (focus != null && observer != null && (grid == null || !grid.contains(observer, .45))) {
            var desired = SurfaceHeightTile.Grid.at(observer, focus.radiusMeters(), TILE_SIZE, spacing(focus.radiusMeters(), 0));
            var terrain = new SolidPlanetTerrain(focus); var cancelled = new AtomicBoolean();
            pending = new Pending(focus, desired, cancelled, CompletableFuture.supplyAsync(() -> {
                var maps = new SolidPlanetMap[3];
                for (int index = 0; index < maps.length; index++) {
                    var mapping = new SurfaceHeightTile.Grid(desired.up(), desired.east(), desired.south(),
                            desired.radiusMeters(), TILE_SIZE, spacing(desired.radiusMeters(), index));
                    maps[index] = SolidPlanetMap.tile(terrain, mapping, appearance, cancelled::get);
                }
                return maps;
            }, Util.backgroundExecutor()));
        }
    }

    void bind(ShaderInstance shader, List<SolidPlanetProfile> visible) {
        shader.setSampler("PlanetAtlas", atlas);
        int focusIndex = -1;
        for (int index = 0; index < 12; index++) {
            var profile = index < visible.size() ? visible.get(index) : null;
            var slot = slots.get(profile);
            shader.safeGetUniform("PlanetSlots[" + index + "]").set(slot == null || !slot.ready() ? -1 : slot.index());
            if (profile != null && profile.equals(focus)) { focusIndex = index; }
        }
        shader.safeGetUniform("PlanetDetailIndex").set(grid == null ? -1 : focusIndex);
        shader.setSampler("PlanetTiles", tileAtlas);
        if (grid != null) {
            vector(shader, "PlanetUp", grid.up()); vector(shader, "PlanetEast", grid.east()); vector(shader, "PlanetSouth", grid.south());
            shader.safeGetUniform("PlanetSpacing").set((float) spacing(grid.radiusMeters(), 0),
                    (float) spacing(grid.radiusMeters(), 1), (float) spacing(grid.radiusMeters(), 2));
        }
    }

    private static double spacing(double radius, int index) {
        return SPACING[index] * Math.min(1, radius * .095 / ((TILE_SIZE - 1) * SPACING[2]));
    }
    private boolean used(int slot) { return slots.values().stream().anyMatch(value -> value.index() == slot); }
    private static SurfaceHeightTile.Grid outer(SurfaceHeightTile.Grid grid) {
        return new SurfaceHeightTile.Grid(grid.up(), grid.east(), grid.south(), grid.radiusMeters(), TILE_SIZE, spacing(grid.radiusMeters(), 2));
    }
    private void installGlobe(int slot, SolidPlanetMap map) {
        try (var state = new FullscreenPass()) {
            if (atlas == 0) {
                atlas = allocate(GLOBE_WIDTH * 4, GLOBE_HEIGHT * 3, null);
            }
            var buffer = MemoryUtil.memAllocFloat(map.width() * map.height() * 4);
            try {
                map.writeTo(buffer); buffer.flip(); RenderSystem.activeTexture(GL13.GL_TEXTURE0); RenderSystem.bindTexture(atlas);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, slot % 4 * GLOBE_WIDTH, slot / 4 * GLOBE_HEIGHT,
                        map.width(), map.height(), GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
            } finally { MemoryUtil.memFree(buffer); }
        }
    }
    private void installTiles(SolidPlanetMap[] maps, SurfaceHeightTile.Grid nextGrid) {
        int next = 0;
        try (var state = new FullscreenPass()) {
            next = allocate(TILE_SIZE * 3, TILE_SIZE, null);
            for (int index = 0; index < maps.length; index++) {
                var map = maps[index]; var buffer = MemoryUtil.memAllocFloat(map.width() * map.height() * 4);
                try {
                    map.writeTo(buffer); buffer.flip();
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, index * TILE_SIZE, 0, map.width(), map.height(),
                            GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
                } finally { MemoryUtil.memFree(buffer); }
            }
            int old = tileAtlas; tileAtlas = next; next = old; grid = nextGrid;
        } finally { if (next != 0) { TextureUtil.releaseTextureId(next); } }
    }
    private static int allocate(int width, int height, FloatBuffer values) {
        int texture = TextureUtil.generateTextureId();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0); RenderSystem.bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, width, height, 0, GL11.GL_RGBA, GL11.GL_FLOAT, values);
        if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != width) {
            TextureUtil.releaseTextureId(texture); throw new IllegalStateException("Could not allocate planetary height/color atlas");
        }
        return texture;
    }
    private static void vector(ShaderInstance shader, String name, SpaceVector value) {
        shader.safeGetUniform(name).set((float) value.x(), (float) value.y(), (float) value.z());
    }
    private void retireTiles() {
        if (pending != null && pending.grid() != null) { pending.cancelled().set(true); pending = null; }
        if (tileAtlas != 0) { TextureUtil.releaseTextureId(tileAtlas); tileAtlas = 0; }
        grid = null;
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread(); retireTiles();
        if (pending != null) { pending.cancelled().set(true); pending = null; }
        if (atlas != 0) { TextureUtil.releaseTextureId(atlas); atlas = 0; }
        slots.clear(); palette = null; focus = null; failed = false;
    }
    private record Slot(int index, boolean ready) { }
    private record Pending(SolidPlanetProfile profile, SurfaceHeightTile.Grid grid, AtomicBoolean cancelled,
                           CompletableFuture<SolidPlanetMap[]> future) { }
}
