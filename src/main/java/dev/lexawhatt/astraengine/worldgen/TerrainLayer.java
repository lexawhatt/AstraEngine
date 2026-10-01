package dev.lexawhatt.astraengine.worldgen;

import net.minecraft.world.level.block.state.BlockState;

/** Immutable half-open host-Y interval for an unmodified terrain column; no level or chunk is retained. */
public record TerrainLayer(int bottomY, int topY, BlockState state) {
    public TerrainLayer {
        if (bottomY >= topY || state == null) {
            throw new IllegalArgumentException("A terrain layer requires a nonempty interval and block state");
        }
    }
}
