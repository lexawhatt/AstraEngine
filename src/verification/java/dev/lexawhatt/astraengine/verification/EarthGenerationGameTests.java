package dev.lexawhatt.astraengine.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.EarthWeather;
import dev.lexawhatt.astraengine.surface.CubeFace;
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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises the real registered Earth preset codecs and generated host storage independently of player worlds. */
@PrefixGameTestTemplate(false)
public final class EarthGenerationGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void physicalWeatherIgnoresStorageBandAndDoesNotFreezeTropicalHighlands(GameTestHelper helper) {
        var terrain = new ContinentalTerrain(ContinentalTerrain.VERSION, ContinentalTerrain.SEED);
        for (int band = EarthChart.MIN_BAND; band <= EarthChart.MAX_BAND; band++) {
            var equator = new EarthChart(CubeFace.POSITIVE_X, band);
            helper.assertTrue(EarthWeather.warmEnough(equator, terrain, 8, 740 - equator.altitudeOriginMeters(), 8),
                    "Tropical highlands froze because of local host Y");
            var north = new EarthChart(CubeFace.POSITIVE_Y, band);
            int physical = (int) Math.ceil(Math.max(0, terrain.sample(north.normal(0, 0)).heightMeters())) + 1;
            helper.assertTrue(!EarthWeather.warmEnough(north, terrain, 0, physical - north.altitudeOriginMeters(), 0),
                    "Polar weather differs across storage bands");
            helper.assertTrue(EarthWeather.warmEnough(north, terrain, 0, -5000 - north.altitudeOriginMeters(), 0),
                    "A submerged storage boundary can freeze into a fake ice surface");
        }
        var level = helper.getLevel();
        var position = helper.absolutePos(new BlockPos(0, 5, 0));
        var biome = level.getBiome(position).value();
        helper.assertTrue(EarthWeather.warmEnoughToRain(biome, level, position) == biome.warmEnoughToRain(position),
                "Earth weather changed an ordinary world's biome behavior");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void completeEarthPresetPinsEveryChartAndRetainsBiomeFeatures(GameTestHelper helper) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var dimensions = preset(helper).getAsJsonObject("dimensions");
        helper.assertTrue(dimensions.size() == 38, "Earth preset is missing charts or host Nether/End worlds");
        for (var chart : EarthChart.all(ContinentalTerrain.CURRENT_VERSION)) {
            var resource = dimensions.getAsJsonObject(chart.dimensionId());
            helper.assertTrue(resource != null && resource.get("type").getAsString().equals("astraengine:earth_surface"),
                    "Missing Earth chart storage type: " + chart);
            var generator = (EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops, resource.get("generator")).getOrThrow();
            helper.assertTrue(generator.chart().equals(chart), "Preset generator has a different canonical chart");
            var saved = ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow();
            var restored = (EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops, saved).getOrThrow();
            helper.assertTrue(restored.chart().equals(chart) && restored.terrain().equals(generator.terrain()),
                    "Reload changed Earth geography");
            helper.assertTrue(generator.getBiomeSource().possibleBiomes().size() == 12,
                    "Earth climate palette lost a biome");
            helper.assertTrue(generator.getBiomeSource().possibleBiomes().stream().anyMatch(biome ->
                    !biome.value().getGenerationSettings().features().isEmpty()), "Earth lost host biome decoration");
        }
        JsonObject valid = dimensions.getAsJsonObject("minecraft:overworld").getAsJsonObject("generator");
        for (String field : new String[]{"chart_version", "terrain_version", "seed", "band", "face", "biome_source"}) {
            JsonObject invalid = valid.deepCopy(); invalid.remove(field);
            rejected(helper, invalid, "Missing " + field);
            invalid = valid.deepCopy(); invalid.addProperty(field, true);
            rejected(helper, invalid, "Boolean " + field);
        }
        for (String field : new String[]{"chart_version", "terrain_version", "band"}) {
            JsonObject invalid = valid.deepCopy(); invalid.addProperty(field, .5);
            rejected(helper, invalid, "Fractional " + field);
            invalid.addProperty(field, 9223372036854775807L);
            rejected(helper, invalid, "Overflowing " + field);
        }
        JsonObject invalid = valid.deepCopy(); invalid.addProperty("seed", ContinentalTerrain.SEED + 1);
        rejected(helper, invalid, "Changed seed");
        invalid.add("seed", JsonParser.parseString(ContinentalTerrain.SEED + ".5"));
        rejected(helper, invalid, "Fractional seed");
        invalid = valid.deepCopy(); invalid.addProperty("face", "unknown");
        rejected(helper, invalid, "Unknown face");
        invalid = valid.deepCopy(); invalid.getAsJsonObject("biome_source").addProperty("face", "nx");
        rejected(helper, invalid, "Biome face differs from stored blocks");
        invalid = valid.deepCopy(); invalid.getAsJsonObject("biome_source").getAsJsonObject("palette").remove("forest");
        rejected(helper, invalid, "Incomplete biome palette");
        invalid = valid.deepCopy(); invalid.getAsJsonObject("biome_source").addProperty("terrain_version", 1);
        rejected(helper, invalid, "Biome algorithm differs from stored blocks");
        JsonObject legacy = valid.deepCopy(); legacy.addProperty("terrain_version", 1);
        legacy.getAsJsonObject("biome_source").remove("terrain_version");
        var oldGenerator = (EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops, legacy).getOrThrow();
        helper.assertTrue(oldGenerator.terrain().version() == 1 && oldGenerator.chart().terrainVersion() == 1,
                "Legacy Earth generator was silently migrated");
        helper.assertTrue(Math.abs(oldGenerator.terrain().sample(new dev.lexawhatt.astraengine.cosmos.SpaceVector(1, 0, 0))
                .heightMeters() - 713.2982624938669) < 1e-8, "Legacy canonical relief changed");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void everyStorageBandMatchesPhysicalColumnsAndRetainsBiomes(GameTestHelper helper) {
        var level = helper.getLevel();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        var dimensions = preset(helper).getAsJsonObject("dimensions");
        var height = LevelHeightAccessor.create(EarthChart.MIN_Y, EarthChart.HEIGHT);
        var random = level.getChunkSource().randomState();
        int minY = EarthChart.MIN_Y, maxY = minY + EarthChart.HEIGHT;
        for (var chart : EarthChart.all(ContinentalTerrain.CURRENT_VERSION)) {
            var generator = (EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops,
                    dimensions.getAsJsonObject(chart.dimensionId()).get("generator")).getOrThrow();
            var position = new ChunkPos(7, -11);
            var chunk = new ProtoChunk(position, UpgradeData.EMPTY, height,
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
            generator.createBiomes(random, Blender.empty(), level.structureManager(), chunk).join();
            var biomeBeforeFill = chunk.getSection(0).getNoiseBiome(0, 0, 0);
            generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
            for (var section : chunk.getSections()) {
                helper.assertTrue(section.getNoiseBiome(0, 0, 0).equals(biomeBeforeFill),
                        "Uniform section fill discarded the climate biome");
            }
            for (int localX : new int[]{0, 15}) {
                int x = position.getMinBlockX() + localX, z = position.getMinBlockZ() + 9;
                int firstAir = (int) Math.floor(generator.terrain().sample(chart.normal(x + .5, z + .5)).heightMeters())
                        - chart.altitudeOriginMeters();
                int solid = Math.clamp(firstAir, minY, maxY);
                int top = Math.clamp(Math.max(firstAir, generator.getSeaLevel()), minY, maxY);
                var column = generator.getBaseColumn(x, z, height, random);
                var layers = generator.terrainLayers(x, z);
                int nextY = minY;
                helper.assertTrue(layers.size() <= 5, "LOD base column expanded into voxels");
                for (var layer : layers) {
                    helper.assertTrue(layer.bottomY() == nextY, "LOD base column has a gap or overlap");
                    for (int y = layer.bottomY(); y < layer.topY(); y++) {
                        helper.assertTrue(layer.state().equals(column.getBlock(y)), "LOD material differs from chunk sampler");
                    }
                    nextY = layer.topY();
                }
                helper.assertTrue(nextY == maxY, "LOD base column omitted explicit air or a band edge");
                for (int y = minY; y < maxY; y++) {
                    var actual = chunk.getBlockState(new BlockPos(x, y, z));
                    helper.assertTrue(actual.equals(column.getBlock(y)), "Stored Earth column differs from base query");
                    helper.assertTrue(!actual.is(Blocks.BEDROCK), "Altitude band invented a bedrock boundary");
                    if (y >= Math.max(firstAir, generator.getSeaLevel())) {
                        helper.assertTrue(actual.isAir(), "Filled above the physical surface");
                    } else if (y >= firstAir) {
                        helper.assertTrue(actual.is(Blocks.WATER), "Clipped a physical ocean into local air");
                    } else {
                        helper.assertTrue(!actual.isAir() && actual.getFluidState().isEmpty(), "Missing physical ground");
                    }
                }
                helper.assertTrue(generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, height, random) == solid
                                && chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, localX, 9) == solid - 1,
                        "Earth solid heightmap disagrees with physical surface and storage clipping");
                helper.assertTrue(generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random) == top
                                && chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, 9) == top - 1,
                        "Earth fluid heightmap disagrees with physical ocean and storage clipping");
            }
        }
        helper.succeed();
    }

    private static void rejected(GameTestHelper helper, JsonObject value, String reason) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        helper.assertTrue(ChunkGenerator.CODEC.parse(ops, value).error().isPresent(), "Accepted invalid Earth identity: " + reason);
    }

    private static JsonObject preset(GameTestHelper helper) {
        var id = ResourceLocation.fromNamespaceAndPath("astraengine", "worldgen/world_preset/earth.json");
        try (var reader = helper.getLevel().getServer().getResourceManager().getResourceOrThrow(id).openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Earth preset verification resource", exception);
        }
    }
}
