package dev.lexawhatt.astraengine.verification;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.lexawhatt.astraengine.surface.ContinentalRegion;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.worldgen.ContinentalTerrainChunkGenerator;
import java.io.IOException;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Registered continental resources and real host ProtoChunks, isolated from player saves and native fixtures. */
@PrefixGameTestTemplate(false)
public final class ContinentalTerrainGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 200)
    public static void allRegionCodecsPinPhysicalIdentityAndRejectMalformedDrift(GameTestHelper helper) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        DimensionType type = DimensionType.DIRECT_CODEC.parse(ops,
                resource(helper, "dimension_type/continental_surface.json")).getOrThrow();
        helper.assertTrue(type.minY() == ContinentalRegion.MIN_Y && type.height() == ContinentalRegion.HEIGHT
                        && type.logicalHeight() == ContinentalRegion.HEIGHT && type.minY() + type.height() == 2032,
                "Continental vertical storage window differs from its pinned host bounds");
        for (ContinentalRegion region : ContinentalRegion.values()) {
            JsonObject dimension = resource(helper, "dimension/continental_" + region.id() + ".json");
            helper.assertTrue(dimension.get("type").getAsString().equals("astraengine:continental_surface"),
                    "Continental dimension has an unexpected storage type");
            ContinentalTerrainChunkGenerator generator = generator(helper, region);
            JsonObject saved = ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow().getAsJsonObject();
            var restored = (ContinentalTerrainChunkGenerator) ChunkGenerator.CODEC.parse(ops, saved).getOrThrow();
            helper.assertTrue(restored.region() == region && restored.terrain().equals(generator.terrain()),
                    "Continental codec lost its region or exact seed");
            helper.assertTrue(saved.get("seed").getAsLong() == ContinentalTerrain.SEED
                            && saved.get("altitude_origin").getAsInt() == region.altitudeOriginMeters(),
                    "Continental encoding rounded its seed or changed physical altitude");

            for (String field : new String[]{"terrain_version", "region_version", "half_width", "altitude_origin"}) {
                JsonObject changed = saved.deepCopy();
                changed.addProperty(field, saved.get(field).getAsInt() + 1);
                rejected(helper, changed, "Changed " + field);
                changed = saved.deepCopy();
                changed.addProperty(field, saved.get(field).getAsInt() + .5);
                rejected(helper, changed, "Fractional " + field);
                changed = saved.deepCopy();
                changed.addProperty(field, "not-an-integer");
                rejected(helper, changed, "Malformed " + field);
            }
            JsonObject changedSeed = saved.deepCopy();
            changedSeed.addProperty("seed", ContinentalTerrain.SEED + 1);
            rejected(helper, changedSeed, "Changed seed");
            changedSeed.add("seed", JsonParser.parseString(ContinentalTerrain.SEED + ".5"));
            rejected(helper, changedSeed, "Fractional seed");
            changedSeed.add("seed", JsonParser.parseString("9223372036854775808"));
            rejected(helper, changedSeed, "Overflowing seed");

            for (String field : new String[]{"anchor_latitude", "anchor_longitude"}) {
                JsonObject changed = saved.deepCopy();
                changed.addProperty(field, Math.nextUp(saved.get(field).getAsDouble()));
                rejected(helper, changed, "Changed exact " + field);
                changed.addProperty(field, "not-an-angle");
                rejected(helper, changed, "Malformed " + field);
            }
            for (String id : new String[]{"unknown", region.id().toUpperCase(java.util.Locale.ROOT), region.dimensionId()}) {
                JsonObject changed = saved.deepCopy();
                changed.addProperty("region", id);
                rejected(helper, changed, "Noncanonical region");
            }
            for (String field : new String[]{"terrain_version", "region_version", "seed", "region",
                    "anchor_latitude", "anchor_longitude", "half_width", "altitude_origin", "biome_source"}) {
                JsonObject changed = saved.deepCopy();
                changed.remove(field);
                rejected(helper, changed, "Missing " + field);
                changed.add(field, JsonNull.INSTANCE);
                rejected(helper, changed, "Null " + field);
                changed.add(field, new JsonArray());
                rejected(helper, changed, "Array " + field);
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 300)
    public static void coastalSolidWaterAndOutsideColumnsMatchPhysicalField(GameTestHelper helper) {
        verifyColumns(helper, ContinentalRegion.COAST);
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 300)
    public static void alpineColumnsRetainMeterScaleWithSeaBelowTheirWindow(GameTestHelper helper) {
        verifyColumns(helper, ContinentalRegion.ALPINE);
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 300)
    public static void abyssalColumnsClipWaterWithoutInventingANewSeaSurface(GameTestHelper helper) {
        verifyColumns(helper, ContinentalRegion.ABYSS);
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 240)
    public static void suppliedBiomeSourcesSurviveBulkGenerationAtEveryStorageDepth(GameTestHelper helper) {
        var level = helper.getLevel();
        var plains = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        var source = new FixedBiomeSource(plains);
        for (ContinentalRegion region : ContinentalRegion.values()) {
            var generator = new ContinentalTerrainChunkGenerator(region, source);
            var chunk = filledChunk(helper, generator, new ChunkPos(0, 0));
            for (var section : chunk.getSections()) {
                helper.assertTrue(section.getNoiseBiome(0, 0, 0).equals(plains)
                                && section.getNoiseBiome(3, 3, 3).equals(plains),
                        "Continental bulk fill replaced a caller-supplied biome palette");
            }
        }
        // This observes real biome input ownership. It does not pretend a ProtoChunk executes placed features.
        helper.succeed();
    }

    private static void verifyColumns(GameTestHelper helper, ContinentalRegion region) {
        var level = helper.getLevel();
        var random = level.getChunkSource().randomState();
        var generator = generator(helper, region);
        LevelHeightAccessor height = LevelHeightAccessor.create(ContinentalRegion.MIN_Y, ContinentalRegion.HEIGHT);
        int minimum = Integer.MAX_VALUE, maximum = Integer.MIN_VALUE;
        BlockPos low = BlockPos.ZERO, high = BlockPos.ZERO;
        int bound = region.patch().halfWidth();
        for (int x = -bound; x < bound; x += 1024) {
            for (int z = -bound; z < bound; z += 1024) {
                int surface = region.firstAir(generator.terrain(), x, z);
                if (surface < minimum) { minimum = surface; low = new BlockPos(x, 0, z); }
                if (surface > maximum) { maximum = surface; high = new BlockPos(x, 0, z); }
            }
        }
        if (region == ContinentalRegion.COAST) {
            helper.assertTrue(minimum < 0 && maximum > 50, "Coastal inspection window lacks actual ocean and land");
        } else if (region == ContinentalRegion.ALPINE) {
            helper.assertTrue(maximum + region.altitudeOriginMeters() > 8000 && maximum - minimum > 2000,
                    "Alpine inspection window lost its physical elevation or relief");
        } else {
            helper.assertTrue(maximum + region.altitudeOriginMeters() < -5000,
                    "Abyss window is not a deep physical basin");
        }
        var positions = new LinkedHashSet<ChunkPos>();
        positions.add(new ChunkPos(low));
        positions.add(new ChunkPos(high));
        positions.add(new ChunkPos((bound - 1) >> 4, 0));
        positions.add(new ChunkPos(bound >> 4, 0));
        for (ChunkPos position : positions) {
            ProtoChunk chunk = filledChunk(helper, generator, position);
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
            chunk.initializeLightSources();
            for (int x : new int[]{0, 15}) {
                for (int z : new int[]{0, 15}) {
                    int worldX = position.getMinBlockX() + x, worldZ = position.getMinBlockZ() + z;
                    boolean inside = region.patch().contains(worldX + .5, worldZ + .5);
                    int physicalSurface = region.firstAir(generator.terrain(), worldX, worldZ);
                    int minY = ContinentalRegion.MIN_Y, maxY = minY + ContinentalRegion.HEIGHT;
                    int solidTop = inside ? Math.clamp(physicalSurface, minY, maxY) : minY;
                    int surfaceTop = inside ? Math.clamp(Math.max(physicalSurface, region.seaY()), minY, maxY) : minY;
                    var column = generator.getBaseColumn(worldX, worldZ, height, random);
                    for (int y = minY; y < maxY; y++) {
                        var actual = chunk.getBlockState(new BlockPos(worldX, y, worldZ));
                        if (!actual.equals(column.getBlock(y))) {
                            helper.fail("Continental chunk/column mismatch at " + worldX + "," + y + "," + worldZ);
                        }
                        helper.assertTrue(!actual.is(Blocks.BEDROCK), "Storage window invented an artificial bedrock floor");
                        if (!inside) { helper.assertTrue(actual.isAir(), "Outside-region column is not void"); }
                    }
                    helper.assertTrue(column.getBlock(minY - 1).isAir() && column.getBlock(maxY).isAir(),
                            "Base column exposes blocks outside its storage band");
                    helper.assertTrue(generator.getBaseHeight(worldX, worldZ, Heightmap.Types.OCEAN_FLOOR_WG,
                                    height, random) == solidTop
                                    && generator.getBaseHeight(worldX, worldZ, Heightmap.Types.WORLD_SURFACE_WG,
                                    height, random) == surfaceTop,
                            "Continental base-height query disagrees with physical solid/fluid boundaries");
                    for (Heightmap.Types type : new Heightmap.Types[]{Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.OCEAN_FLOOR}) {
                        helper.assertTrue(chunk.getHeight(type, x, z) == solidTop - 1, "Wrong solid heightmap: " + type);
                    }
                    for (Heightmap.Types type : new Heightmap.Types[]{Heightmap.Types.WORLD_SURFACE_WG,
                            Heightmap.Types.WORLD_SURFACE, Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES}) {
                        helper.assertTrue(chunk.getHeight(type, x, z) == surfaceTop - 1, "Wrong fluid heightmap: " + type);
                    }
                    if (inside && region == ContinentalRegion.ABYSS) {
                        helper.assertTrue(column.getBlock(maxY - 1).is(Blocks.WATER) && region.seaY() > maxY,
                                "Abyssal water was incorrectly cut into an exposed local sea surface");
                    }
                    if (inside && region == ContinentalRegion.ALPINE) {
                        helper.assertTrue(column.getBlock(physicalSurface).isAir() && region.seaY() < minY,
                                "Alpine world inserted local water at its translated sea coordinate");
                    }
                }
            }
        }
    }

    private static ProtoChunk filledChunk(GameTestHelper helper, ContinentalTerrainChunkGenerator generator, ChunkPos position) {
        var level = helper.getLevel();
        var chunk = new ProtoChunk(position, UpgradeData.EMPTY,
                LevelHeightAccessor.create(ContinentalRegion.MIN_Y, ContinentalRegion.HEIGHT),
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        var random = level.getChunkSource().randomState();
        generator.createBiomes(random, Blender.empty(), level.structureManager(), chunk).join();
        var expected = chunk.getSection(0).getNoiseBiome(0, 0, 0);
        generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
        for (var section : chunk.getSections()) {
            helper.assertTrue(section.getNoiseBiome(0, 0, 0).equals(expected),
                    "Continental fill discarded registry-resolved biome data");
        }
        return chunk;
    }

    private static void rejected(GameTestHelper helper, JsonObject definition, String reason) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        helper.assertTrue(ChunkGenerator.CODEC.parse(ops, definition).error().isPresent(),
                "Continental codec accepted: " + reason);
    }

    private static ContinentalTerrainChunkGenerator generator(GameTestHelper helper, ContinentalRegion region) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        ChunkGenerator decoded = ChunkGenerator.CODEC.parse(ops,
                resource(helper, "dimension/continental_" + region.id() + ".json").get("generator")).getOrThrow();
        helper.assertTrue(decoded instanceof ContinentalTerrainChunkGenerator, "Continental generator is not registered");
        return (ContinentalTerrainChunkGenerator) decoded;
    }

    private static JsonObject resource(GameTestHelper helper, String path) {
        var location = ResourceLocation.fromNamespaceAndPath("astraengine", path);
        try (var reader = helper.getLevel().getServer().getResourceManager().getResourceOrThrow(location).openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read continental verification resource: " + location, failure);
        }
    }
}
