package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.worldgen.TerrainHeightmaps;
import java.util.Arrays;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Tall host chunks verify real block predicates, standard packed maps and the measured generation bottleneck. */
@PrefixGameTestTemplate(false)
public final class TerrainHeightmapGameTests {
    private static final EnumSet<Heightmap.Types> TYPES = EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE);

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void tallHeightmapsMatchActualBlocksAndRetainHostFormat(GameTestHelper helper) {
        for (int fixture = 0; fixture < 5; fixture++) {
            ProtoChunk chunk = chunk(helper, fixture == 0 ? Blocks.AIR.defaultBlockState()
                    : fixture == 1 ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
            if (fixture >= 3) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int material = (x + z) % 4;
                        set(chunk, x, 1800 - x * 17 - z * 53, z, material == 0 ? Blocks.OAK_LEAVES.defaultBlockState()
                                : material == 1 ? Blocks.LAVA.defaultBlockState() : material == 2
                                ? Blocks.STONE.defaultBlockState() : Blocks.SNOW.defaultBlockState());
                        if (fixture == 4 && x == z) {
                            for (int y = EarthChart.MIN_Y; y < EarthChart.MIN_Y + EarthChart.HEIGHT; y++) {
                                set(chunk, x, y, z, Blocks.CAVE_AIR.defaultBlockState());
                            }
                        }
                    }
                }
            }
            TerrainHeightmaps.prime(chunk, TYPES);
            long[][] encoded = new long[TYPES.size()][];
            int map = 0;
            for (var type : TYPES) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int expected = chunk.getMinBuildHeight();
                        for (int y = chunk.getMaxBuildHeight() - 1; y >= chunk.getMinBuildHeight(); y--) {
                            if (type.isOpaque().test(chunk.getBlockState(new BlockPos(x, y, z)))) {
                                expected = y + 1; break;
                            }
                        }
                        helper.assertTrue(chunk.getHeight(type, x, z) + 1 == expected,
                                "Section prime disagrees with actual column: " + fixture + "/" + type + "/" + x + "/" + z);
                    }
                }
                encoded[map++] = chunk.getOrCreateHeightmapUnprimed(type).getRawData().clone();
            }
            Heightmap.primeHeightmaps(chunk, TYPES);
            map = 0;
            for (var type : TYPES) {
                helper.assertTrue(Arrays.equals(encoded[map++], chunk.getOrCreateHeightmapUnprimed(type).getRawData()),
                        "Standard packed heightmap differs from the host implementation");
            }
        }
        ProtoChunk water = chunk(helper, Blocks.WATER.defaultBlockState());
        for (int i = 0; i < 16; i++) { Heightmap.primeHeightmaps(water, TYPES); TerrainHeightmaps.prime(water, TYPES); }
        double[] host = new double[31], sections = new double[31];
        for (int i = 0; i < host.length; i++) {
            if (i % 2 == 0) { host[i] = measure(water, false); sections[i] = measure(water, true); }
            else { sections[i] = measure(water, true); host[i] = measure(water, false); }
        }
        AstraEngine.LOGGER.info("ASTRA_HEIGHTMAP_PRIME_RAW host_ms={} section_ms={}",
                Arrays.toString(host), Arrays.toString(sections));
        AstraEngine.LOGGER.info("ASTRA_HEIGHTMAP_PRIME_MEAN host_ms={} section_ms={}",
                Arrays.stream(host).average().orElseThrow(), Arrays.stream(sections).average().orElseThrow());
        Arrays.sort(host); Arrays.sort(sections);
        AstraEngine.LOGGER.info("ASTRA_HEIGHTMAP_PRIME samples=31 warmup=16 height={} host_ms_median={} host_ms_p95={} section_ms_median={} section_ms_p95={}",
                water.getHeight(), host[15], host[29], sections[15], sections[29]);
        helper.succeed();
    }

    private static double measure(ProtoChunk chunk, boolean sections) {
        long start = System.nanoTime();
        if (sections) { TerrainHeightmaps.prime(chunk, TYPES); }
        else { Heightmap.primeHeightmaps(chunk, TYPES); }
        return (System.nanoTime() - start) / 1e6;
    }

    private static ProtoChunk chunk(GameTestHelper helper, BlockState state) {
        var chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY,
                LevelHeightAccessor.create(EarthChart.MIN_Y, EarthChart.HEIGHT),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        for (int i = 0; i < chunk.getSections().length; i++) {
            var biomes = chunk.getSection(i).getBiomes();
            chunk.getSections()[i] = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                    state, PalettedContainer.Strategy.SECTION_STATES), biomes);
        }
        return chunk;
    }

    private static void set(ProtoChunk chunk, int x, int y, int z, BlockState state) {
        chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z, state);
    }
}
