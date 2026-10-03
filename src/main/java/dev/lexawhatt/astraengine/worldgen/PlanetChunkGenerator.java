package dev.lexawhatt.astraengine.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import dev.lexawhatt.astraengine.surface.SolidPlanetTerrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
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
 * Whole-body chart generator using the same immutable terrain sampler as direct LODs and orbital summaries.
 * Host chunks remain canonical and existing chunks are never regenerated. Registry biome features remain
 * extensible through Minecraft's inherited decoration hook; this class does not add consumer ore policy.
 */
public final class PlanetChunkGenerator extends ChunkGenerator {
    private static final MapCodec<Definition> DEFINITION = RecordCodecBuilder.mapCodec(instance -> instance.group(
            PlanetProfileCodec.CODEC.fieldOf("profile").forGetter(Definition::profile),
            Codec.STRING.fieldOf("face").forGetter(Definition::face),
            TerrainCodecs.EXACT_INT.fieldOf("band").forGetter(Definition::band),
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(Definition::biomes)
    ).apply(instance, Definition::new));
    public static final MapCodec<PlanetChunkGenerator> CODEC = DEFINITION.flatXmap(value -> {
        try { return DataResult.success(new PlanetChunkGenerator(new PlanetChart(value.profile(),
                CubeFace.fromId(value.face()), value.band()), value.biomes())); }
        catch (IllegalArgumentException exception) { return DataResult.error(exception::getMessage); }
    }, value -> DataResult.success(new Definition(value.chart.profile(), value.chart.face().id(), value.chart.band(), value.biomeSource)));

    private final PlanetChart chart;
    private final SolidPlanetTerrain terrain;
    private final TerrainColumns columns;
    private final int terrainMinY;
    private final boolean upperAir;

    /** No world references are retained. The biome source is a host registry value, not mutable geographic state. */
    public PlanetChunkGenerator(PlanetChart chart, BiomeSource biomes) {
        super(biomes);
        if (chart == null || biomes == null) { throw new IllegalArgumentException("Planet generation requires a chart and biomes"); }
        this.chart = chart;
        terrain = new SolidPlanetTerrain(chart.profile());
        upperAir = chart.altitudeOriginMeters() + chart.minY() >= Math.ceil(terrain.maximumHeightMeters());
        terrainMinY = Math.max(chart.minY(), chart.coreFloorY() + 1);
        columns = new TerrainColumns(terrainMinY, chart.minY() + chart.height() - terrainMinY, this::column);
    }

    public PlanetChart chart() { return chart; }
    public SolidPlanetTerrain terrain() { return terrain; }
    /** Exact undecorated column runs for DH, without allocating a host chunk or voxel-height array. */
    public List<TerrainLayer> terrainLayers(int x, int z) {
        if (!ownsColumn(x, z)) { return List.of(new TerrainLayer(chart.minY(), chart.minY() + chart.height(),
                Blocks.AIR.defaultBlockState())); }
        if (terrainMinY == chart.minY()) { return columns.layers(x, z); }
        var result = new ArrayList<TerrainLayer>();
        if (chart.coreFloorY() > chart.minY()) {
            result.add(new TerrainLayer(chart.minY(), chart.coreFloorY(), Blocks.AIR.defaultBlockState()));
        }
        result.add(new TerrainLayer(chart.coreFloorY(), terrainMinY, Blocks.BEDROCK.defaultBlockState()));
        if (terrainMinY < chart.minY() + chart.height()) { result.addAll(columns.layers(x, z)); }
        return List.copyOf(result);
    }
    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }
    @Override public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structures, RandomState random, long seed) {
        return ChunkGeneratorStructureState.createForFlat(random, seed, biomeSource, Stream.empty());
    }
    @Override public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random,
            StructureManager structures, ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("astra_planet_terrain", () -> fill(chunk)),
                Util.backgroundExecutor());
    }
    @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        int result = columns.getBaseHeight(x, z, type, level, random);
        return hasCore(level) && ownsColumn(x, z) && type.isOpaque().test(Blocks.BEDROCK.defaultBlockState())
                ? Math.max(result, chart.coreFloorY() + 1) : result;
    }
    @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        var result = columns.getBaseColumn(x, z, level, random);
        if (hasCore(level) && ownsColumn(x, z)) { result.setBlock(chart.coreFloorY(), Blocks.BEDROCK.defaultBlockState()); }
        return result;
    }
    @Override public int getMinY() { return chart.minY(); }
    @Override public int getGenDepth() { return chart.height(); }
    @Override public int getSeaLevel() { return -chart.altitudeOriginMeters(); }
    @Override public int getSpawnHeight(LevelHeightAccessor level) {
        var column = column(0, 0);
        return column == null ? 0 : Math.clamp(column.top(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
    }
    @Override public void buildSurface(WorldGenRegion level, StructureManager structures, RandomState random, ChunkAccess chunk) { }
    @Override public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomes,
            StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) { }
    @Override public void spawnOriginalMobs(WorldGenRegion level) { }
    @Override public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos position) {
        info.add(String.format(Locale.ROOT, "Astra %s/%s: %s/%d, altitude %.1f m", chart.profile().systemId(),
                chart.profile().bodyId(), chart.face().id(), chart.band(), position.getY() + (double) chart.altitudeOriginMeters()));
    }

    private TerrainColumns.Column column(int x, int z) {
        if (upperAir || !ownsColumn(x, z)) { return null; }
        var sample = terrain.sample(chart.normal(x + .5, z + .5));
        int firstAir = (int) Math.floor(sample.heightMeters()) - chart.altitudeOriginMeters();
        BlockState surface = switch (sample.material()) {
            case ICE -> Blocks.PACKED_ICE.defaultBlockState();
            case SNOW -> Blocks.SNOW_BLOCK.defaultBlockState();
            case SAND -> Blocks.SAND.defaultBlockState();
            case GRASS -> Blocks.GRASS_BLOCK.defaultBlockState();
            case REGOLITH, OCEAN_FLOOR -> Blocks.GRAVEL.defaultBlockState();
            case ROCK -> Blocks.STONE.defaultBlockState();
        };
        BlockState deep = chart.profile().kind() == dev.lexawhatt.astraengine.cosmos.CelestialBody.Kind.ICE
                ? Blocks.PACKED_ICE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        BlockState subsurface = sample.material() == SolidPlanetTerrain.Material.GRASS ? Blocks.DIRT.defaultBlockState() : deep;
        return new TerrainColumns.Column(firstAir, (int) Math.floor(sample.topMeters()) - chart.altitudeOriginMeters(),
                surface, subsurface, deep);
    }

    private boolean ownsColumn(int x, int z) {
        return Math.abs(x + .5) <= chart.radiusMeters() && Math.abs(z + .5) <= chart.radiusMeters()
                && CubeFace.containing(chart.normal(x + .5, z + .5)) == chart.face();
    }

    private boolean hasCore(LevelHeightAccessor level) {
        return chart.coreFloorY() >= Math.max(chart.minY(), level.getMinBuildHeight())
                && chart.coreFloorY() < Math.min(chart.minY() + chart.height(), level.getMaxBuildHeight());
    }

    private ChunkAccess fill(ChunkAccess chunk) {
        columns.fill(chunk);
        if (!hasCore(chunk)) { return chunk; }
        int y = chart.coreFloorY();
        var bedrock = Blocks.BEDROCK.defaultBlockState();
        var position = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int worldX = chunk.getPos().getMinBlockX() + x, worldZ = chunk.getPos().getMinBlockZ() + z;
                if (!ownsColumn(worldX, worldZ)) { continue; }
                chunk.setBlockState(position.set(worldX, y, worldZ), bedrock, false);
                chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG).update(x, y, z, bedrock);
                chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).update(x, y, z, bedrock);
            }
        }
        return chunk;
    }

    private record Definition(SolidPlanetProfile profile, String face, int band, BiomeSource biomes) { }
}
