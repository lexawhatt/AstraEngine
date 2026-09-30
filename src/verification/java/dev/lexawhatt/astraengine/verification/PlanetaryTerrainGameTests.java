package dev.lexawhatt.astraengine.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.lexawhatt.astraengine.surface.PlanetaryTerrain;
import dev.lexawhatt.astraengine.worldgen.PlanetaryTerrainChunkGenerator;
import java.io.IOException;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual registered-resource and isolated host ProtoChunk checks; native runs verify loaded highland worlds. */
@PrefixGameTestTemplate(false)
public final class PlanetaryTerrainGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void highlandResourcesPinCompatibleIndependentIdentity(GameTestHelper helper) {
        var level = helper.getLevel();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        JsonObject dimension = resource(helper, "dimension/terrain_highlands.json");
        helper.assertTrue(dimension.get("type").getAsString().equals(PlanetaryTerrain.DIMENSION_ID),
                "Highlands resource does not have its independent world identity");
        PlanetaryTerrainChunkGenerator generator = generator(helper);
        JsonObject saved = ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow().getAsJsonObject();
        var restored = (PlanetaryTerrainChunkGenerator) ChunkGenerator.CODEC.parse(ops, saved).getOrThrow();
        helper.assertTrue(restored.terrain().equals(generator.terrain()), "Highland codec changed the pinned terrain model");
        helper.assertTrue(saved.get("terrain_version").getAsInt() == PlanetaryTerrain.VERSION
                        && saved.get("seed").getAsLong() == PlanetaryTerrain.SEED,
                "Highland codec failed to retain its version and seed");
        JsonObject wrongVersion = saved.deepCopy();
        wrongVersion.addProperty("terrain_version", PlanetaryTerrain.VERSION + 1);
        helper.assertTrue(ChunkGenerator.CODEC.parse(ops, wrongVersion).error().isPresent(),
                "Highland codec accepted an unknown terrain version");
        JsonObject wrongSeed = saved.deepCopy();
        wrongSeed.addProperty("seed", PlanetaryTerrain.SEED + 1);
        helper.assertTrue(ChunkGenerator.CODEC.parse(ops, wrongSeed).error().isPresent(),
                "Highland codec silently regenerated from another seed");
        DimensionType type = DimensionType.DIRECT_CODEC.parse(ops, resource(helper, "dimension_type/terrain_highlands.json"))
                .getOrThrow();
        helper.assertTrue(type.minY() == PlanetaryTerrain.MIN_Y && type.height() == PlanetaryTerrain.HEIGHT
                        && type.logicalHeight() == PlanetaryTerrain.HEIGHT && type.minY() + type.height() == 1792,
                "Highlands vertical frame differs from its source model or host bounds");
        for (String oldBody : new String[]{"moon", "earth"}) {
            JsonObject original = resource(helper, "dimension_type/surface_" + oldBody + ".json");
            helper.assertTrue(original.get("min_y").getAsInt() == 0 && original.get("height").getAsInt() == 256,
                    "Highlands prototype changed an existing version-one world's height");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 240)
    public static void tallSolidOceanAndVoidColumnsRetainBiomesAndHeightmaps(GameTestHelper helper) {
        var level = helper.getLevel();
        var random = level.getChunkSource().randomState();
        PlanetaryTerrainChunkGenerator generator = generator(helper);
        LevelHeightAccessor height = LevelHeightAccessor.create(PlanetaryTerrain.MIN_Y, PlanetaryTerrain.HEIGHT);
        BlockPos highest = BlockPos.ZERO;
        BlockPos lowest = BlockPos.ZERO;
        int maximum = Integer.MIN_VALUE;
        int minimum = Integer.MAX_VALUE;
        for (int x = -30_720; x <= 30_720; x += 2048) {
            for (int z = -30_720; z <= 30_720; z += 2048) {
                int elevation = generator.terrain().firstAir(x, z);
                if (elevation > maximum) { maximum = elevation; highest = new BlockPos(x, 0, z); }
                if (elevation < minimum) { minimum = elevation; lowest = new BlockPos(x, 0, z); }
            }
        }
        helper.assertTrue(maximum >= 1000, "Pinned highland patch has no kilometer-scale terrain sample");
        helper.assertTrue(minimum < PlanetaryTerrain.SEA_Y, "Pinned highland patch has no sampled ocean basin");
        for (ChunkPos position : new ChunkPos[]{new ChunkPos(highest), new ChunkPos(lowest),
                new ChunkPos(2047, 0), new ChunkPos(2048, 0)}) {
            ProtoChunk chunk = new ProtoChunk(position, UpgradeData.EMPTY, height,
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
            generator.createBiomes(random, Blender.empty(), level.structureManager(), chunk).join();
            var expectedBiome = chunk.getSection(1).getNoiseBiome(0, 0, 0);
            generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
            // The host FEATURES stage primes its runtime heightmaps from these actual generated sections.
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
            chunk.initializeLightSources();
            for (var section : chunk.getSections()) {
                helper.assertTrue(section.getNoiseBiome(0, 0, 0).equals(expectedBiome),
                        "Bulk rock sections discarded the registry-resolved biome palette");
            }
            for (int x : new int[]{0, 7, 15}) {
                for (int z : new int[]{0, 9, 15}) {
                    int worldX = position.getMinBlockX() + x;
                    int worldZ = position.getMinBlockZ() + z;
                    boolean inside = PlanetaryTerrain.PATCH.contains(worldX + 0.5, worldZ + 0.5);
                    var column = generator.getBaseColumn(worldX, worldZ, height, random);
                    int firstAir = generator.terrain().firstAir(worldX, worldZ);
                    int top = inside ? Math.max(firstAir, PlanetaryTerrain.SEA_Y) : PlanetaryTerrain.MIN_Y;
                    for (int y = PlanetaryTerrain.MIN_Y; y < PlanetaryTerrain.MIN_Y + PlanetaryTerrain.HEIGHT; y++) {
                        helper.assertTrue(chunk.getBlockState(new BlockPos(worldX, y, worldZ)).equals(column.getBlock(y)),
                                "Highland bulk fill differs from base column at " + worldX + "," + y + "," + worldZ);
                        if (!inside) { helper.assertTrue(column.getBlock(y).isAir(), "Out-of-patch terrain is not void"); }
                    }
                    helper.assertTrue(generator.getBaseHeight(worldX, worldZ, Heightmap.Types.OCEAN_FLOOR_WG, height, random) == firstAir
                                    && generator.getBaseHeight(worldX, worldZ, Heightmap.Types.WORLD_SURFACE_WG, height, random) == top,
                            "Highland solid/fluid height query differs from the shared spherical sample");
                    for (Heightmap.Types type : new Heightmap.Types[]{Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.OCEAN_FLOOR}) {
                        helper.assertTrue(chunk.getHeight(type, x, z) == firstAir - 1, "Tall solid heightmap is incorrect: " + type);
                    }
                    for (Heightmap.Types type : new Heightmap.Types[]{Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.WORLD_SURFACE,
                            Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES}) {
                        helper.assertTrue(chunk.getHeight(type, x, z) == top - 1, "Tall fluid/runtime heightmap is incorrect: " + type);
                    }
                    if (inside) {
                        helper.assertTrue(column.getBlock(PlanetaryTerrain.MIN_Y).is(Blocks.BEDROCK), "Tall terrain lacks bedrock");
                        helper.assertTrue(column.getBlock(firstAir).is(firstAir < PlanetaryTerrain.SEA_Y ? Blocks.WATER : Blocks.AIR),
                                "Highland solid/water/air boundary is inconsistent");
                    }
                }
            }
        }
        helper.succeed();
    }

    private static PlanetaryTerrainChunkGenerator generator(GameTestHelper helper) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        ChunkGenerator decoded = ChunkGenerator.CODEC.parse(ops, resource(helper, "dimension/terrain_highlands.json").get("generator"))
                .getOrThrow();
        helper.assertTrue(decoded instanceof PlanetaryTerrainChunkGenerator, "Highland generator codec was not registered");
        return (PlanetaryTerrainChunkGenerator) decoded;
    }

    private static JsonObject resource(GameTestHelper helper, String path) {
        var location = ResourceLocation.fromNamespaceAndPath("astraengine", path);
        try (var reader = helper.getLevel().getServer().getResourceManager().getResourceOrThrow(location).openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read highland verification resource: " + location, failure);
        }
    }
}
