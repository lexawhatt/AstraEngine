package dev.lexawhatt.astraengine.verification;

import com.mojang.authlib.GameProfile;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.ChartCollisionProjection;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.EarthChartTransform;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.function.Supplier;
import net.minecraft.world.phys.AABB;


@PrefixGameTestTemplate(false)
public final class ChartCollisionGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void fallingAcrossBandStopsOnActualNeighborPlatformBeforeRebase(GameTestHelper helper) {
        var base = SolidPlanetGameTests.chart("moon");
        var lowerChart = new PlanetChart(base.profile(), base.face(), 6);
        var upperChart = new PlanetChart(base.profile(), base.face(), 7);
        var server = helper.getLevel().getServer();
        var lower = PlanetSurfaceWorlds.ensure(server, lowerChart);
        var upper = PlanetSurfaceWorlds.ensure(server, upperChart);
        lower.getChunk(4, 4); upper.getChunk(4, 4);
        var block = new BlockPos(72, 2029, 72);
        lower.setBlock(block, Blocks.RED_CONCRETE.defaultBlockState(), 3);
        var player = new FakePlayer(upper,
                new GameProfile(UUID.randomUUID(), "BandCollision"));
        player.setPos(72.5, -2028.5, 72.5);
        player.noPhysics = false;
        player.move(MoverType.SELF, new Vec3(0, -8, 0));
        helper.assertTrue(Math.abs(player.getY() + 2034) < 1e-8,
                "Host movement passed through a real block below its current storage band");
        helper.assertTrue(!upper.noCollision(null, new AABB(72.1, -2034.9, 72.1, 72.9, -2034.1, 72.9)),
                "Host intersection query did not observe the same neighboring collision");
        helper.assertTrue(lower.getBlockState(block).is(Blocks.RED_CONCRETE),
                "Neighbor collision mutated its canonical platform");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void bandsRetainExactStairAndSlabVolumes(GameTestHelper helper) {
        var source = new EarthChart(CubeFace.POSITIVE_X, 2, 3);
        var target = new EarthChart(CubeFace.POSITIVE_X, 3, 3);
        for (AABB shape : List.of(new AABB(17, 2031, 25, 18, 2031.5, 26),
                new AABB(17, 2031.5, 25.5, 18, 2032, 26))) {
            assertEquals(List.of(shape.move(0, -EarthChart.HEIGHT, 0)), ChartCollisionProjection.boxes(source, target, shape));
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 200)
    public static void everyFaceEdgeAndCornerConservativelyCoversActualGeometryWithBoundedExcess(GameTestHelper helper) {
        double r = EarthChart.RADIUS_METERS;
        for (CubeFace face : CubeFace.values()) {
            var source = new EarthChart(face, 0, 3);
            for (double z : new double[]{0, r - 2, -r + 2}) {
                for (int sign : new int[]{-1, 1}) {
                    var target = source.chart(CubeFace.containing(source.normal(sign * (r + 1), z)), 0).orElseThrow();
                    var original = new AABB(sign > 0 ? r - 1 : -r, 12, z, sign > 0 ? r : -r + 1, 12.5, z + .5);
                    var boxes = ChartCollisionProjection.boxes(source, target, original);
                    var transform = new EarthChartTransform(source, target);
                    assertTrue(boxes.size() <= 192);
                    for (int x = 0; x <= 20; x++) {
                        for (int zi = 0; zi <= 20; zi++) {
                            var mapped = transform.position(new SpaceVector(original.minX + x / 20.0,
                                    12.25, original.minZ + zi / 40.0));
                            assertTrue(boxes.stream().anyMatch(box -> closedContains(box, mapped)),
                                    () -> "Missing neighbor collision on " + source + " -> " + target + " at " + mapped);
                        }
                    }
                    var polygon = List.of(transform.position(new SpaceVector(original.minX, 12, original.minZ)),
                            transform.position(new SpaceVector(original.maxX, 12, original.minZ)),
                            transform.position(new SpaceVector(original.maxX, 12, original.maxZ)),
                            transform.position(new SpaceVector(original.minX, 12, original.maxZ)));
                    for (var box : boxes) {
                        for (double x : new double[]{box.minX, box.maxX}) {
                            for (double zz : new double[]{box.minZ, box.maxZ}) {
                                assertTrue(distance(polygon, x, zz) <= ChartCollisionProjection.MAX_EXCESS_METERS + 3e-9,
                                        () -> "Collision approximation exceeded its physical envelope at " + source + " -> " + target);
                            }
                        }
                    }
                }
            }
        }
        helper.succeed();
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) { throw new AssertionError("Exact band collision changed: " + expected + " != " + actual); }
    }
    private static void assertTrue(boolean value) { assertTrue(value, () -> "Collision projection exceeded its row budget"); }
    private static void assertTrue(boolean value, Supplier<String> message) { if (!value) { throw new AssertionError(message.get()); } }

    private static boolean closedContains(AABB box, SpaceVector p) {
        return p.x() >= box.minX - 2e-9 && p.x() <= box.maxX + 2e-9
                && p.y() >= box.minY && p.y() <= box.maxY && p.z() >= box.minZ - 2e-9 && p.z() <= box.maxZ + 2e-9;
    }

    private static double distance(List<SpaceVector> polygon, double x, double z) {
        boolean positive = false, negative = false;
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double cross = dx * (z - a.z()) - dz * (x - a.x());
            positive |= cross > 1e-9; negative |= cross < -1e-9;
            double t = Math.clamp(((x - a.x()) * dx + (z - a.z()) * dz) / (dx * dx + dz * dz), 0, 1);
            distance = Math.min(distance, Math.hypot(x - a.x() - dx * t, z - a.z() - dz * t));
        }
        return positive && negative ? distance : 0;
    }
}
