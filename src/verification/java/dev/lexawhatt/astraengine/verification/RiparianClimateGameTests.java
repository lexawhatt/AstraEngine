package dev.lexawhatt.astraengine.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.EarthClimate;
import dev.lexawhatt.astraengine.surface.EarthSurfacePalette;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import java.io.IOException;
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
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Verifies versioned river geometry and climate through the saved codec and actual host blocks. */
@PrefixGameTestTemplate(false)
public final class RiparianClimateGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void curvedChannelsUseTheSameBedWaterAndBiomeInNativeChunks(GameTestHelper helper) {
        var field = new ContinentalTerrain(4, ContinentalTerrain.SEED);
        var atlas = field.rivers().orElseThrow();
        var level = helper.getLevel();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        var dimensions = preset(helper).getAsJsonObject("dimensions");
        var random = level.getChunkSource().randomState();
        int checked = 0;
        for (int cell = 0; cell < atlas.cellCount() && checked < 3; cell += 37) {
            if (!atlas.river(cell) || atlas.waterMeters(cell) < 100) { continue; }
            var direction = atlas.point(cell, .37);
            var observation = field.sample(direction);
            if (!observation.river() || observation.temperature() < 5) { continue; }
            var address = GeographicPosition.fromBody(direction.multiply(EarthChart.RADIUS_METERS
                    + observation.waterMeters()), EarthChart.RADIUS_METERS);
            var chart = EarthChart.owner(address, 4).orElseThrow();
            var point = chart.resolve(address).orElseThrow();
            int x = (int) Math.floor(point.x()), z = (int) Math.floor(point.z());
            var sample = field.sample(chart.normal(x + .5, z + .5));
            int bed = (int) Math.floor(sample.heightMeters()) - chart.altitudeOriginMeters();
            int water = (int) Math.floor(sample.waterMeters()) - chart.altitudeOriginMeters();
            if (water <= bed || bed <= EarthChart.MIN_Y + 12 || water >= EarthChart.MIN_Y + EarthChart.HEIGHT) { continue; }
            var generator = (EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops,
                    dimensions.getAsJsonObject(chart.dimensionId()).get("generator")).getOrThrow();
            var chunk = new ProtoChunk(new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), UpgradeData.EMPTY,
                    LevelHeightAccessor.create(EarthChart.MIN_Y, EarthChart.HEIGHT),
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
            generator.createBiomes(random, Blender.empty(), level.structureManager(), chunk).join();
            generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
            helper.assertTrue(!chunk.getBlockState(new BlockPos(x, bed - 1, z)).isAir(), "Curved river lost its solid bed");
            for (int y = bed; y < water; y++) {
                helper.assertTrue(chunk.getBlockState(new BlockPos(x, y, z)).is(Blocks.WATER), "Curved river has a native water gap");
            }
            helper.assertTrue(chunk.getBlockState(new BlockPos(x, water, z)).isAir(), "River overflowed its shared water level");
            helper.assertTrue(generator.getBiomeSource().getNoiseBiome(Math.floorDiv(x, 4), 0,
                    Math.floorDiv(z, 4), random.sampler()).is(net.minecraft.world.level.biome.Biomes.RIVER),
                    "Native curved river lost its shared river biome");
            checked++;
        }
        helper.assertTrue(checked == 3, "No representative curved raised channels were checked");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void newValleyMaterialsMatchOrbitWithoutMigratingSavedV3(GameTestHelper helper) {
        var normal = new SpaceVector(.8268180005447172, .3394324720038389, -.4485059541685025);
        var chart = EarthChart.owner(GeographicPosition.fromBody(normal.multiply(EarthChart.RADIUS_METERS + 76),
                EarthChart.RADIUS_METERS), 4).orElseThrow();
        var point = chart.resolve(GeographicPosition.fromBody(normal.multiply(EarthChart.RADIUS_METERS + 76),
                EarthChart.RADIUS_METERS)).orElseThrow();
        int x = (int) Math.floor(point.x()), z = (int) Math.floor(point.z());
        var level = helper.getLevel();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        JsonObject resource = preset(helper).getAsJsonObject("dimensions").getAsJsonObject(chart.dimensionId())
                .getAsJsonObject("generator");
        helper.assertTrue(resource.get("terrain_version").getAsInt() == 4,
                "Fresh Earth preset did not select the riparian algorithm");
        int previousHeight = Integer.MIN_VALUE;
        for (int version : new int[]{3, 4}) {
            var definition = resource.deepCopy();
            definition.addProperty("terrain_version", version);
            definition.getAsJsonObject("biome_source").addProperty("terrain_version", version);
            var generator = (EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops, definition).getOrThrow();
            var encoded = ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow();
            var restored = (EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops, encoded).getOrThrow();
            helper.assertTrue(restored.chart().terrainVersion() == version && restored.terrain().version() == version,
                    "Save/reload silently migrated an existing geographic version");
            var chunk = new ProtoChunk(new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), UpgradeData.EMPTY,
                    LevelHeightAccessor.create(EarthChart.MIN_Y, EarthChart.HEIGHT),
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
            var random = level.getChunkSource().randomState();
            restored.createBiomes(random, Blender.empty(), level.structureManager(), chunk).join();
            restored.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
            var sample = restored.terrain().sample(chart.normal(x + .5, z + .5));
            int firstAir = (int) Math.floor(sample.heightMeters()) - chart.altitudeOriginMeters();
            var surface = chunk.getBlockState(new BlockPos(x, firstAir - 1, z));
            helper.assertTrue(!sample.water() && chunk.getBlockState(new BlockPos(x, firstAir, z)).isAir(),
                    "Native dry surface disagrees with its versioned geographic height/water contract");
            if (version == 3) {
                helper.assertTrue(surface.is(Blocks.SAND) && EarthSurfacePalette.material(sample) == EarthClimate.DESERT,
                        "Historical v3 no longer retains its exact desert bank");
                previousHeight = firstAir;
            } else {
                helper.assertTrue(firstAir > previousHeight + 50,
                        "New geometry retained the old straight incision at the reported desert finger");
                helper.assertTrue(surface.is(Blocks.GRASS_BLOCK)
                                && EarthSurfacePalette.material(sample) != EarthClimate.DESERT,
                        "Native valley blocks and orbital material disagree with the revised riparian climate");
            }
        }
        helper.succeed();
    }

    private static JsonObject preset(GameTestHelper helper) {
        var id = ResourceLocation.fromNamespaceAndPath("astraengine", "worldgen/world_preset/earth.json");
        try (var reader = helper.getLevel().getServer().getResourceManager().getResourceOrThrow(id).openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read the Earth preset for riparian verification", exception);
        }
    }
}
