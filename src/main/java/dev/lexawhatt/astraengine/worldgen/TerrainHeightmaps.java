package dev.lexawhatt.astraengine.worldgen;

import java.util.Set;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/** Exact heightmap priming for tall generated terrain, skipping palettes that cannot satisfy a predicate. */
public final class TerrainHeightmaps {
    private TerrainHeightmaps() {}

    /**
     * Replaces the requested standard heightmaps from actual blocks, including prior feature/mod edits.
     * Requires exclusive ownership of the chunk by its host worldgen task; does not load neighboring chunks,
     * mutate blocks, schedule work or retain references. Empty columns use the host minimum build height.
     * Null chunks, sets or members are invalid. Other heightmaps remain unchanged.
     */
    public static void prime(ChunkAccess chunk, Set<Heightmap.Types> types) {
        if (chunk == null || types == null || types.stream().anyMatch(type -> type == null)) {
            throw new IllegalArgumentException("Heightmap priming requires a chunk and non-null map types");
        }
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();
        int bits = Mth.ceillog2(chunk.getHeight() + 1);
        for (Heightmap.Types type : types) {
            var predicate = type.isOpaque();
            var data = new SimpleBitStorage(bits, 256);
            boolean[] resolved = new boolean[256];
            int remaining = 256;
            for (int index = chunk.getSections().length - 1; index >= 0 && remaining > 0; index--) {
                var section = chunk.getSection(index);
                if (section.hasOnlyAir() || !section.maybeHas(predicate)) { continue; }
                int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
                int bottom = Math.max(baseY, minY);
                int top = Math.min(baseY + 16, maxY);
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int column = x + z * 16;
                        if (resolved[column]) { continue; }
                        for (int y = top - 1; y >= bottom; y--) {
                            if (predicate.test(section.getBlockState(x, y & 15, z))) {
                                data.set(column, y + 1 - minY);
                                resolved[column] = true;
                                remaining--;
                                break;
                            }
                        }
                    }
                }
            }
            chunk.setHeightmap(type, data.getRaw());
        }
    }
}
