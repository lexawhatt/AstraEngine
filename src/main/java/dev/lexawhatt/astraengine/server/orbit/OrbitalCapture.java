package dev.lexawhatt.astraengine.server.orbit;

import dev.lexawhatt.astraengine.surface.orbit.OrbitalPatch;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalSurface;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** Bounded 256-column capture from an already loaded chunk; no access calls can request neighboring chunks. */
public final class OrbitalCapture {
    private OrbitalCapture() { }

    /** Reads actual top material and exposed emitters only, never skylight or fabricated settlement lights. */
    public static OrbitalPatch capture(ServerLevel level, OrbitalSurface surface, LevelChunk chunk, long revision) {
        if (level == null || surface == null || chunk == null || chunk.getLevel() != level
                || !level.getServer().isSameThread()) {
            throw new IllegalArgumentException("Orbital capture requires an owned loaded chunk on its server thread");
        }
        var cells = new ArrayList<OrbitalPatch.Cell>(16);
        var position = new BlockPos.MutableBlockPos();
        for (int cellZ = 0; cellZ < 4; cellZ++) {
            for (int cellX = 0; cellX < 4; cellX++) {
                double height = 0, red = 0, green = 0, blue = 0, emission = 0; int visible = 0;
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int x = chunk.getPos().getMinBlockX() + cellX * 4 + dx;
                        int z = chunk.getPos().getMinBlockZ() + cellZ * 4 + dz;
                        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                        // The upper storage boundary is not an exposed surface: canonical terrain may continue in
                        // the next altitude band. Do not turn that clipped column into an orbital plateau or light.
                        if (top < level.getMinBuildHeight() || top >= level.getMaxBuildHeight() - 1) { continue; }
                        position.set(x, top, z);
                        int light = 0; int rgb = 0; boolean material = false;
                        for (int y = top; y >= Math.max(level.getMinBuildHeight(), top - 8); y--) {
                            position.setY(y); var state = chunk.getBlockState(position);
                            light = Math.max(light, state.getLightEmission(chunk, position));
                            MapColor color = state.getMapColor(chunk, position);
                            if (!material && color != MapColor.NONE) {
                                rgb = color.col;
                                var biome = chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2).value();
                                if (state.is(Blocks.GRASS_BLOCK)) { rgb = biome.getGrassColor(x, z); }
                                else if (state.is(BlockTags.LEAVES)) { rgb = biome.getFoliageColor(); }
                                material = true;
                            }
                            if (state.canOcclude() || !state.getFluidState().isEmpty()) { break; }
                        }
                        if (!material) { continue; }
                        visible++; height += top + 1.0 + surface.altitudeOriginMeters();
                        red += (rgb >>> 16) & 255; green += (rgb >>> 8) & 255; blue += rgb & 255;
                        emission += light / 15.0;
                    }
                }
                cells.add(visible == 0 ? OrbitalPatch.Cell.EMPTY : new OrbitalPatch.Cell((float) (height / visible),
                        (int) Math.round(red / visible) << 16 | (int) Math.round(green / visible) << 8
                                | (int) Math.round(blue / visible),
                        (float) (emission / visible), visible / 16.0f));
            }
        }
        return new OrbitalPatch(surface, chunk.getPos().x, chunk.getPos().z, 0, revision, cells);
    }
}
