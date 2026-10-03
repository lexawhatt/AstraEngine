package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.surface.EarthBoundarySection;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import dev.lexawhatt.astraengine.server.interaction.BoundaryMenus;

/** Server-thread copies of already loaded canonical blocks. No tickets, chunk generation, mutation or caching. */
public final class EarthBoundaryCapture {
    private EarthBoundaryCapture() {}

    /**
     * Copies one complete section, including real edits and current host light/biomes. Absence means its chunk
     * or completed lighting is unavailable. Null, foreign charts or invalid vertical sections throw. Returned
     * numeric data retains no world/chunk references and may be handed to the connection or a mesh worker.
     */
    public static Optional<EarthBoundarySection> capture(ServerLevel level, CubeStorageChart chart, SectionPos position) {
        if (level == null || chart == null || position == null
                || !dev.lexawhatt.astraengine.api.AstraGeography.planetaryReference(level).map(chart::equals).orElse(false)
                || position.y() < level.getMinSection() || position.y() >= level.getMaxSection()) {
            throw new IllegalArgumentException("Boundary capture requires an owned Earth chart and valid section");
        }
        var chunk = level.getChunkSource().getChunkNow(position.x(), position.z());
        if (chunk == null || !chunk.isLightCorrect()) { return Optional.empty(); }
        var section = chunk.getSection(level.getSectionIndexFromSectionY(position.y()));
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        int[] states = new int[EarthBoundarySection.CELL_COUNT]; byte[] light = new byte[states.length];
        int[] biomes = new int[EarthBoundarySection.BIOME_COUNT];
        long[] openContainers = new long[EarthBoundarySection.VISUAL_WORD_COUNT];
        var block = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int at = (y * 16 + z) * 16 + x;
                    states[at] = Block.getId(section.getBlockState(x, y, z));
                    block.set(position.minBlockX() + x, position.minBlockY() + y, position.minBlockZ() + z);
                    light[at] = (byte) (level.getBrightness(LightLayer.SKY, block) << 4
                            | level.getBrightness(LightLayer.BLOCK, block));
                    var state = section.getBlockState(x, y, z);
                    if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.ENDER_CHEST)) {
                        var entity = chunk.getBlockEntities().get(block);
                        boolean opened = ChestBlockEntity.getOpenCount(level, block) > 0;
                        if (!opened && entity != null) {
                            for (var player : level.getServer().getPlayerList().getPlayers()) {
                                if (BoundaryMenus.observes(player, entity)) { opened = true; break; }
                            }
                        }
                        if (opened) { openContainers[at / Long.SIZE] |= 1L << (at % Long.SIZE); }
                    }
                }
            }
        }
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    biomes[(y * 4 + z) * 4 + x] = biomeRegistry.getId(section.getBiomes().get(x, y, z).value());
                }
            }
        }
        return Optional.of(new EarthBoundarySection(chart, position, states, light, biomes, openContainers));
    }
}
