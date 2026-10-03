package dev.lexawhatt.astraengine.worldgen;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Worker-safe ownership guard derived only from the immutable generator, without save or level lookups. */
public final class CanonicalBlockOwnership {
    private CanonicalBlockOwnership() { }

    /** Unbound host worlds retain vanilla behavior. Bound charts have exactly one writable owner per cell. */
    public static boolean permits(ChunkGenerator generator, BlockPos position) {
        CubeStorageChart chart = generator instanceof EarthChunkGenerator earth ? earth.chart()
                : generator instanceof PlanetChunkGenerator planet ? planet.chart() : null;
        return chart == null || chart.contains(new SpaceVector(position.getX() + .5,
                position.getY() + .5, position.getZ() + .5));
    }
}
