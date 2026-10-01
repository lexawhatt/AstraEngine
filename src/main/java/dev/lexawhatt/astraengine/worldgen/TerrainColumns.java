package dev.lexawhatt.astraengine.worldgen;

import java.util.Arrays;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/** Shared worker-local column fill for permanent continental windows and whole-Earth charts. */
final class TerrainColumns {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private final int minYBound;
    private final int height;
    private final Sampler sampler;

    TerrainColumns(int minYBound, int height, Sampler sampler) {
        this.minYBound = minYBound;
        this.height = height;
        this.sampler = sampler;
    }

    interface Sampler { Column sample(int x, int z); }
    record Column(int firstAir, int top, BlockState surface, BlockState subsurface) {}
    private Column column(int x, int z) { return sampler.sample(x, z); }

    ChunkAccess fill(ChunkAccess chunk) {
        Column[] columns = new Column[256];
        int minY = Math.max(minYBound, chunk.getMinBuildHeight());
        int maxY = Math.min(minYBound + height, chunk.getMaxBuildHeight());
        int highest = minY;
        int solidStoneCeiling = maxY;
        int waterFloor = minY;
        int waterCeiling = maxY;
        boolean allColumnsPresent = true;
        int originX = chunk.getPos().getMinBlockX();
        int originZ = chunk.getPos().getMinBlockZ();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                Column column = column(originX + x, originZ + z);
                columns[x + z * 16] = column;
                if (column == null) {
                    allColumnsPresent = false;
                } else {
                    highest = Math.max(highest, Math.min(maxY, column.top()));
                    solidStoneCeiling = Math.min(solidStoneCeiling, column.firstAir() - 5);
                    waterFloor = Math.max(waterFloor, column.firstAir());
                    waterCeiling = Math.min(waterCeiling, column.top());
                }
            }
        }

        for (int sectionY = SectionPos.blockToSectionCoord(minY);
                highest > minY && sectionY <= SectionPos.blockToSectionCoord(highest - 1); sectionY++) {
            int baseY = SectionPos.sectionToBlockCoord(sectionY);
            int index = chunk.getSectionIndexFromSectionY(sectionY);
            LevelChunkSection section = chunk.getSection(index);
            BlockState uniform = null;
            if (allColumnsPresent && baseY >= minY && baseY + 16 <= maxY) {
                if (baseY + 16 <= solidStoneCeiling) { uniform = STONE; }
                else if (baseY >= waterFloor && baseY + 16 <= waterCeiling) { uniform = WATER; }
            }
            if (uniform != null) {
                // BIOMES has completed. Preserve its palette and derive the correct block/fluid counts in
                // the new section. Deep ocean windows benefit from the same batching as solid rock.
                chunk.getSections()[index] = new LevelChunkSection(new PalettedContainer<>(
                        Block.BLOCK_STATE_REGISTRY, uniform, PalettedContainer.Strategy.SECTION_STATES), section.getBiomes());
                continue;
            }
            section.acquire();
            try {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Column column = columns[x + z * 16];
                        if (column == null) { continue; }
                        int top = Math.min(Math.min(baseY + 16, maxY), column.top());
                        for (int y = Math.max(baseY, minY); y < top; y++) {
                            section.setBlockState(x, y & 15, z, block(column, y), false);
                        }
                    }
                }
            } finally {
                section.release();
            }
        }

        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                Column column = columns[x + z * 16];
                if (column == null) { continue; }
                int solid = Math.min(maxY, column.firstAir()) - 1;
                int top = Math.min(maxY, column.top()) - 1;
                if (solid >= minY) { oceanFloor.update(x, solid, z, block(column, solid)); }
                if (top >= minY) { worldSurface.update(x, top, z, block(column, top)); }
            }
        }
        return chunk;
    }

    /** Matches generated heightmap predicates, including clipped, wholly submerged and off-patch columns. */
    int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        Column column = column(x, z);
        if (column == null) { return level.getMinBuildHeight(); }
        int minY = Math.max(minYBound, level.getMinBuildHeight());
        int top = Math.min(Math.min(level.getMaxBuildHeight(), minYBound + height), column.top());
        for (int y = top - 1; y >= minY; y--) {
            if (type.isOpaque().test(block(column, y))) { return y + 1; }
        }
        return level.getMinBuildHeight();
    }

    /** Returns the generated, vertically clipped materials; the caller owns the returned NoiseColumn. */
    NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        BlockState[] states = new BlockState[level.getHeight()];
        Arrays.fill(states, AIR);
        Column column = column(x, z);
        if (column != null) {
            int minY = Math.max(minYBound, level.getMinBuildHeight());
            int top = Math.min(Math.min(level.getMaxBuildHeight(), minYBound + height), column.top());
            for (int y = minY; y < top; y++) { states[y - level.getMinBuildHeight()] = block(column, y); }
        }
        return new NoiseColumn(level.getMinBuildHeight(), states);
    }

    private BlockState block(Column column, int y) {
        if (y < minYBound || y >= minYBound + height) { return AIR; }
        if (y >= column.firstAir()) { return y < column.top() ? WATER : AIR; }
        int depth = column.firstAir() - 1 - y;
        return depth == 0 ? column.surface() : depth < 5 ? column.subsurface() : STONE;
    }

}
