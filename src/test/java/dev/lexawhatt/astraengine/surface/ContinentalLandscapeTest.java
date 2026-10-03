package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContinentalLandscapeTest {
    @Test
    void shoreMaterialsAndWaterDatumAgreeWithTheGeneratedSurface() {
        var shore = new GeographicPosition(.06042640098612123, 2.2251276299502054, 40);
        var chart = EarthChart.owner(shore, 2).orElseThrow();
        var point = chart.resolve(shore).orElseThrow();
        var terrain = new ContinentalTerrain(2, ContinentalTerrain.SEED);
        assertEquals(EarthClimate.BEACH, EarthClimate.at(terrain.sample(chart.normal(point.x(), point.z()))));
        var sand = ContinentalLandscape.bake(chart, point.x(), point.z(), () -> false);
        assertEquals(2, sand.position(0).y(), 1e-5);
        assertTrue(sand.color(0).x() > sand.color(0).y() && sand.color(0).y() > sand.color(0).z());
        double oceanX = point.x() + Math.sqrt(.5) * 20000;
        double oceanZ = point.z() + Math.sqrt(.5) * 20000;
        assertTrue(terrain.sample(chart.normal(oceanX, oceanZ)).heightMeters() < 0);
        var ocean = ContinentalLandscape.bake(chart, oceanX, oceanZ, () -> false);
        assertEquals(-1.0 / 9.0, ocean.position(0).y(), 1e-6);
        assertTrue(ocean.color(0).z() > ocean.color(0).y() && ocean.color(0).y() > ocean.color(0).x());
    }

    @Test
    void localJoinAndFarFieldPreserveTheirCoordinateContractsOnEveryFace() {
        for (CubeFace face : CubeFace.values()) {
            EarthChart chart = new EarthChart(face, 0, 2);
            for (double x : new double[] {0, EarthChart.RADIUS_METERS * .99}) {
                var frame = chart.tangentFrame(x, 0, 0);
                var direction = chart.normal(x + 20, 15);
                var near = ContinentalLandscape.project(chart, x, 0, frame, direction, 700);
                assertEquals(20, near.x(), 2e-8);
                assertEquals(700, near.y(), 1e-9);
                assertEquals(15, near.z(), 2e-8);
                var distant = chart.normal(x - 300_000, 400_000);
                var expected = frame.toLocalPoint(distant.multiply(EarthChart.RADIUS_METERS + 3500));
                assertTrue(expected.distance(ContinentalLandscape.project(chart, x, 0, frame, distant, 3500)) < 1e-8);
            }
        }
        double previous = 0;
        for (int i = 0; i < ContinentalLandscape.RINGS; i++) {
            assertTrue(ContinentalLandscape.ringRadius(i) > previous);
            previous = ContinentalLandscape.ringRadius(i);
        }
        assertEquals(2_000_000, previous, 1e-8);
    }

    @Test
    void cornerJoinCannotFoldBackIntoAnInteriorHorizon() {
        for (CubeFace face : CubeFace.values()) {
            for (int corner = 0; corner < 4; corner++) {
                var chart = new EarthChart(face, 0, 3);
                double x = Math.copySign(EarthChart.RADIUS_METERS - 4, (corner & 1) == 0 ? -1 : 1);
                double z = Math.copySign(EarthChart.RADIUS_METERS - 4, (corner & 2) == 0 ? -1 : 1);
                var frame = chart.tangentFrame(x, z, 0);
                for (int bearing = 0; bearing < 64; bearing++) {
                    double angle = bearing * Math.PI / 32;
                    var outward = frame.xAxis().multiply(Math.cos(angle)).add(frame.zAxis().multiply(Math.sin(angle)));
                    double previous = 0;
                    for (int step = 0; step <= 256; step++) {
                        double distance = 256 * Math.pow(256, step / 256.0);
                        var direction = frame.upAxis().multiply(EarthChart.RADIUS_METERS)
                                .add(outward.multiply(distance)).normalized();
                        var projected = ContinentalLandscape.project(chart, x, z, frame, direction, 0);
                        double radial = projected.x() * Math.cos(angle) + projected.z() * Math.sin(angle);
                        assertTrue(radial > previous, "Folded radial strip at face=" + face + " corner=" + corner
                                + " bearing=" + bearing + " distance=" + distance);
                        previous = radial;
                    }
                }
            }
        }
    }

    @Test
    void bakedMeshIsFiniteBoundedAndManifoldAwayFromTheOuterBoundary() {
        var mesh = ContinentalLandscape.bake(new EarthChart(CubeFace.POSITIVE_X, 0, 2), 0, 0, () -> false);
        assertEquals(1 + ContinentalLandscape.RINGS * ContinentalLandscape.SECTORS, mesh.vertexCount());
        int[] uses = new int[mesh.vertexCount()];
        var edges = new java.util.HashMap<Long, Integer>();
        for (int i = 0; i < mesh.indexCount(); i += 3) {
            for (int side = 0; side < 3; side++) {
                int a = mesh.vertexIndex(i + side), b = mesh.vertexIndex(i + (side + 1) % 3);
                long key = (long) Math.min(a, b) << 32 | Math.max(a, b);
                edges.merge(key, 1, Integer::sum);
            }
        }
        int outerBoundary = 0;
        for (var edge : edges.entrySet()) {
            if (edge.getValue() == 1) {
                int a = (int) (edge.getKey() >>> 32), b = edge.getKey().intValue();
                assertTrue(a >= mesh.vertexCount() - ContinentalLandscape.SECTORS);
                assertTrue(b >= mesh.vertexCount() - ContinentalLandscape.SECTORS);
                outerBoundary++;
            } else { assertEquals(2, edge.getValue()); }
        }
        assertEquals(ContinentalLandscape.SECTORS, outerBoundary);
        for (int i = 0; i < mesh.indexCount(); i++) {
            int vertex = mesh.vertexIndex(i);
            assertTrue(vertex >= 0 && vertex < mesh.vertexCount()); uses[vertex]++;
        }
        for (int i = 0; i < mesh.vertexCount(); i++) {
            assertTrue(uses[i] >= 2);
            assertTrue(Double.isFinite(mesh.position(i).length()));
            assertEquals(1, mesh.normal(i).length(), 1e-6);
            SpaceVector color = mesh.color(i);
            assertTrue(color.x() >= 0 && color.x() <= 1 && color.y() >= 0 && color.y() <= 1 && color.z() >= 0 && color.z() <= 1);
        }
        var terrain = new ContinentalTerrain(2, ContinentalTerrain.SEED);
        assertEquals(Math.floor(terrain.sample(new SpaceVector(1, 0, 0)).heightMeters()), mesh.position(0).y());
        assertThrows(CancellationException.class, () -> ContinentalLandscape.bake((EarthChart) mesh.chart(), 0, 0, () -> true));
    }
}
