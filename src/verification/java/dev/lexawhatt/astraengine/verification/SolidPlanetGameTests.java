package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.network.CubeStorageCharts;
import dev.lexawhatt.astraengine.network.PlanetContextPayload;
import dev.lexawhatt.astraengine.server.PlanetSurfaceBindings;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.server.EarthSpawn;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.BoundaryCollision;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import dev.lexawhatt.astraengine.worldgen.PlanetChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.UniformTerrainStates;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Whole solid-body codecs, generated host materials and permanent dimension ownership in disposable worlds. */
@PrefixGameTestTemplate(false)
public final class SolidPlanetGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void generatedUpperAirKeepsBiomesForeignPalettesAndLaterSavedEdits(GameTestHelper helper) {
        var system = ExplorationCatalog.get(helper.getLevel().getServer()).system("sol");
        var moon = system.bodies().stream().filter(body -> body.id().equals("moon")).findFirst().orElseThrow();
        var profile = SolidPlanetProfile.create(system, moon).orElseThrow();
        var chart = new PlanetChart(profile, CubeFace.POSITIVE_X, 25);
        var world = PlanetSurfaceWorlds.ensure(helper.getLevel().getServer(), chart);
        var generator = (PlanetChunkGenerator) world.getChunkSource().getGenerator();
        var chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, world,
                world.registryAccess().registryOrThrow(Registries.BIOME), null);
        generator.createBiomes(world.getChunkSource().randomState(), Blender.empty(), world.structureManager(), chunk).join();
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        var biomes = chunk.getSection(0).getBiomes();
        var cave = new BlockPos(2, world.getMinBuildHeight() + 16 + 3, 4);
        chunk.setBlockState(cave, Blocks.CAVE_AIR.defaultBlockState(), false);
        // ProtoChunk.getBlockState intentionally returns ordinary AIR for an all-air section. Inspect
        // the actual stored palette to distinguish CAVE_AIR without changing the host's empty-section rule.
        helper.assertTrue(chunk.getSection(1).getBlockState(2, 3, 4).is(Blocks.CAVE_AIR),
                "The upper-air fixture did not store its distinct air state");
        var foreign = new PalettedContainer<BlockState>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES) { };
        chunk.getSections()[2] = new LevelChunkSection(foreign, chunk.getSection(2).getBiomes());
        generator.fillFromNoise(Blender.empty(), world.getChunkSource().randomState(), world.structureManager(), chunk).join();
        helper.assertTrue(chunk.getSection(0).getStates() instanceof UniformTerrainStates
                && chunk.getSection(0).getBiomes() == biomes && chunk.getSection(0).hasOnlyAir(),
                "Generated upper air lost its independent biome palette or empty-section counts");
        helper.assertTrue(chunk.getSection(1).getBlockState(2, 3, 4).is(Blocks.CAVE_AIR)
                && chunk.getSection(2).getStates() == foreign,
                "Upper-air serialization marking replaced a distinct air state or foreign palette implementation");
        var original = ChunkSerializer.write(world, chunk);
        var info = new RegionStorageInfo("astra-upper-air-test", world.dimension(), "chunk");
        var restored = ChunkSerializer.read(world, world.getPoiManager(), info, chunk.getPos(), original);
        var edit = new BlockPos(3, world.getMinBuildHeight() + 4, 5);
        helper.assertTrue(restored.getBlockState(edit).is(Blocks.AIR)
                && restored.getSection(1).getBlockState(2, 3, 4).is(Blocks.CAVE_AIR)
                && restored.getSection(0).getBiomes().get(0, 0, 0).equals(biomes.get(0, 0, 0)),
                "Actual host upper-air save/read changed exact air states or biome identity");
        chunk.setBlockState(edit, Blocks.GOLD_BLOCK.defaultBlockState(), false);
        var afterEdit = ChunkSerializer.read(world, world.getPoiManager(), info, chunk.getPos(), ChunkSerializer.write(world, chunk));
        helper.assertTrue(afterEdit.getBlockState(edit).is(Blocks.GOLD_BLOCK)
                && afterEdit.getBlockState(edit.east()).is(Blocks.AIR)
                && afterEdit.getSection(1).getBlockState(2, 3, 4).is(Blocks.CAVE_AIR),
                "An edit to generated upper air was lost or contaminated neighboring saved cells");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void proceduralCatalogBodyKeepsItsOwnTerrainFrameAndPersistentWorld(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var catalog = ExplorationCatalog.get(server);
        var system = catalog.system("s_1_0_0");
        var profile = system.bodies().stream().map(body -> SolidPlanetProfile.create(system, body))
                .flatMap(java.util.Optional::stream).findFirst().orElseThrow();
        var terrain = new dev.lexawhatt.astraengine.surface.SolidPlanetTerrain(profile);
        var direction = new dev.lexawhatt.astraengine.cosmos.SpaceVector(1, 0, 0);
        double altitude = terrain.sample(direction).heightMeters() + 2;
        var chart = PlanetChart.owner(profile, new GeographicPosition(0, 0, altitude)).orElseThrow();
        var world = PlanetSurfaceWorlds.ensure(server, chart);
        var generator = (PlanetChunkGenerator) world.getChunkSource().getGenerator();
        world.getChunk(0, 0);
        var sample = terrain.sample(chart.normal(8.5, 8.5));
        int y = (int) Math.floor(sample.heightMeters()) - chart.altitudeOriginMeters();
        var ground = new BlockPos(8, y - 1, 8);
        helper.assertTrue(!world.getBlockState(ground).isAir(), "Generated body did not use its whole-body terrain field");
        var marker = ground.above(2);
        helper.assertTrue(world.setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), 3), "Generated body rejected its own surface edit");
        helper.assertTrue(PlanetSurfaceWorlds.ensure(server, chart) == world
                && world.getBlockState(marker).is(Blocks.GOLD_BLOCK), "Returning to a procedural body replaced its storage");
        var frame = profile.frame(system, 12000);
        var point = direction.multiply(profile.radiusMeters() + altitude);
        helper.assertTrue(frame.toBodyPoint(frame.toSystemPoint(point)).distance(point) < .001,
                "Generated surface and orbital frames disagree");
        var bindings = PlanetSurfaceBindings.get(server);
        var decoded = PlanetSurfaceBindings.decode(bindings.save(new CompoundTag(), world.registryAccess()), world.registryAccess());
        helper.assertTrue(decoded.binding(chart.dimensionId()).chart().equals(chart)
                && generator.chart().profile().equals(profile), "Persistent generated profile changed on reload");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void tinySolidCoreMatchesColumnsAndStopsMovementAfterBlockRemoval(GameTestHelper helper) {
        var source = helper.getLevel();
        var profile = new SolidPlanetProfile(1, "verify:tiny_core", "rock", 8, 16,
                CelestialBody.Kind.ROCKY, 40000, 0, 0);
        var chart = new PlanetChart(profile, CubeFace.POSITIVE_X, 0);
        var biome = source.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.THE_VOID);
        var generator = new PlanetChunkGenerator(chart, new FixedBiomeSource(biome));
        var heights = LevelHeightAccessor.create(chart.minY(), chart.height());
        var chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, heights,
                source.registryAccess().registryOrThrow(Registries.BIOME), null);
        generator.fillFromNoise(Blender.empty(), source.getChunkSource().randomState(), source.structureManager(), chunk).join();
        var column = generator.getBaseColumn(1, 1, heights, source.getChunkSource().randomState());
        int core = chart.coreFloorY();
        for (int y = chart.minY(); y <= core + 2; y++) {
            helper.assertTrue(chunk.getBlockState(new BlockPos(1, y, 1)).equals(column.getBlock(y)),
                    "Tiny-body base query disagrees with generated core");
        }
        helper.assertTrue(column.getBlock(core).is(Blocks.BEDROCK) && column.getBlock(core - 1).isAir(),
                "Tiny-body generation filled invalid negative-radius storage");
        var runs = generator.terrainLayers(1, 1);
        for (int y = core - 1; y <= core + 1; y++) {
            int probe = y;
            helper.assertTrue(runs.stream().filter(layer -> layer.bottomY() <= probe && layer.topY() > probe)
                    .findFirst().orElseThrow().state().equals(column.getBlock(y)), "Direct LOD lost the solid core");
        }
        var world = PlanetSurfaceWorlds.ensure(source.getServer(), chart);
        world.getChunk(0, 0);
        world.setBlock(new BlockPos(1, core, 1), Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(!world.setBlock(new BlockPos(1, core - 1, 1), Blocks.STONE.defaultBlockState(), 3),
                "A write was accepted beneath the body's core");
        var query = new AABB(1, core - .5, 1, 1.8, core + .5, 1.8);
        helper.assertTrue(BoundaryCollision.shapes(world, null, query).stream()
                .anyMatch(shape -> shape.bounds().maxY == core), "Removing bedrock made the body center traversable");
        var faceCorner = new AABB(15.8, core - .5, 15.8, 16.2, core + .5, 16.2);
        helper.assertTrue(!BoundaryCollision.shapes(world, null, faceCorner).isEmpty(),
                "Core collision disappeared where two face boundaries meet");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void freshEarthSpawnIsActualLowlandInsideTheStoredBand(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var palette = new java.util.LinkedHashMap<String, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>();
        for (var climate : dev.lexawhatt.astraengine.surface.EarthClimate.values()) {
            palette.put(climate.name().toLowerCase(java.util.Locale.ROOT), registry.getHolderOrThrow(Biomes.PLAINS));
        }
        palette.put("river", registry.getHolderOrThrow(Biomes.RIVER));
        var chart = new dev.lexawhatt.astraengine.surface.EarthChart(CubeFace.POSITIVE_X, 0,
                dev.lexawhatt.astraengine.surface.ContinentalTerrain.CURRENT_VERSION);
        var generator = new dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator(chart,
                new dev.lexawhatt.astraengine.worldgen.EarthBiomeSource(chart.face(), chart.terrainVersion(), palette));
        var spawn = EarthSpawn.select(generator);
        var sample = generator.terrain().sample(chart.normal(spawn.getX() + .5, spawn.getZ() + .5));
        helper.assertTrue(!sample.water() && Math.floor(sample.heightMeters()) + 2 == spawn.getY(),
                "Fresh spawn is not the canonical land surface");
        helper.assertTrue(spawn.getY() < chart.minY() + chart.height() - 100, "Fresh spawn still touches artificial storage ceiling");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void rockyAndIcyColumnsMatchCanonicalFieldAndKeepIndependentBindings(GameTestHelper helper) {
        var level = helper.getLevel();
        var biome = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.THE_VOID);
        var moon = chart("moon");
        var europa = chart("europa");
        for (var chart : List.of(moon, europa)) {
            var generator = new PlanetChunkGenerator(chart, new FixedBiomeSource(biome));
            var height = LevelHeightAccessor.create(chart.minY(), chart.height());
            var chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, height,
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
            generator.fillFromNoise(Blender.empty(), level.getChunkSource().randomState(), level.structureManager(), chunk).join();
            for (int x = 0; x < 16; x += 3) {
                for (int z = 0; z < 16; z += 3) {
                    var sample = generator.terrain().sample(chart.normal(x + .5, z + .5));
                    int firstAir = (int) Math.floor(sample.heightMeters()) - chart.altitudeOriginMeters();
                    helper.assertTrue(chunk.getBlockState(new BlockPos(x, firstAir, z)).isAir(), "Ground height differs from orbital field");
                    helper.assertTrue(!chunk.getBlockState(new BlockPos(x, firstAir - 1, z)).isAir(), "Surface material is missing");
                    helper.assertTrue(chunk.getBlockState(new BlockPos(x, firstAir - 8, z)).is(
                            chart == europa ? Blocks.PACKED_ICE : Blocks.STONE), "Body deep material lost its physical profile");
                }
            }
            var upper = new PlanetChunkGenerator(new PlanetChart(chart.profile(), chart.face(), 25), new FixedBiomeSource(biome));
            helper.assertTrue(upper.terrainLayers(0, 0).stream().allMatch(layer -> layer.state().isAir()),
                    "Upper atmosphere contains an invented floor");
        }
        var server = level.getServer();
        var lunarWorld = PlanetSurfaceWorlds.ensure(server, moon);
        var icyWorld = PlanetSurfaceWorlds.ensure(server, europa);
        helper.assertTrue(lunarWorld != icyWorld && !lunarWorld.dimension().equals(icyWorld.dimension()), "Two bodies share writable host storage");
        helper.assertTrue(PlanetSurfaceWorlds.ensure(server, moon) == lunarWorld, "Returning allocated a replacement world");
        var store = PlanetSurfaceBindings.get(server);
        var saved = store.save(new CompoundTag(), level.registryAccess());
        var decoded = PlanetSurfaceBindings.decode(saved, level.registryAccess());
        helper.assertTrue(decoded.bindings().size() == store.bindings().size(), "Manifest reload lost permanent bindings");
        helper.assertTrue(decoded.binding(moon.dimensionId()).chart().equals(moon)
                && decoded.binding(europa.dimensionId()).chart().equals(europa), "Manifest reload changed a body profile");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void profileWireRoundTripAndStrictContextOwnership(GameTestHelper helper) {
        var payload = new PlanetContextPayload(8, List.of(chart("moon"), chart("europa")));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            PlanetContextPayload.CODEC.encode(buffer, payload);
            helper.assertTrue(PlanetContextPayload.CODEC.decode(buffer).equals(payload), "Planet context changed on wire");
            buffer.clear(); buffer.writeVarInt(41);
            try { CubeStorageCharts.read(buffer); helper.assertTrue(false, "Unknown chart tag was accepted"); }
            catch (IllegalArgumentException expected) { }
        } finally { buffer.release(); }
        try { new PlanetContextPayload(1, List.of(chart("moon"), chart("moon")));
            helper.assertTrue(false, "Duplicate dimension owner was accepted"); }
        catch (IllegalArgumentException expected) { }
        helper.succeed();
    }

    static PlanetChart chart(String bodyId) {
        var system = CosmosGenerator.sol();
        var profile = SolidPlanetProfile.create(system, system.bodies().stream()
                .filter(body -> body.id().equals(bodyId)).findFirst().orElseThrow()).orElseThrow();
        return new PlanetChart(profile, CubeFace.POSITIVE_X, 0);
    }
}
