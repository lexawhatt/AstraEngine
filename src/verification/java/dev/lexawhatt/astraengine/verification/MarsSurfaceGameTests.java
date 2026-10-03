package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.PlanetSurfaceBindings;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import dev.lexawhatt.astraengine.surface.SolidPlanetTerrain;
import dev.lexawhatt.astraengine.worldgen.PlanetChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.PlanetProfileCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual host material columns and saved Mars v1/v2 identity; no historical chunk is migrated. */
@PrefixGameTestTemplate(false)
public final class MarsSurfaceGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void actualMarsMaterialsMatchDirectLodAndSavedLegacyWinsAllFutureCharts(GameTestHelper helper) {
        var source = helper.getLevel(); var server = source.getServer();
        var sol = ExplorationCatalog.get(server).system("sol");
        var body = sol.bodies().stream().filter(value -> value.id().equals("mars")).findFirst().orElseThrow();
        var current = SolidPlanetProfile.create(sol, body).orElseThrow();
        var old = new SolidPlanetProfile(1, current.systemId(), current.bodyId(), current.seed(), current.radiusMeters(),
                current.kind(), current.rotationSeconds(), current.axialTiltRadians(), current.atmosphereStrength());
        var registry = source.registryAccess().registryOrThrow(Registries.BIOME);
        var biome = new FixedBiomeSource(registry.getHolderOrThrow(Biomes.THE_VOID));
        PlanetChart legacyChart = null;
        for (var profile : new SolidPlanetProfile[] {old, current}) {
            var sample = new SolidPlanetTerrain(profile).sample(new dev.lexawhatt.astraengine.cosmos.SpaceVector(1, 0, 0));
            var chart = PlanetChart.owner(profile, new GeographicPosition(0, 0, sample.heightMeters())).orElseThrow();
            if (profile.version() == 1) { legacyChart = chart; }
            var generator = new PlanetChunkGenerator(chart, biome);
            var height = LevelHeightAccessor.create(chart.minY(), chart.height());
            var chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, height, registry, null);
            generator.fillFromNoise(Blender.empty(), source.getChunkSource().randomState(), source.structureManager(), chunk).join();
            var actualSample = generator.terrain().sample(chart.normal(8.5, 8.5));
            int y = (int) Math.floor(actualSample.heightMeters()) - chart.altitudeOriginMeters() - 1;
            var actual = chunk.getBlockState(new BlockPos(8, y, 8));
            helper.assertTrue(profile.version() == 1 ? actual.is(Blocks.GRAVEL) || actual.is(Blocks.STONE)
                    : actual.is(Blocks.RED_SAND) || actual.is(Blocks.TERRACOTTA), "Mars host ground lost versioned material");
            helper.assertTrue(generator.terrainLayers(8, 8).stream().filter(run -> run.bottomY() <= y && run.topY() > y)
                    .findFirst().orElseThrow().state().equals(actual), "Mars DH field disagrees with actual generated ground");
            var encoded = PlanetProfileCodec.CODEC.encodeStart(NbtOps.INSTANCE, profile).getOrThrow();
            helper.assertTrue(PlanetProfileCodec.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow().equals(profile),
                    "Saved Mars profile changed its explicit material version");
        }
        var world = PlanetSurfaceWorlds.ensure(server, legacyChart);
        helper.assertTrue(PlanetSurfaceWorlds.profile(server, sol, body).orElseThrow().equals(old),
                "Current defaults replaced the saved Mars v1 realization");
        var future = new PlanetChart(PlanetSurfaceWorlds.profile(server, sol, body).orElseThrow(), CubeFace.NEGATIVE_Z, 20);
        PlanetSurfaceWorlds.ensure(server, future);
        var manifest = PlanetSurfaceBindings.get(server);
        var restored = PlanetSurfaceBindings.decode(manifest.save(new CompoundTag(), world.registryAccess()), world.registryAccess());
        helper.assertTrue(restored.profile("sol", "mars").orElseThrow().equals(old)
                && ((PlanetChart) restored.binding(future.dimensionId()).chart()).profile().equals(old),
                "Mars restart or new chart silently changed historical terrain materials");
        helper.succeed();
    }
}
