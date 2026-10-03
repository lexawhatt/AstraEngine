package dev.lexawhatt.astraengine.verification;

import com.mojang.authlib.GameProfile;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.InspectionAirSweep;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.server.PlanetaryFlightGround;
import dev.lexawhatt.astraengine.surface.BodyFixedFrame;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.InspectionFlightStep;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual canonical palettes and host movement; procedural height is never used as a collision oracle. */
@PrefixGameTestTemplate(false)
public final class InspectionAirGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 600)
    public static void occupiedSmallBodyVolumeKeepsPhysicalTerrainSpeedDespiteCompressedChartMeters(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var profile = new SolidPlanetProfile(1, "verify:air-speed", "small", 7, 1000,
                CelestialBody.Kind.ROCKY, 40000, .1, 0);
        var chart = new PlanetChart(profile, CubeFace.POSITIVE_X, 15);
        var level = PlanetSurfaceWorlds.ensure(server, chart);
        var loads = new ArrayList<CompletableFuture<?>>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -4; z <= 4; z++) {
                loads.add(level.getChunkSource().getChunkFuture(x, z, ChunkStatus.FULL, true));
            }
        }
        var all = CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(all.isDone(), "Small-body chunks are not ready"))
                .thenExecute(() -> {
                    all.join();
                    var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "PhysicalSpeed"));
                    player.setPos(8.5, 0, 8.5);
                    // Occupy the current section without obstructing this player's forward path.
                    var offPath = new BlockPos(12, 0, 8); level.setBlock(offPath, Blocks.STONE.defaultBlockState(), 3);
                    var frame = new BodyFixedFrame(SpaceVector.ZERO, FlightOrientation.IDENTITY, chart.radiusMeters());
                    var view = frame.toSystemOrientation(chart.tangentFrame(player.getX(), player.getZ(),
                            chart.altitudeOriginMeters()).toBodyOrientation(FlightOrientation.IDENTITY));
                    try (var connection = new HostPlayerConnection(player); var ground = new PlanetaryFlightGround(player)) {
                        var state = ground.move(player, chart, frame, new FlightDynamics.Input(1, 0, 0, view, false),
                                InspectionFlightStep.AIR_SPEED);
                        helper.assertTrue(state.velocity().length() > 2400 && state.velocity().length() < 2570,
                                "Small-body chart compression bypassed the physical terrain-speed limit: " + state.velocity().length());
                        helper.assertTrue(Math.abs(player.getZ() - 8.5) < 3, "Occupied volume used a fast unproved chart sweep");
                    } finally { level.setBlock(offPath, Blocks.AIR.defaultBlockState(), 3); }
                }).thenSucceed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 600)
    public static void fastAirAscentPreservesActualBuiltCollisionAndRejectsUnknownSections(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var base = SolidPlanetGameTests.chart("moon");
        var chart = new PlanetChart(base.profile(), CubeFace.NEGATIVE_Z, 15);
        var level = PlanetSurfaceWorlds.ensure(server, chart);
        var loads = new ArrayList<CompletableFuture<?>>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                loads.add(level.getChunkSource().getChunkFuture(x, z, ChunkStatus.FULL, true));
            }
        }
        var all = CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(all.isDone(), "Air test chunks are not ready"))
                .thenExecute(() -> {
                    all.join();
                    var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "AirSweep"));
                    player.setPos(8.5, chart.minY() + 100, 8.5);
                    var frame = new BodyFixedFrame(SpaceVector.ZERO, FlightOrientation.IDENTITY, chart.radiusMeters());
                    var view = frame.toSystemOrientation(chart.tangentFrame(player.getX(), player.getZ(),
                            player.getY() + chart.altitudeOriginMeters()).toBodyOrientation(FlightOrientation.fromAngles(0, -90, 0)));
                    var input = new FlightDynamics.Input(1, 0, 0, view, false);
                    var volume = player.getBoundingBox().expandTowards(0, 1000, 0).inflate(.001);
                    helper.assertTrue(InspectionAirSweep.clear(level, chart, volume), "Actual loaded air was not recognized");
                    helper.assertTrue(!InspectionAirSweep.clear(level, chart,
                            new AABB(160_000, 0, 160_000, 160_001, 100, 160_001)), "Unloaded terrain was assumed empty");
                    helper.assertTrue(!InspectionAirSweep.clear(level, chart,
                            new AABB(8, chart.minY() + chart.height() - 2, 8, 9,
                                    chart.minY() + chart.height() + 2, 9)), "Cross-chart volume used the interior fast path");
                    try (var connection = new HostPlayerConnection(player); var ground = new PlanetaryFlightGround(player)) {
                        double start = player.getY();
                        var state = ground.move(player, chart, frame, input, InspectionFlightStep.AIR_SPEED);
                        helper.assertTrue(player.getY() - start > 2000 && state.velocity().length() > 40_000,
                                "Loaded empty ascent retained the old2560m/s cap");
                        player.setPos(8.5, start, 8.5);
                        var obstruction = BlockPos.containing(8.5, start + 64, 8.5);
                        level.setBlock(obstruction, Blocks.RED_CONCRETE.defaultBlockState(), 3);
                        helper.assertTrue(!InspectionAirSweep.clear(level, chart, volume), "A later player build retained a stale empty proof");
                        ground.move(player, chart, frame, input, InspectionFlightStep.AIR_SPEED);
                        helper.assertTrue(player.getBoundingBox().maxY <= obstruction.getY() + .001,
                                "Fast inspection tunnelled through an actual high-altitude build");
                        helper.assertTrue(level.getBlockState(obstruction).is(Blocks.RED_CONCRETE), "Movement changed the obstacle");
                        level.setBlock(obstruction, Blocks.AIR.defaultBlockState(), 3);
                        helper.assertTrue(InspectionAirSweep.clear(level, chart, volume), "Removed obstruction did not restore exact air proof");
                    }
                }).thenSucceed();
    }
}
