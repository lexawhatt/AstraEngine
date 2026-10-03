package dev.lexawhatt.astraengine.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.EarthWeather;
import dev.lexawhatt.astraengine.worldgen.UniformTerrainNbt;
import dev.lexawhatt.astraengine.worldgen.UniformTerrainStates;
import dev.lexawhatt.astraengine.worldgen.UniformBiomeNbt;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import dev.lexawhatt.astraengine.surface.CubeFace;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises the real registered Earth preset codecs and generated host storage independently of player worlds. */
@PrefixGameTestTemplate(false)
public final class EarthGenerationGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void tallChunkMissingHeightmapsMatchHostAndPreserveSavedMaps(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var system = ExplorationCatalog.get(server).system("sol");
        var moon = system.bodies().stream().filter(body -> body.id().equals("moon")).findFirst().orElseThrow();
        var profile = SolidPlanetProfile.create(system, moon).orElseThrow();
        var level = PlanetSurfaceWorlds.ensure(server, new PlanetChart(profile, CubeFace.POSITIVE_X, 6));
        var chunk = new ProtoChunk(new ChunkPos(11, -13), UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        int min = level.getMinBuildHeight();
        int[] heights = {min + 9, min + 33, 17, 900, level.getMaxBuildHeight() - 2};
        BlockState[] states = {Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(),
                Blocks.SNOW.defaultBlockState()};
        for (int i = 0; i < heights.length; i++) {
            chunk.setBlockState(new BlockPos(chunk.getPos().getMinBlockX() + i, heights[i],
                    chunk.getPos().getMinBlockZ() + 2), states[i], false);
        }
        // Original, unmodified host priming is the oracle. The new hook is at read(), not this method.
        var requested = EnumSet.copyOf(ChunkStatus.FEATURES.getChunkSaveHeightmaps());
        Heightmap.primeHeightmaps(chunk, requested);
        var saved = ChunkSerializer.write(level, chunk);
        var missing = saved.copy();
        var retained = Heightmap.Types.WORLD_SURFACE;
        for (var type : requested) {
            if (type != retained) { missing.getCompound("Heightmaps").remove(type.getSerializationKey()); }
        }
        var originalRetained = missing.getCompound("Heightmaps").getLongArray(retained.getSerializationKey()).clone();
        var info = new RegionStorageInfo("astra-heightmap-repair-test", level.dimension(), "chunk");
        var restored = ChunkSerializer.read(level, level.getPoiManager(), info, chunk.getPos(), missing);
        for (var type : requested) {
            helper.assertTrue(Arrays.equals(chunk.getOrCreateHeightmapUnprimed(type).getRawData(),
                            restored.getOrCreateHeightmapUnprimed(type).getRawData()),
                    "Missing tall map differs from host for " + type);
        }
        helper.assertTrue(Arrays.equals(originalRetained,
                        restored.getOrCreateHeightmapUnprimed(retained).getRawData()),
                "Repair replaced a heightmap that was already present in the save");
        var complete = ChunkSerializer.read(level, level.getPoiManager(), info, chunk.getPos(), saved);
        for (var type : requested) {
            helper.assertTrue(Arrays.equals(chunk.getOrCreateHeightmapUnprimed(type).getRawData(),
                            complete.getOrCreateHeightmapUnprimed(type).getRawData()),
                    "An empty repair request altered a saved heightmap");
            helper.assertTrue(restored.getOrCreateHeightmapUnprimed(type).getFirstAvailable(15, 15) == min,
                    "Empty tall column did not retain the host minimum for " + type);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void uniformBiomeEncodingIsLocalExactAndRejectsMixedPalettes(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var plains = registry.getHolderOrThrow(Biomes.PLAINS);
        var forest = registry.getHolderOrThrow(Biomes.FOREST);
        var codec = PalettedContainer.codecRO(registry.asHolderIdMap(), registry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, plains);
        var first = new PalettedContainer<>(registry.asHolderIdMap(), forest, PalettedContainer.Strategy.SECTION_BIOMES);
        var second = new PalettedContainer<>(registry.asHolderIdMap(), forest, PalettedContainer.Strategy.SECTION_BIOMES);
        var templates = new UniformBiomeNbt(registry);
        var encodes = new AtomicInteger();
        Function<PalettedContainerRO<Holder<Biome>>, Tag> encode = palette -> {
            encodes.incrementAndGet(); return codec.encodeStart(NbtOps.INSTANCE, palette).getOrThrow();
        };
        var expected = codec.encodeStart(NbtOps.INSTANCE, first).getOrThrow();
        var actual = templates.encode(first, encode);
        helper.assertTrue(expected.equals(actual), "Uniform biome encoding changed the original host format");
        ((CompoundTag) actual).remove("palette");
        helper.assertTrue(expected.equals(templates.encode(second, encode)) && encodes.get() == 1,
                "Repeated uniform sections did not reuse an independent exact template");
        second.getAndSet(2, 1, 3, plains);
        helper.assertTrue(templates.encode(second, encode) == null, "A mixed biome palette lost its changed cell");
        helper.assertTrue(expected.equals(templates.encode(first, encode)), "Changing one palette affected another section");
        helper.assertTrue(expected.equals(new UniformBiomeNbt(registry).encode(first, encode)) && encodes.get() == 2,
                "A biome template leaked beyond one write invocation");
        var bounded = new UniformBiomeNbt(registry);
        var holders = registry.holders().limit(17).toList();
        helper.assertTrue(holders.size() == 17, "Biome budget fixture needs seventeen distinct registered holders");
        for (int index = 0; index < holders.size(); index++) {
            var palette = new PalettedContainer<>(registry.asHolderIdMap(), holders.get(index),
                    PalettedContainer.Strategy.SECTION_BIOMES);
            helper.assertTrue((bounded.encode(palette, encode) != null) == (index < 16),
                    "Invocation-local biome template budget is not bounded at sixteen");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void tallChunkResavePreservesDeserializedPalettesAndBiomeEdits(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var system = ExplorationCatalog.get(server).system("sol");
        var moon = system.bodies().stream().filter(body -> body.id().equals("moon")).findFirst().orElseThrow();
        var profile = SolidPlanetProfile.create(system, moon).orElseThrow();
        var level = PlanetSurfaceWorlds.ensure(server, new PlanetChart(profile, CubeFace.POSITIVE_X, 6));
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var plains = registry.getHolderOrThrow(Biomes.PLAINS);
        var forest = registry.getHolderOrThrow(Biomes.FOREST);
        var chunk = new ProtoChunk(new ChunkPos(7, -9), UpgradeData.EMPTY, level, registry, null);
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        for (int index = 0; index < chunk.getSectionsCount(); index++) {
            chunk.getSections()[index] = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                    Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                    new PalettedContainer<>(registry.asHolderIdMap(), forest, PalettedContainer.Strategy.SECTION_BIOMES));
        }
        var blockCodec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
        var biomeCodec = PalettedContainer.codecRO(registry.asHolderIdMap(), registry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, plains);
        var first = ChunkSerializer.write(level, chunk);
        var tags = first.getList("sections", 10);
        helper.assertTrue(tags.size() == chunk.getSectionsCount(), "Tall save omitted owned section palettes");
        for (int index = 0; index < tags.size(); index++) {
            var section = chunk.getSection(index);
            helper.assertTrue(tags.getCompound(index).get("block_states").equals(
                    blockCodec.encodeStart(NbtOps.INSTANCE, section.getStates()).getOrThrow())
                    && tags.getCompound(index).get("biomes").equals(
                    biomeCodec.encodeStart(NbtOps.INSTANCE, section.getBiomes()).getOrThrow()),
                    "Tall fast save differs from the original block/biome codecs");
        }
        var info = new RegionStorageInfo("astra-tall-palette-test", level.dimension(), "chunk");
        var restored = ChunkSerializer.read(level, level.getPoiManager(), info, chunk.getPos(), first);
        helper.assertTrue(restored.getSection(1).getStates().getClass() == PalettedContainer.class,
                "Reload fixture did not exercise ordinary deserialized host palettes");
        var plain = restored.getSection(1).getStates();
        helper.assertTrue(UniformTerrainNbt.encodeHostPalette(plain,
                palette -> blockCodec.encodeStart(NbtOps.INSTANCE, palette).getOrThrow()) != null,
                "Exact singleton host palette did not enter the bounded save path");
        plain.getAndSet(4, 5, 6, Blocks.GOLD_BLOCK.defaultBlockState());
        var changedBiomes = restored.getSection(1).getBiomes().recreate();
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) { changedBiomes.getAndSet(x, y, z, forest); }
            }
        }
        changedBiomes.getAndSet(1, 2, 3, plains);
        restored.getSections()[1] = new LevelChunkSection(plain, changedBiomes);
        helper.assertTrue(UniformTerrainNbt.encodeHostPalette(plain,
                palette -> blockCodec.encodeStart(NbtOps.INSTANCE, palette).getOrThrow()) == null,
                "A deserialized palette's later block edit reused an obsolete template");
        var second = ChunkSerializer.write(level, restored);
        var reloaded = ChunkSerializer.read(level, level.getPoiManager(), info, chunk.getPos(), second);
        helper.assertTrue(reloaded.getSection(1).getBlockState(4, 5, 6).is(Blocks.GOLD_BLOCK)
                && reloaded.getSection(1).getBlockState(3, 5, 6).isAir()
                && reloaded.getSection(1).getNoiseBiome(1, 2, 3).equals(plains)
                && reloaded.getSection(1).getNoiseBiome(0, 2, 3).equals(forest),
                "Save/read lost a later block or biome edit after deserialization");
        tags.getCompound(0).getCompound("biomes").remove("palette");
        helper.assertTrue(second.getList("sections", 10).getCompound(0).getCompound("biomes").contains("palette"),
                "Two saves share mutable biome NBT");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void uniformPaletteSaveIsExactIndependentAndPreservesLaterEdits(GameTestHelper helper) {
        var level = helper.getLevel();
        var chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        var states = new UniformTerrainStates(Blocks.STONE.defaultBlockState());
        chunk.getSections()[0] = new LevelChunkSection(states, chunk.getSection(0).getBiomes());
        var codec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
        var plain = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.STONE.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        var expected = codec.encodeStart(NbtOps.INSTANCE, plain).getOrThrow();
        var first = ChunkSerializer.write(level, chunk);
        var firstPalette = first.getList("sections", 10).stream().map(CompoundTag.class::cast)
                .filter(section -> section.getByte("Y") == chunk.getMinSection()).findFirst().orElseThrow()
                .getCompound("block_states");
        helper.assertTrue(firstPalette.equals(expected), "Uniform fast save changed the original host palette format");
        firstPalette.remove("palette");
        var second = ChunkSerializer.write(level, chunk);
        var secondPalette = second.getList("sections", 10).stream().map(CompoundTag.class::cast)
                .filter(section -> section.getByte("Y") == chunk.getMinSection()).findFirst().orElseThrow()
                .getCompound("block_states");
        helper.assertTrue(secondPalette.equals(expected), "Returned NBT mutated the shared uniform encoding");
        states.getAndSet(3, 4, 5, Blocks.GOLD_BLOCK.defaultBlockState());
        helper.assertTrue(UniformTerrainNbt.encode(states, palette -> codec.encodeStart(NbtOps.INSTANCE, palette)
                .getOrThrow()) == null, "Edited palette retained the uniform fast path");
        var edited = ChunkSerializer.write(level, chunk);
        var restored = ChunkSerializer.read(level, level.getPoiManager(),
                new RegionStorageInfo("astra-uniform-test", level.dimension(), "chunk"), chunk.getPos(), edited);
        helper.assertTrue(restored.getBlockState(new BlockPos(3, level.getMinBuildHeight() + 4, 5)).is(Blocks.GOLD_BLOCK)
                && restored.getBlockState(new BlockPos(2, level.getMinBuildHeight() + 4, 5)).is(Blocks.STONE),
                "Actual host save/read lost a later edit or neighboring generated material");
        states.getAndSet(3, 4, 5, Blocks.STONE.defaultBlockState());
        helper.assertTrue(UniformTerrainNbt.encode(states, palette -> codec.encodeStart(NbtOps.INSTANCE, palette)
                .getOrThrow()) == null, "A previously changed palette incorrectly reused its initial snapshot");
        var foreign = new UniformTerrainStates(Blocks.DIAMOND_BLOCK.defaultBlockState());
        helper.assertTrue(UniformTerrainNbt.encode(foreign, palette -> { throw new AssertionError("Unexpected encoding"); })
                == null, "Unlisted material entered the bounded cache");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void regionalWeatherUsesTheSameTemperatureAsItsForestMaterial(GameTestHelper helper) {
        var terrain = new ContinentalTerrain(3, ContinentalTerrain.SEED);
        var address = new dev.lexawhatt.astraengine.surface.GeographicPosition(.7661117545732665, 2.783804210211678, 265);
        var chart = EarthChart.owner(address, 3).orElseThrow();
        var feet = chart.resolve(address).orElseThrow();
        var sample = terrain.sample(address.normal());
        helper.assertTrue(sample.temperature() > 10, "Forest temperature fixture changed");
        helper.assertTrue(EarthWeather.warmEnough(chart, terrain, (int) feet.x(), (int) feet.y(), (int) feet.z()),
                "A warm v3 forest canopy received snow from the legacy latitude formula");
        helper.assertTrue(!EarthWeather.warmEnough(chart, terrain, (int) feet.x(), (int) feet.y() + 4000, (int) feet.z()),
                "Temperature above the same forest lost its physical altitude lapse");
        helper.succeed();
    }

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
            try {
                ((dev.lexawhatt.astraengine.worldgen.EarthBiomeSource) generator.getBiomeSource())
                        .biome((ContinentalTerrain.Sample) null);
                helper.assertTrue(false, "Null terrain observation was accepted");
            } catch (IllegalArgumentException expected) { }
            var saved = ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow();
            var restored = (EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops, saved).getOrThrow();
            helper.assertTrue(restored.chart().equals(chart) && restored.terrain().equals(generator.terrain())
                            && restored.caves().equals(generator.caves()) && generator.caves().version() == 2,
                    "Reload changed Earth geography");
            helper.assertTrue(generator.getBiomeSource().possibleBiomes().size() == 13,
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
        JsonObject solid = valid.deepCopy(); solid.remove("cave_version");
        helper.assertTrue(((EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops, solid).getOrThrow()).caves().version() == 0,
                "Missing cave version silently carved a legacy saved world");
        JsonObject broadCaves = valid.deepCopy(); broadCaves.addProperty("cave_version", 1);
        helper.assertTrue(((EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops, broadCaves).getOrThrow()).caves().version() == 1,
                "Saved broad caves were silently changed to the current density");
        for (double version : new double[]{-1, .5, 3, 1e20}) {
            JsonObject malformed = valid.deepCopy(); malformed.addProperty("cave_version", version);
            rejected(helper, malformed, "Unsupported cave version");
        }
        JsonObject legacy = valid.deepCopy(); legacy.addProperty("terrain_version", 1);
        legacy.getAsJsonObject("biome_source").remove("terrain_version");
        legacy.getAsJsonObject("biome_source").getAsJsonObject("palette").remove("river");
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
                int waterTop = (int) Math.floor(generator.terrain().sample(chart.normal(x + .5, z + .5)).waterMeters())
                        - chart.altitudeOriginMeters();
                int top = Math.clamp(waterTop, minY, maxY);
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
                    if (y >= waterTop) {
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

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void cavesCarveActualChunksWithoutChangingLegacyRoofsOrWater(GameTestHelper helper) {
        var level = helper.getLevel();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        var dimensions = preset(helper).getAsJsonObject("dimensions");
        var random = level.getChunkSource().randomState();
        var height = LevelHeightAccessor.create(EarthChart.MIN_Y, EarthChart.HEIGHT);
        long started = System.nanoTime();
        int carved = 0;
        for (var chart : new EarthChart[]{new EarthChart(CubeFace.POSITIVE_X, 0, 2),
                new EarthChart(CubeFace.POSITIVE_Y, -1, 2)}) {
            JsonObject resource = dimensions.getAsJsonObject(chart.dimensionId()).getAsJsonObject("generator");
            var generator = (EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops, resource).getOrThrow();
            for (int chunkX = 0; chunkX < 2; chunkX++) {
                var position = new ChunkPos(chunkX, 0);
                var chunk = new ProtoChunk(position, UpgradeData.EMPTY, height,
                        level.registryAccess().registryOrThrow(Registries.BIOME), null);
                generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
                generator.applyCarvers(null, 0, random, level.getBiomeManager(), level.structureManager(), chunk,
                        net.minecraft.world.level.levelgen.GenerationStep.Carving.AIR);
                int localVoids = 0;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int wx = position.getMinBlockX() + x;
                        int ground = (int) Math.floor(generator.terrain().sample(chart.normal(wx + .5, z + .5)).heightMeters());
                        for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++) {
                            var block = chunk.getBlockState(new BlockPos(wx, y, z));
                            int physical = y + chart.altitudeOriginMeters();
                            if (physical >= ground && physical < 0) {
                                helper.assertTrue(block.is(Blocks.WATER), "Caves drained the ocean");
                            }
                            if (ground < 4 && physical < ground && physical >= ground - 64) {
                                helper.assertTrue(!block.isAir(), "Caves punctured the seabed roof");
                            }
                            if (block.is(Blocks.CAVE_AIR)) {
                                localVoids++;
                                helper.assertTrue(physical < ground && physical >= ground - generator.caves().maxDepthMeters(),
                                        "Carving escaped its physical depth envelope");
                            }
                        }
                    }
                }
                helper.assertTrue(localVoids > 1000, "Actual chunk has no useful underground space: " + localVoids);
                carved += localVoids;
                JsonObject old = resource.deepCopy(); old.remove("cave_version");
                var legacy = (EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops, old).getOrThrow();
                var solid = new ProtoChunk(position, UpgradeData.EMPTY, height,
                        level.registryAccess().registryOrThrow(Registries.BIOME), null);
                legacy.fillFromNoise(Blender.empty(), random, level.structureManager(), solid).join();
                legacy.applyCarvers(null, 0, random, level.getBiomeManager(), level.structureManager(), solid,
                        net.minecraft.world.level.levelgen.GenerationStep.Carving.AIR);
                for (int y = solid.getMinBuildHeight(); y < solid.getMaxBuildHeight(); y++) {
                    helper.assertTrue(!solid.getBlockState(new BlockPos(position.getMinBlockX(), y, 0)).is(Blocks.CAVE_AIR),
                            "Legacy generator began carving on reload");
                }
            }
        }
        dev.lexawhatt.astraengine.AstraEngine.LOGGER.info("ASTRA_VERIFY_CAVES chunks=4 carved={} elapsedMs={}",
                carved, (System.nanoTime() - started) / 1_000_000.0);
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void riverBedsWaterBiomesAndCaveRoofsMatchTheSharedGeography(GameTestHelper helper) {
        var field = new ContinentalTerrain(3, ContinentalTerrain.SEED);
        var rivers = field.rivers().orElseThrow();
        var level = helper.getLevel();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        var dimensions = preset(helper).getAsJsonObject("dimensions");
        var height = LevelHeightAccessor.create(EarthChart.MIN_Y, EarthChart.HEIGHT);
        var random = level.getChunkSource().randomState();
        int checked = 0;
        for (int cell = 0; cell < rivers.cellCount() && checked < 6; cell += 17) {
            if (!rivers.river(cell) || rivers.waterMeters(cell) < 100) { continue; }
            var normal = rivers.point(cell, .5);
            var observation = field.sample(normal);
            var address = dev.lexawhatt.astraengine.surface.GeographicPosition.fromBody(
                    normal.multiply(EarthChart.RADIUS_METERS + observation.waterMeters()), EarthChart.RADIUS_METERS);
            var chart = EarthChart.owner(address, 3).orElseThrow();
            var point = chart.resolve(address).orElseThrow();
            int x = (int) Math.floor(point.x()), z = (int) Math.floor(point.z());
            var definition = dimensions.getAsJsonObject(chart.dimensionId()).getAsJsonObject("generator").deepCopy();
            definition.addProperty("terrain_version", 3);
            definition.getAsJsonObject("biome_source").addProperty("terrain_version", 3);
            var generator = (EarthChunkGenerator) ChunkGenerator.CODEC.parse(ops, definition).getOrThrow();
            var chunk = new ProtoChunk(new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), UpgradeData.EMPTY,
                    height, level.registryAccess().registryOrThrow(Registries.BIOME), null);
            generator.createBiomes(random, Blender.empty(), level.structureManager(), chunk).join();
            generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk).join();
            generator.applyCarvers(null, 0, random, level.getBiomeManager(), level.structureManager(), chunk,
                    net.minecraft.world.level.levelgen.GenerationStep.Carving.AIR);
            var sample = field.sample(chart.normal(x + .5, z + .5));
            int bed = (int) Math.floor(sample.heightMeters()) - chart.altitudeOriginMeters();
            int water = (int) Math.floor(sample.waterMeters()) - chart.altitudeOriginMeters();
            helper.assertTrue(water > bed && water < chunk.getMaxBuildHeight() && bed - 12 >= chunk.getMinBuildHeight(),
                    "River test missed a stored wet column");
            for (int y = bed - 12; y < water; y++) {
                var state = chunk.getBlockState(new BlockPos(x, y, z));
                helper.assertTrue(y >= bed ? state.is(Blocks.WATER) : !state.isAir() && state.getFluidState().isEmpty(),
                        "River bed/water was lost or cave roof was punctured");
            }
            helper.assertTrue(chunk.getBlockState(new BlockPos(x, water, z)).isAir(), "Filled above river level");
            helper.assertTrue(generator.getBiomeSource().getNoiseBiome(Math.floorDiv(x, 4), 0, Math.floorDiv(z, 4), random.sampler())
                    .is(net.minecraft.world.level.biome.Biomes.RIVER), "Raised channel lost its river biome");
            checked++;
        }
        helper.assertTrue(checked == 6, "Missing regional river test sites");
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
