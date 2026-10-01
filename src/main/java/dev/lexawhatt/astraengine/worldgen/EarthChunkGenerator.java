package dev.lexawhatt.astraengine.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.EarthClimate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Permanent chart/band storage for the shared continental Earth. Pure immutable sampling drives generation;
 * workers mutate only the chunk supplied by Minecraft. Saved chunks and edits remain host-owned. Physical
 * elevations are never clamped to this band's storage interval before materials are chosen. Biome decoration
 * remains inherited and extensible through ordinary registry features and NeoForge biome modifiers.
 */
public final class EarthChunkGenerator extends ChunkGenerator {
    private static final MapCodec<Definition> DEFINITION_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TerrainCodecs.EXACT_INT.fieldOf("chart_version").forGetter(Definition::chartVersion),
            TerrainCodecs.EXACT_INT.fieldOf("terrain_version").forGetter(Definition::terrainVersion),
            TerrainCodecs.EXACT_LONG.fieldOf("seed").forGetter(Definition::seed),
            Codec.STRING.fieldOf("face").forGetter(Definition::face),
            TerrainCodecs.EXACT_INT.fieldOf("band").forGetter(Definition::band),
            EarthBiomeSource.CODEC.fieldOf("biome_source").forGetter(Definition::biomes)
    ).apply(instance, Definition::new));

    /** Strict versioned saved identity; rejects unsupported geography instead of regenerating with new defaults. */
    public static final MapCodec<EarthChunkGenerator> CODEC = DEFINITION_CODEC.flatXmap(definition -> {
        try {
            return DataResult.success(new EarthChunkGenerator(definition));
        } catch (IllegalArgumentException exception) {
            return DataResult.error(exception::getMessage);
        }
    }, generator -> DataResult.success(generator.definition));

    private final Definition definition;
    private final EarthChart chart;
    private final ContinentalTerrain terrain;
    private final TerrainColumns columns;

    /** Requires a chart and biome source for the same face; neither retains a level or mutable worker cache. */
    public EarthChunkGenerator(EarthChart chart, EarthBiomeSource biomes) {
        this(definition(chart, biomes));
    }

    private EarthChunkGenerator(Definition definition) {
        super(definition.biomes());
        chart = new EarthChart(CubeFace.fromId(definition.face()), definition.band(), definition.terrainVersion());
        if (definition.chartVersion() != EarthChart.VERSION || definition.terrainVersion() != definition.biomes().terrainVersion()
                || definition.seed() != ContinentalTerrain.SEED || definition.biomes().face() != chart.face()) {
            throw new IllegalArgumentException("Earth generator does not match its pinned terrain and chart identity");
        }
        this.definition = definition;
        terrain = new ContinentalTerrain(definition.terrainVersion(), definition.seed());
        columns = new TerrainColumns(EarthChart.MIN_Y, EarthChart.HEIGHT, this::column);
    }

    /** Immutable persistent chart identity. */
    public EarthChart chart() { return chart; }
    /** Immutable spherical field shared by every storage chart and presentation sampler. */
    public ContinentalTerrain terrain() { return terrain; }
    /**
     * Worker-safe unmodified base column, including lit-air space but no decoration or player edits.
     * Uses the exact generation sampler and band clipping, without loading chunks or allocating a voxel array.
     */
    public List<TerrainLayer> terrainLayers(int x, int z) { return columns.layers(x, z); }
    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structures, RandomState random, long seed) {
        return ChunkGeneratorStructureState.createForFlat(random, seed, biomeSource, Stream.empty());
    }

    /** Column climate is height-independent: compute 16 quart samples once, then fill all owned section palettes. */
    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState random, Blender blender,
            StructureManager structures, ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("astra_earth_biomes", () -> {
            int startX = chunk.getPos().x * 4, startZ = chunk.getPos().z * 4;
            List<Holder<Biome>> biomes = new ArrayList<>(16);
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    biomes.add(biomeSource.getNoiseBiome(startX + x, 0, startZ + z, random.sampler()));
                }
            }
            chunk.fillBiomesFromNoise((x, y, z, sampler) -> biomes.get(x - startX + (z - startZ) * 4), random.sampler());
            return chunk;
        }), Util.backgroundExecutor());
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random,
            StructureManager structures, ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("astra_earth_terrain",
                () -> columns.fill(chunk)), Util.backgroundExecutor());
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        return columns.getBaseHeight(x, z, type, level, random);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        return columns.getBaseColumn(x, z, level, random);
    }

    private TerrainColumns.Column column(int x, int z) {
        if (Math.abs(x + .5) > EarthChart.RADIUS_METERS || Math.abs(z + .5) > EarthChart.RADIUS_METERS) { return null; }
        var sample = terrain.sample(chart.normal(x + .5, z + .5));
        int firstAir = (int) Math.floor(sample.heightMeters()) - chart.altitudeOriginMeters();
        BlockState surface;
        BlockState subsurface;
        switch (EarthClimate.at(sample)) {
            case DEEP_OCEAN, OCEAN, FROZEN_OCEAN -> { surface = Blocks.GRAVEL.defaultBlockState(); subsurface = Blocks.STONE.defaultBlockState(); }
            case BEACH, DESERT -> { surface = Blocks.SAND.defaultBlockState(); subsurface = Blocks.SANDSTONE.defaultBlockState(); }
            case SNOW -> { surface = Blocks.SNOW_BLOCK.defaultBlockState(); subsurface = Blocks.STONE.defaultBlockState(); }
            case ALPINE -> { surface = Blocks.STONE.defaultBlockState(); subsurface = surface; }
            default -> { surface = Blocks.GRASS_BLOCK.defaultBlockState(); subsurface = Blocks.DIRT.defaultBlockState(); }
        }
        return new TerrainColumns.Column(firstAir, Math.max(firstAir, getSeaLevel()), surface, subsurface);
    }

    @Override public int getMinY() { return EarthChart.MIN_Y; }
    @Override public int getGenDepth() { return EarthChart.HEIGHT; }
    @Override public int getSeaLevel() { return -chart.altitudeOriginMeters(); }
    @Override public int getSpawnHeight(LevelHeightAccessor level) {
        return Math.clamp(column(0, 0).top(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
    }
    @Override public void buildSurface(WorldGenRegion level, StructureManager structures, RandomState random, ChunkAccess chunk) {}
    @Override public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomes,
            StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {}
    @Override public void spawnOriginalMobs(WorldGenRegion level) {}

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos position) {
        info.add(String.format(Locale.ROOT, "Astra Earth: chart v%d %s/%d, altitude %.1f m",
                EarthChart.VERSION, chart.face().id(), chart.band(), position.getY() + (double) chart.altitudeOriginMeters()));
    }

    private static Definition definition(EarthChart chart, EarthBiomeSource biomes) {
        if (chart == null || biomes == null) { throw new IllegalArgumentException("Earth generation requires a chart and biomes"); }
        return new Definition(EarthChart.VERSION, chart.terrainVersion(), ContinentalTerrain.SEED,
                chart.face().id(), chart.band(), biomes);
    }

    private record Definition(int chartVersion, int terrainVersion, long seed, String face, int band, EarthBiomeSource biomes) {}
}
