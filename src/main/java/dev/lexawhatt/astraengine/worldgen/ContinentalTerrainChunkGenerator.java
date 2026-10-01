package dev.lexawhatt.astraengine.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.PrimitiveCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.lexawhatt.astraengine.surface.ContinentalRegion;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import java.math.BigDecimal;
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
 * Permanent, version-pinned altitude window into an immutable spherical continental field. One host block is
 * one meter; the region's altitude origin translates Y without compressing relief. Generation workers own the
 * supplied chunks, saved chunks are never regenerated, and this object retains no level or mutable cache.
 * Ordinary biome decoration remains inherited for consumer-provided placed features.
 */
public final class ContinentalTerrainChunkGenerator extends ChunkGenerator {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    // The usual integer codecs narrow Number values. Saved identities must reject fractional or overflowing
    // values before that narrowing, including a seed whose exact long value cannot be represented by a double.
    private static final Codec<Long> EXACT_LONG = new PrimitiveCodec<>() {
        @Override
        public <T> DataResult<Long> read(DynamicOps<T> ops, T input) {
            return ops.getNumberValue(input).flatMap(number -> {
                try {
                    return DataResult.success(new BigDecimal(number.toString()).longValueExact());
                } catch (ArithmeticException | NumberFormatException exception) {
                    return DataResult.error(() -> "Continental identity requires an exact signed integer");
                }
            });
        }

        @Override
        public <T> T write(DynamicOps<T> ops, Long value) { return ops.createLong(value); }
    };
    private static final Codec<Integer> EXACT_INT = EXACT_LONG.comapFlatMap(value ->
            value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                    ? DataResult.success(value.intValue())
                    : DataResult.error(() -> "Continental identity integer exceeds its supported range"), Integer::longValue);
    private static final MapCodec<Definition> DEFINITION_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            EXACT_INT.fieldOf("terrain_version").forGetter(Definition::terrainVersion),
            EXACT_INT.fieldOf("region_version").forGetter(Definition::regionVersion),
            EXACT_LONG.fieldOf("seed").forGetter(Definition::seed),
            Codec.STRING.fieldOf("region").forGetter(Definition::regionId),
            Codec.DOUBLE.fieldOf("anchor_latitude").forGetter(Definition::latitude),
            Codec.DOUBLE.fieldOf("anchor_longitude").forGetter(Definition::longitude),
            EXACT_INT.fieldOf("half_width").forGetter(Definition::halfWidth),
            EXACT_INT.fieldOf("altitude_origin").forGetter(Definition::altitudeOrigin),
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(Definition::biomeSource)
    ).apply(instance, Definition::new));

    /** Rejects changed saved versions, seed, region mapping or altitude origin instead of mixing terrain. */
    public static final MapCodec<ContinentalTerrainChunkGenerator> CODEC = DEFINITION_CODEC.flatXmap(definition -> {
        try {
            return DataResult.success(new ContinentalTerrainChunkGenerator(definition));
        } catch (IllegalArgumentException exception) {
            return DataResult.error(exception::getMessage);
        }
    }, generator -> DataResult.success(generator.definition));

    private final Definition definition;
    private final ContinentalTerrain terrain;
    private final ContinentalRegion region;

    /** Creates the pinned region with a non-null, registry-owned biome source; safe to retain across workers. */
    public ContinentalTerrainChunkGenerator(ContinentalRegion region, BiomeSource biomeSource) {
        this(canonicalDefinition(region, biomeSource));
    }

    private ContinentalTerrainChunkGenerator(Definition definition) {
        super(requireBiomeSource(definition.biomeSource()));
        this.region = ContinentalRegion.byId(definition.regionId());
        if (definition.terrainVersion() != ContinentalTerrain.VERSION
                || definition.regionVersion() != ContinentalRegion.REGION_VERSION
                || definition.seed() != ContinentalTerrain.SEED
                || Double.compare(definition.latitude(), region.patch().latitudeRadians()) != 0
                || Double.compare(definition.longitude(), region.patch().longitudeRadians()) != 0
                || definition.halfWidth() != region.patch().halfWidth()
                || definition.altitudeOrigin() != region.altitudeOriginMeters()) {
            throw new IllegalArgumentException("Continental generator definition does not match pinned region " + region.id());
        }
        this.definition = definition;
        this.terrain = new ContinentalTerrain(definition.terrainVersion(), definition.seed());
    }

    /** Immutable, worker-safe height/climate field, with physical heights independent of host storage bounds. */
    public ContinentalTerrain terrain() { return terrain; }

    /** Immutable permanent mapping and altitude origin for this generator's host world. */
    public ContinentalRegion region() { return region; }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    /** This inspection world has no structure sets; inherited biome decoration still accepts placed features. */
    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structures, RandomState random, long seed) {
        return ChunkGeneratorStructureState.createForFlat(random, seed, biomeSource, Stream.empty());
    }

    /** Uses Minecraft's generation executor and mutates only the supplied not-yet-generated chunk. */
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random,
            StructureManager structures, ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("astra_continental_terrain",
                () -> fillChunk(chunk)), Util.backgroundExecutor());
    }

    private ChunkAccess fillChunk(ChunkAccess chunk) {
        Column[] columns = new Column[256];
        int minY = Math.max(ContinentalRegion.MIN_Y, chunk.getMinBuildHeight());
        int maxY = Math.min(ContinentalRegion.MIN_Y + ContinentalRegion.HEIGHT, chunk.getMaxBuildHeight());
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
    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        Column column = column(x, z);
        if (column == null) { return level.getMinBuildHeight(); }
        int minY = Math.max(ContinentalRegion.MIN_Y, level.getMinBuildHeight());
        int top = Math.min(Math.min(level.getMaxBuildHeight(), ContinentalRegion.MIN_Y + ContinentalRegion.HEIGHT), column.top());
        for (int y = top - 1; y >= minY; y--) {
            if (type.isOpaque().test(block(column, y))) { return y + 1; }
        }
        return level.getMinBuildHeight();
    }

    /** Returns the generated, vertically clipped materials; the caller owns the returned NoiseColumn. */
    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        BlockState[] states = new BlockState[level.getHeight()];
        Arrays.fill(states, AIR);
        Column column = column(x, z);
        if (column != null) {
            int minY = Math.max(ContinentalRegion.MIN_Y, level.getMinBuildHeight());
            int top = Math.min(Math.min(level.getMaxBuildHeight(), ContinentalRegion.MIN_Y + ContinentalRegion.HEIGHT), column.top());
            for (int y = minY; y < top; y++) { states[y - level.getMinBuildHeight()] = block(column, y); }
        }
        return new NoiseColumn(level.getMinBuildHeight(), states);
    }

    private Column column(int x, int z) {
        if (!region.patch().contains(x + 0.5, z + 0.5)) { return null; }
        var sample = terrain.sample(region.patch().normal(x + 0.5, z + 0.5));
        int physicalFirstAir = (int) Math.floor(sample.heightMeters());
        int firstAir = physicalFirstAir - region.altitudeOriginMeters();
        BlockState surface;
        BlockState subsurface;
        if (physicalFirstAir < -5) {
            surface = Blocks.GRAVEL.defaultBlockState(); subsurface = STONE;
        } else if (physicalFirstAir <= 5) {
            surface = Blocks.SAND.defaultBlockState(); subsurface = Blocks.SANDSTONE.defaultBlockState();
        } else if (sample.temperature() <= 0) {
            surface = Blocks.SNOW_BLOCK.defaultBlockState(); subsurface = STONE;
        } else if (sample.heightMeters() >= 2200 || sample.moisture() < 0.18) {
            surface = STONE; subsurface = STONE;
        } else {
            surface = Blocks.GRASS_BLOCK.defaultBlockState(); subsurface = Blocks.DIRT.defaultBlockState();
        }
        // A surface outside the window is legitimate. Keep the actual field height and clip only storage;
        // do not turn a storage boundary into a new bedrock floor, water surface or mountain summit.
        return new Column(firstAir, Math.max(firstAir, region.seaY()), surface, subsurface);
    }

    private static BlockState block(Column column, int y) {
        if (y < ContinentalRegion.MIN_Y || y >= ContinentalRegion.MIN_Y + ContinentalRegion.HEIGHT) { return AIR; }
        if (y >= column.firstAir()) { return y < column.top() ? WATER : AIR; }
        int depth = column.firstAir() - 1 - y;
        return depth == 0 ? column.surface() : depth < 5 ? column.subsurface() : STONE;
    }

    /** Reports physical field elevation, explicitly separate from the region's finite host storage window. */
    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos position) {
        Column column = column(position.getX(), position.getZ());
        info.add(String.format(Locale.ROOT, "Astra continents: v%d, %s, reference terrain %s m",
                ContinentalTerrain.VERSION, region.id(), column == null ? "outside patch"
                        : Integer.toString(column.firstAir() + region.altitudeOriginMeters())));
    }

    @Override public int getMinY() { return ContinentalRegion.MIN_Y; }
    @Override public int getGenDepth() { return ContinentalRegion.HEIGHT; }
    @Override public int getSeaLevel() { return region.seaY(); }

    /** Host reference only: a submerged inspection window has no dry spawn and does not create a platform. */
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

    private static Definition canonicalDefinition(ContinentalRegion region, BiomeSource biomeSource) {
        if (region == null) { throw new IllegalArgumentException("Continental generation requires a region"); }
        return new Definition(ContinentalTerrain.VERSION, ContinentalRegion.REGION_VERSION, ContinentalTerrain.SEED,
                region.id(), region.patch().latitudeRadians(), region.patch().longitudeRadians(),
                region.patch().halfWidth(), region.altitudeOriginMeters(), requireBiomeSource(biomeSource));
    }

    private static BiomeSource requireBiomeSource(BiomeSource source) {
        if (source == null) { throw new IllegalArgumentException("Continental generation requires a biome source"); }
        return source;
    }

    private record Definition(int terrainVersion, int regionVersion, long seed, String regionId,
            double latitude, double longitude, int halfWidth, int altitudeOrigin, BiomeSource biomeSource) {}

    private record Column(int firstAir, int top, BlockState surface, BlockState subsurface) {}
}
