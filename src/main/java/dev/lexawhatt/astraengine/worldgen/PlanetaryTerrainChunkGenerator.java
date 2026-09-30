package dev.lexawhatt.astraengine.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.lexawhatt.astraengine.surface.PlanetaryTerrain;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Separate version-pinned highland prototype. Host generation workers own each supplied chunk;
 * this generator retains no level, schedules no ticks, and never revisits saved terrain.
 * Standard biome decoration remains inherited so resource-defined features can participate.
 */
public final class PlanetaryTerrainChunkGenerator extends ChunkGenerator {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final Codec<Long> SEED_CODEC = Codec.LONG.validate(value -> value == PlanetaryTerrain.SEED
            ? DataResult.success(value) : DataResult.error(() -> "Highland prototype requires its pinned terrain seed"));

    /** Pins the prototype version and seed; unsupported saved inputs fail instead of selecting new terrain. */
    public static final MapCodec<PlanetaryTerrainChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.intRange(PlanetaryTerrain.VERSION, PlanetaryTerrain.VERSION).fieldOf("terrain_version")
                    .forGetter(PlanetaryTerrainChunkGenerator::terrainVersion),
            SEED_CODEC.fieldOf("seed").forGetter(PlanetaryTerrainChunkGenerator::terrainSeed),
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(PlanetaryTerrainChunkGenerator::getBiomeSource)
    ).apply(instance, PlanetaryTerrainChunkGenerator::new));

    private final PlanetaryTerrain terrain;

    /** Creates immutable generation data; the supplied biome source is registry-owned and must not be null. */
    public PlanetaryTerrainChunkGenerator(int version, long seed, BiomeSource biomeSource) {
        super(requireBiomeSource(biomeSource));
        if (seed != PlanetaryTerrain.SEED) {
            throw new IllegalArgumentException("Highland prototype requires its pinned terrain seed");
        }
        terrain = new PlanetaryTerrain(version, seed);
    }

    /** Returns the immutable spherical height/climate model; safe to sample on generation workers. */
    public PlanetaryTerrain terrain() { return terrain; }

    private int terrainVersion() { return PlanetaryTerrain.VERSION; }
    private long terrainSeed() { return PlanetaryTerrain.SEED; }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    /** This prototype has no structure sets; biome placed features keep the normal host decoration path. */
    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structures, RandomState random, long seed) {
        return ChunkGeneratorStructureState.createForFlat(random, seed, biomeSource, Stream.empty());
    }

    /** Uses Minecraft's generation executor; only the supplied, not-yet-generated chunk is mutated. */
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random,
            StructureManager structures, ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("astra_highland_terrain",
                () -> fillChunk(chunk)), Util.backgroundExecutor());
    }

    private ChunkAccess fillChunk(ChunkAccess chunk) {
        Column[] columns = new Column[256];
        int minY = Math.max(PlanetaryTerrain.MIN_Y, chunk.getMinBuildHeight());
        int maxY = Math.min(PlanetaryTerrain.MIN_Y + PlanetaryTerrain.HEIGHT, chunk.getMaxBuildHeight());
        int highest = minY;
        int solidStoneCeiling = maxY;
        int originX = chunk.getPos().getMinBlockX();
        int originZ = chunk.getPos().getMinBlockZ();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                Column column = column(originX + x, originZ + z);
                columns[x + z * 16] = column;
                if (column == null) {
                    solidStoneCeiling = minY;
                } else {
                    highest = Math.max(highest, Math.min(maxY, column.top()));
                    solidStoneCeiling = Math.min(solidStoneCeiling, column.firstAir() - 5);
                }
            }
        }

        for (int sectionY = SectionPos.blockToSectionCoord(minY);
                sectionY < SectionPos.blockToSectionCoord(highest - 1) + 1 && highest > minY; sectionY++) {
            int baseY = SectionPos.sectionToBlockCoord(sectionY);
            int index = chunk.getSectionIndexFromSectionY(sectionY);
            LevelChunkSection section = chunk.getSection(index);
            if (baseY > PlanetaryTerrain.MIN_Y && baseY >= minY && baseY + 16 <= solidStoneCeiling) {
                // BIOMES has finished before NOISE. Preserve its palette while replacing a whole
                // homogeneous rock section; its constructor derives correct nonempty block counts.
                chunk.getSections()[index] = new LevelChunkSection(new PalettedContainer<>(
                        Block.BLOCK_STATE_REGISTRY, STONE, PalettedContainer.Strategy.SECTION_STATES), section.getBiomes());
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
        // Minecraft's INITIALIZE_LIGHT/LIGHT stages inspect the finished sections and heightmaps.
        return chunk;
    }

    /** Matches generated first-air heights for solid/fluid predicates, including empty off-patch columns. */
    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        Column column = column(x, z);
        if (column == null) { return level.getMinBuildHeight(); }
        int minY = Math.max(PlanetaryTerrain.MIN_Y, level.getMinBuildHeight());
        int top = Math.min(Math.min(level.getMaxBuildHeight(), PlanetaryTerrain.MIN_Y + PlanetaryTerrain.HEIGHT), column.top());
        for (int y = top - 1; y >= minY; y--) {
            if (type.isOpaque().test(block(column, y))) { return y + 1; }
        }
        return level.getMinBuildHeight();
    }

    /** Returns the exact generated material column; host callers own the returned mutable array through NoiseColumn. */
    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        BlockState[] states = new BlockState[level.getHeight()];
        Arrays.fill(states, AIR);
        Column column = column(x, z);
        if (column != null) {
            int minY = Math.max(PlanetaryTerrain.MIN_Y, level.getMinBuildHeight());
            int top = Math.min(Math.min(level.getMaxBuildHeight(), PlanetaryTerrain.MIN_Y + PlanetaryTerrain.HEIGHT), column.top());
            for (int y = minY; y < top; y++) { states[y - level.getMinBuildHeight()] = block(column, y); }
        }
        return new NoiseColumn(level.getMinBuildHeight(), states);
    }

    private Column column(int x, int z) {
        if (!PlanetaryTerrain.PATCH.contains(x + 0.5, z + 0.5)) { return null; }
        var sample = terrain.sample(PlanetaryTerrain.PATCH.normal(x + 0.5, z + 0.5));
        int firstAir = (int) Math.floor(PlanetaryTerrain.SEA_Y + sample.heightMeters());
        if (firstAir <= PlanetaryTerrain.MIN_Y || firstAir >= PlanetaryTerrain.MIN_Y + PlanetaryTerrain.HEIGHT) {
            throw new IllegalStateException("Pinned highland surface exceeds its dimension's vertical range");
        }
        BlockState surface;
        BlockState subsurface;
        if (firstAir < PlanetaryTerrain.SEA_Y - 5) {
            surface = Blocks.GRAVEL.defaultBlockState(); subsurface = STONE;
        } else if (firstAir <= PlanetaryTerrain.SEA_Y + 5) {
            surface = Blocks.SAND.defaultBlockState(); subsurface = Blocks.SANDSTONE.defaultBlockState();
        } else if (sample.temperature() <= 0) {
            surface = Blocks.SNOW_BLOCK.defaultBlockState(); subsurface = STONE;
        } else if (sample.heightMeters() >= 700 || sample.moisture() < 0.18) {
            surface = STONE; subsurface = STONE;
        } else {
            surface = Blocks.GRASS_BLOCK.defaultBlockState(); subsurface = Blocks.DIRT.defaultBlockState();
        }
        return new Column(firstAir, Math.max(firstAir, PlanetaryTerrain.SEA_Y), surface, subsurface);
    }

    private static BlockState block(Column column, int y) {
        if (y < PlanetaryTerrain.MIN_Y || y >= PlanetaryTerrain.MIN_Y + PlanetaryTerrain.HEIGHT) { return AIR; }
        if (y == PlanetaryTerrain.MIN_Y) { return BEDROCK; }
        if (y >= column.firstAir()) { return y < column.top() ? WATER : AIR; }
        int depth = column.firstAir() - 1 - y;
        return depth == 0 ? column.surface() : depth < 5 ? column.subsurface() : STONE;
    }

    /** Shows the independent prototype identity and reference height without affecting server-owned terrain. */
    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos position) {
        Column column = column(position.getX(), position.getZ());
        info.add(String.format(Locale.ROOT, "Astra highlands: v%d, reference terrain %s m", PlanetaryTerrain.VERSION,
                column == null ? "outside patch" : Integer.toString(column.firstAir() - PlanetaryTerrain.SEA_Y)));
    }

    @Override public int getMinY() { return PlanetaryTerrain.MIN_Y; }
    @Override public int getGenDepth() { return PlanetaryTerrain.HEIGHT; }
    @Override public int getSeaLevel() { return PlanetaryTerrain.SEA_Y; }

    @Override
    public int getSpawnHeight(LevelHeightAccessor level) {
        Column center = column(0, 0);
        return Math.clamp(center == null ? getSeaLevel() : center.top(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structures, RandomState random, ChunkAccess chunk) {}

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomes,
            StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {}

    @Override public void spawnOriginalMobs(WorldGenRegion level) {}

    private static BiomeSource requireBiomeSource(BiomeSource source) {
        if (source == null) { throw new IllegalArgumentException("Highland terrain generation requires a biome source"); }
        return source;
    }

    private record Column(int firstAir, int top, BlockState surface, BlockState subsurface) {}
}
