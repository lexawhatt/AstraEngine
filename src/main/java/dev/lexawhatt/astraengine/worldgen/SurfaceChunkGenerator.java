package dev.lexawhatt.astraengine.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import dev.lexawhatt.astraengine.surface.SurfaceGeography;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
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
 * Immutable version-pinned voxel realization of one permanent geographic patch.
 * Minecraft owns generation scheduling and saved chunks; this class never edits a loaded world,
 * allocates a dimension, or changes existing chunks on reload. Column sampling is safe on host generation workers.
 */
public final class SurfaceChunkGenerator extends ChunkGenerator {
    private static final int MIN_Y = 0;
    private static final int HEIGHT = 256;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final Codec<String> BODY_CODEC = Codec.STRING.validate(value ->
            value.equals("moon") || value.equals("earth") ? DataResult.success(value)
                    : DataResult.error(() -> "Surface patches support only the fixed Sol Moon and Earth bindings"));

    /** Full codec pins the patch identity/version/seed instead of deriving terrain from a mutable world seed. */
    public static final MapCodec<SurfaceChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BODY_CODEC.fieldOf("body").forGetter(SurfaceChunkGenerator::bodyId),
            Codec.intRange(1, 1).fieldOf("geography_version").forGetter(SurfaceChunkGenerator::geographyVersion),
            Codec.LONG.fieldOf("seed").forGetter(SurfaceChunkGenerator::seed),
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(SurfaceChunkGenerator::getBiomeSource)
    ).apply(instance, SurfaceChunkGenerator::new));

    private final SurfaceDefinition definition;

    /**
     * Constructs a fixed built-in patch; unknown identities, generation versions, or seeds fail loading.
     * The immutable biome source is registry-owned. No world or chunk instance is retained.
     */
    public SurfaceChunkGenerator(String bodyId, int geographyVersion, long seed, BiomeSource biomeSource) {
        super(requireBiomeSource(biomeSource));
        definition = SurfaceDefinition.byBody(bodyId);
        if (definition.version() != geographyVersion || definition.seed() != seed) {
            throw new IllegalArgumentException("Surface generator identity differs from its pinned definition: "
                    + bodyId + ", version=" + geographyVersion + ", seed=" + seed);
        }
    }

    /** Stable descriptor used by server handoff validation; callers cannot mutate the generator's binding. */
    public SurfaceDefinition definition() { return definition; }

    private String bodyId() { return definition.bodyId(); }
    private int geographyVersion() { return definition.version(); }
    private long seed() { return definition.seed(); }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    /** Surface patches deliberately contain no procedural structures or biome decorations in this version. */
    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structures, RandomState random, long seed) {
        return ChunkGeneratorStructureState.createForFlat(random, seed, biomeSource, Stream.empty());
    }

    /** Fills only the chunk supplied by Minecraft's generation callback; already saved terrain is never revisited. */
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random,
            StructureManager structures, ChunkAccess chunk) {
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minY = Math.max(MIN_Y, chunk.getMinBuildHeight());
        int maxY = Math.min(MIN_Y + HEIGHT, chunk.getMaxBuildHeight());
        int originX = chunk.getPos().getMinBlockX();
        int originZ = chunk.getPos().getMinBlockZ();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = originX + localX;
                int worldZ = originZ + localZ;
                Column column = column(worldX, worldZ);
                // The dimension border is also enforced by the server. This independent hard guard
                // makes off-patch generation finite and never wraps into another geographic location.
                if (column == null) { continue; }
                int top = Math.min(maxY, column.top());
                for (int y = minY; y < top; y++) {
                    BlockState state = block(column, y);
                    if (state.isAir()) { continue; }
                    chunk.setBlockState(position.set(worldX, y, worldZ), state, false);
                    oceanFloor.update(localX, y, localZ, state);
                    worldSurface.update(localX, y, localZ, state);
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    /** Uses the same column/material rules as generation, including fluid-sensitive heightmap predicates. */
    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        Column column = column(x, z);
        if (column == null) { return level.getMinBuildHeight(); }
        int minY = Math.max(MIN_Y, level.getMinBuildHeight());
        for (int y = Math.min(level.getMaxBuildHeight(), column.top()) - 1; y >= minY; y--) {
            if (type.isOpaque().test(block(column, y))) { return y + 1; }
        }
        return level.getMinBuildHeight();
    }

    /** Returns a complete height-accessor column; coordinates outside the fixed patch contain only air. */
    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        BlockState[] states = new BlockState[level.getHeight()];
        Arrays.fill(states, AIR);
        Column column = column(x, z);
        if (column != null) {
            int minY = Math.max(MIN_Y, level.getMinBuildHeight());
            int top = Math.min(Math.min(MIN_Y + HEIGHT, level.getMaxBuildHeight()), column.top());
            for (int y = minY; y < top; y++) { states[y - level.getMinBuildHeight()] = block(column, y); }
        }
        return new NoiseColumn(level.getMinBuildHeight(), states);
    }

    private Column column(int blockX, int blockZ) {
        double x = blockX + 0.5;
        double z = blockZ + 0.5;
        if (!definition.patch().contains(x, z)) { return null; }
        var sample = definition.geography().sample(definition.patch().normal(x, z));
        // SurfaceDefinition.terrainY uses this exact first-air convention. Sampling once also
        // keeps material, shoreline and height consistent at each block-column center.
        int firstAir = (int) Math.floor(definition.patch().seaY() + sample.heightMeters());
        if (firstAir <= MIN_Y || firstAir >= MIN_Y + HEIGHT) {
            throw new IllegalStateException("Versioned surface height exceeds its fixed dimension range");
        }
        int seaY = (int) definition.patch().seaY();
        return new Column(firstAir, sample.ocean() ? Math.max(firstAir, seaY) : firstAir, sample.material());
    }

    private BlockState block(Column column, int y) {
        if (y < MIN_Y || y >= MIN_Y + HEIGHT) { return AIR; }
        if (y == MIN_Y) { return Blocks.BEDROCK.defaultBlockState(); }
        if (y >= column.firstAir()) { return y < column.top() ? WATER : AIR; }
        int depth = column.firstAir() - 1 - y;
        boolean lunar = definition.bodyId().equals("moon");
        if (depth >= 5) { return (lunar ? Blocks.BASALT : Blocks.STONE).defaultBlockState(); }
        return switch (column.material()) {
            case REGOLITH -> (depth < 2 ? Blocks.ANDESITE : Blocks.TUFF).defaultBlockState();
            case ROCK -> (lunar ? Blocks.BASALT : Blocks.STONE).defaultBlockState();
            case GRASS -> (depth == 0 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState();
            case SAND -> Blocks.SAND.defaultBlockState();
            case ICE -> (depth == 0 ? Blocks.SNOW_BLOCK : Blocks.PACKED_ICE).defaultBlockState();
            case OCEAN_FLOOR -> Blocks.GRAVEL.defaultBlockState();
        };
    }

    /** Adds version/binding information to the vanilla debug screen without touching saved state. */
    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos position) {
        Column column = column(position.getX(), position.getZ());
        info.add(String.format(Locale.ROOT, "Astra surface: sol/%s v%d, terrain %s", definition.bodyId(),
                definition.version(), column == null ? "outside patch" : Integer.toString(column.firstAir())));
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor level) {
        Column center = column(0, 0);
        return Math.clamp(center == null ? getSeaLevel() : center.top(),
                level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
    }

    @Override public int getMinY() { return MIN_Y; }
    @Override public int getGenDepth() { return HEIGHT; }
    @Override public int getSeaLevel() { return (int) definition.patch().seaY(); }
    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structures, RandomState random, ChunkAccess chunk) {}
    @Override public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {}
    @Override public void spawnOriginalMobs(WorldGenRegion level) {}

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomes,
            StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {}

    private static BiomeSource requireBiomeSource(BiomeSource source) {
        if (source == null) { throw new IllegalArgumentException("Surface generation requires a biome source"); }
        return source;
    }

    private record Column(int firstAir, int top, SurfaceGeography.Material material) {}
}
