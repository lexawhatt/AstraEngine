package dev.lexawhatt.astraengine.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import dev.lexawhatt.astraengine.server.SurfaceBindings;
import dev.lexawhatt.astraengine.worldgen.SurfaceChunkGenerator;
import java.io.IOException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Dedicated-server resource-codec and isolated block-column checks; native fixtures verify loaded worlds. */
@PrefixGameTestTemplate(false)
public final class SurfaceGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void horizonOceanUsesPermanentHostFlatResource(GameTestHelper helper) {
        var level = helper.getLevel();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        JsonObject dimension = resourceJson(helper, "dimension/horizon_ocean.json");
        helper.assertTrue(dimension.get("type").getAsString().equals("astraengine:horizon_ocean"),
                "Calibration world lost its independent permanent identity");
        var generator = ChunkGenerator.CODEC.parse(ops, dimension.get("generator")).getOrThrow();
        helper.assertTrue(generator instanceof net.minecraft.world.level.levelgen.FlatLevelSource,
                "Calibration world must retain vanilla flat block storage");
        var height = LevelHeightAccessor.create(0, 256);
        var column = generator.getBaseColumn(0, 0, height, level.getChunkSource().randomState());
        helper.assertTrue(column.getBlock(0).is(Blocks.BEDROCK) && column.getBlock(55).is(Blocks.SAND)
                        && column.getBlock(56).is(Blocks.WATER) && column.getBlock(63).is(Blocks.WATER)
                        && column.getBlock(64).isAir(),
                "Host ocean layers no longer meet the calibration sea reference");
        var encoded = ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow();
        helper.assertTrue(ChunkGenerator.CODEC.parse(ops, encoded).getOrThrow()
                        instanceof net.minecraft.world.level.levelgen.FlatLevelSource,
                "Saved calibration generator cannot be restored by the host codec");
        var type = net.minecraft.world.level.dimension.DimensionType.DIRECT_CODEC.parse(ops,
                resourceJson(helper, "dimension_type/horizon_ocean.json")).getOrThrow();
        helper.assertTrue(type.minY() == 0 && type.height() == 256 && type.hasSkyLight(),
                "Calibration dimension type changed its block frame or sky light");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void surfaceResourcesAndGeneratorCodec(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        helper.assertTrue(!(server.overworld().getChunkSource().getGenerator() instanceof SurfaceChunkGenerator),
                "Surface implementation replaced the existing Overworld generator");
        var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        SurfaceBindings bindings = SurfaceBindings.get(server);
        CompoundTag manifest = bindings.save(new CompoundTag(), server.registryAccess());
        SurfaceBindings restored = SurfaceBindings.decode(manifest.copy());
        for (String body : new String[]{"moon", "earth"}) {
            // GameTestServer omits datapack LevelStems. Decode the shipped resources with its
            // actual biome and generator registries; native SurfaceScenario checks loaded worlds.
            JsonObject dimension = resourceJson(helper, "dimension/surface_" + body + ".json");
            helper.assertTrue(dimension.get("type").getAsString().equals("astraengine:surface_" + body),
                    "Surface resource references a different dimension type");
            SurfaceChunkGenerator generator = resourceGenerator(helper, body);
            SurfaceDefinition expected = SurfaceDefinition.byBody(body);
            helper.assertTrue(generator.definition().equals(expected), "Dimension has the wrong geographic binding");
            helper.assertTrue(!restored.matches(null, expected) && !restored.matches(server.overworld(), expected),
                    "Saved surface binding accepted an absent world or the existing Overworld");
            JsonObject type = resourceJson(helper, "dimension_type/surface_" + body + ".json");
            helper.assertTrue(type.get("min_y").getAsInt() == 0 && type.get("height").getAsInt() == 256
                            && type.get("logical_height").getAsInt() == 256,
                    "Surface dimension resource changed its fixed vertical coordinate frame");
            JsonObject encoded = ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow().getAsJsonObject();
            SurfaceChunkGenerator decoded = (SurfaceChunkGenerator) ChunkGenerator.CODEC.parse(ops, encoded).getOrThrow();
            helper.assertTrue(decoded.definition().equals(expected), "Generator codec changed body/version/seed");
            helper.assertTrue(encoded.get("geography_version").getAsInt() == expected.version()
                            && encoded.get("seed").getAsLong() == expected.seed(),
                    "Saved generator does not pin geography version and seed");
            JsonObject unsupported = encoded.deepCopy();
            unsupported.addProperty("geography_version", 999);
            helper.assertTrue(ChunkGenerator.CODEC.parse(ops, unsupported).error().isPresent(),
                    "Unsupported generator version silently selected current terrain");
            boolean rejected = false;
            try {
                new SurfaceChunkGenerator(body, expected.version(), expected.seed() + 1, generator.getBiomeSource());
            } catch (IllegalArgumentException invalid) { rejected = true; }
            helper.assertTrue(rejected, "Generator accepted a seed that would silently replace a permanent patch");
            for (String field : new String[]{"seed", "version", "dimension", "system"}) {
                CompoundTag invalid = manifest.copy();
                invalid.getCompound(body).remove(field);
                boolean malformedRejected = false;
                try { SurfaceBindings.decode(invalid); }
                catch (IllegalArgumentException malformed) { malformedRejected = true; }
                helper.assertTrue(malformedRejected, "Incomplete persisted surface binding was silently recreated: " + field);
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 200)
    public static void generatedColumnsAndVoidBoundaryMatchSharedGeography(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelHeightAccessor height = LevelHeightAccessor.create(0, 256);
        var random = level.getChunkSource().randomState();
        for (String body : new String[]{"moon", "earth"}) {
            SurfaceChunkGenerator generator = resourceGenerator(helper, body);
            for (ChunkPos position : new ChunkPos[]{new ChunkPos(0, 0), new ChunkPos(-1, 1),
                    new ChunkPos(127, 0), new ChunkPos(128, 0)}) {
                // Isolated host ProtoChunks exercise the actual production fill callback without
                // allocating unrelated FULL chunks or changing the world's retained terrain.
                ProtoChunk chunk = new ProtoChunk(position, UpgradeData.EMPTY, height,
                        level.registryAccess().registryOrThrow(Registries.BIOME), null);
                generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
                for (int localX : new int[]{0, 7, 15}) {
                    for (int localZ : new int[]{0, 9, 15}) {
                        int x = position.getMinBlockX() + localX;
                        int z = position.getMinBlockZ() + localZ;
                        boolean inside = generator.definition().patch().contains(x + 0.5, z + 0.5);
                        var column = generator.getBaseColumn(x, z, height, random);
                        for (int y = 0; y < 256; y++) {
                            var actual = chunk.getBlockState(new BlockPos(x, y, z));
                            helper.assertTrue(actual.equals(column.getBlock(y)),
                                    "Generated block differs from queried base column at " + x + "," + y + "," + z);
                            if (!inside) { helper.assertTrue(actual.isAir(), "Out-of-patch column contains generated terrain"); }
                        }
                        int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, height, random);
                        int top = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
                        if (inside) {
                            int terrain = generator.definition().terrainY(x + 0.5, z + 0.5);
                            helper.assertTrue(floor == terrain, "Solid height differs from shared geographic first-air height");
                            helper.assertTrue(column.getBlock(0).is(Blocks.BEDROCK), "Playable terrain lacks its bedrock floor");
                            helper.assertTrue(chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, localX, localZ) == floor - 1
                                            && chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) == top - 1,
                                    "Generated heightmaps differ from solid/fluid block columns");
                        } else {
                            helper.assertTrue(floor == 0 && top == 0, "Void boundary reports a phantom terrain surface");
                        }
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void persistedSurfaceManifestRejectsChangedIdentityWithoutMutation(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        CompoundTag saved = SurfaceBindings.get(server).save(new CompoundTag(), server.registryAccess());
        CompoundTag restored = SurfaceBindings.decode(saved.copy()).save(new CompoundTag(), server.registryAccess());
        helper.assertTrue(saved.equals(restored), "Surface manifest round trip changed its exact typed identities");

        CompoundTag unsupported = saved.copy();
        unsupported.putInt("version", 2);
        assertManifestRejected(helper, unsupported, "future manifest version");
        CompoundTag wrongVersionType = saved.copy();
        wrongVersionType.putString("version", "1");
        assertManifestRejected(helper, wrongVersionType, "string manifest version");
        for (String body : new String[]{"moon", "earth"}) {
            CompoundTag missingBody = saved.copy();
            missingBody.remove(body);
            assertManifestRejected(helper, missingBody, "missing body " + body);
            CompoundTag wrongBodyType = saved.copy();
            wrongBodyType.putString(body, "not a binding compound");
            assertManifestRejected(helper, wrongBodyType, "wrong body tag type " + body);

            CompoundTag changedSeed = saved.copy();
            changedSeed.getCompound(body).putLong("seed", SurfaceDefinition.byBody(body).seed() + 1);
            assertManifestRejected(helper, changedSeed, "changed seed " + body);
            CompoundTag wrongSeedType = saved.copy();
            wrongSeedType.getCompound(body).putInt("seed", (int) SurfaceDefinition.byBody(body).seed());
            assertManifestRejected(helper, wrongSeedType, "narrowed seed tag " + body);
            CompoundTag changedVersion = saved.copy();
            changedVersion.getCompound(body).putInt("version", 2);
            assertManifestRejected(helper, changedVersion, "future geography version " + body);
            CompoundTag changedDimension = saved.copy();
            changedDimension.getCompound(body).putString("dimension", "minecraft:overworld");
            assertManifestRejected(helper, changedDimension, "Overworld replacement " + body);
            CompoundTag changedSystem = saved.copy();
            changedSystem.getCompound(body).putString("system", "example:sol");
            assertManifestRejected(helper, changedSystem, "custom system replacement " + body);
        }
        helper.assertTrue(SurfaceBindings.decode(saved).save(new CompoundTag(), server.registryAccess()).equals(restored),
                "Rejected manifests altered the last valid geographic binding");
        helper.succeed();
    }

    private static void assertManifestRejected(GameTestHelper helper, CompoundTag invalid, String reason) {
        CompoundTag original = invalid.copy();
        boolean rejected = false;
        try { SurfaceBindings.decode(invalid); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "Invalid surface manifest was accepted: " + reason);
        helper.assertTrue(invalid.equals(original), "Rejected surface manifest was mutated: " + reason);
    }

    private static SurfaceChunkGenerator resourceGenerator(GameTestHelper helper, String body) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().getServer().registryAccess());
        JsonObject dimension = resourceJson(helper, "dimension/surface_" + body + ".json");
        ChunkGenerator generator = ChunkGenerator.CODEC.parse(ops, dimension.get("generator")).getOrThrow();
        helper.assertTrue(generator instanceof SurfaceChunkGenerator,
                "Surface resource does not use the registered terrain generator: " + body);
        return (SurfaceChunkGenerator) generator;
    }

    private static JsonObject resourceJson(GameTestHelper helper, String path) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("astraengine", path);
        try (var reader = helper.getLevel().getServer().getResourceManager()
                .getResourceOrThrow(location).openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read surface verification resource: " + location, failure);
        }
    }
}
