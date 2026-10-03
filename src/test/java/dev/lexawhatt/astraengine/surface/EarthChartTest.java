package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarthChartTest {
    @Test
    void dimensionLookupPreservesVersionsAndExactSavedIdentities() {
        for (int version = 1; version <= 3; version++) {
            for (var face : CubeFace.values()) {
                for (int band = EarthChart.MIN_BAND; band <= EarthChart.MAX_BAND; band++) {
                    var chart = new EarthChart(face, band, version);
                    assertEquals(chart, EarthChart.forDimension(chart.dimensionId(), version).orElseThrow());
                }
            }
        }
        assertTrue(EarthChart.forDimension("astraengine:earth/px/above_00", 3).isEmpty());
        assertTrue(EarthChart.forDimension("astraengine:earth/px/above_0", 3).isEmpty());
        assertTrue(EarthChart.forDimension("astraengine:earth/py/above_26", 3).isEmpty());
        assertTrue(EarthChart.forDimension("other:earth/py/above_1", 3).isEmpty());
    }

    @Test
    void everyPhysicalAddressHasOnePersistentStorageOwner() {
        assertEquals(36, EarthChart.ALL.size());
        assertEquals(36, EarthChart.ALL.stream().map(EarthChart::dimensionId).distinct().count());
        assertEquals(1, EarthChart.ALL.stream().filter(chart -> chart.dimensionId().equals("minecraft:overworld")).count());
        var random = new Random(781921);
        for (int i = 0; i < 12000; i++) {
            var geographic = new GeographicPosition(Math.asin(random.nextDouble() * 2 - 1),
                    random.nextDouble() * Math.PI * 2 - Math.PI, -10160 + random.nextDouble() * 24384);
            var owner = EarthChart.owner(geographic).orElseThrow();
            var feet = owner.resolve(geographic).orElseThrow();
            assertTrue(owner.contains(feet));
            var restored = owner.geographic(feet);
            assertEquals(geographic.altitudeMeters(), restored.altitudeMeters(), 2e-12);
            assertTrue(geographic.normal().distance(restored.normal()) < 1e-14);
            assertEquals(1, EarthChart.ALL.stream().filter(chart -> chart.resolve(geographic).isPresent()).count());
        }
        assertTrue(EarthChart.owner(new GeographicPosition(0, 0, -10160.01)).isEmpty());
        assertEquals(4, EarthChart.owner(new GeographicPosition(0, 0, 14224)).orElseThrow().band());
        assertEquals(25, EarthChart.owner(new GeographicPosition(0, 0, 100000)).orElseThrow().band());
        assertTrue(EarthChart.owner(new GeographicPosition(0, 0, 103632)).isEmpty());
    }

    @Test
    void altitudeBoundariesDoNotDuplicateOrCompressStorage() {
        for (int band = EarthChart.MIN_BAND; band <= EarthChart.MAX_BAND; band++) {
            double lower = EarthChart.MIN_Y + band * EarthChart.HEIGHT;
            var address = new GeographicPosition(.42, -2.1, lower);
            var chart = EarthChart.owner(address).orElseThrow();
            assertEquals(band, chart.band());
            assertEquals(EarthChart.MIN_Y, chart.resolve(address).orElseThrow().y(), 0);
            assertEquals(band, EarthChart.owner(new GeographicPosition(.42, -2.1, lower + .125)).orElseThrow().band());
            if (band > EarthChart.MIN_BAND) {
                assertEquals(band - 1, EarthChart.owner(new GeographicPosition(.42, -2.1, lower - .00001)).orElseThrow().band());
                assertEquals(band - 1, EarthChart.owner(new GeographicPosition(.42, -2.1, Math.nextDown(lower))).orElseThrow().band());
            }
        }
    }

    @Test
    void polesDatelineEdgesAndCornersResolveWithoutAWindowCenterFallback() {
        for (SpaceVector direction : new SpaceVector[]{new SpaceVector(0, 1, 0), new SpaceVector(0, -1, 0),
                new SpaceVector(-1, 0, 0), new SpaceVector(1, 1, 0), new SpaceVector(1, -1, 1),
                new SpaceVector(-1, -1, -1), new SpaceVector(1, 0, 1.000000001)}) {
            var normal = GeographicPosition.fromBody(direction, 1);
            var address = new GeographicPosition(normal.latitudeRadians(), normal.longitudeRadians(), 8650.25);
            var chart = EarthChart.owner(address).orElseThrow();
            var feet = chart.resolve(address).orElseThrow();
            assertTrue(chart.contains(feet), () -> "Unowned edge inverse: " + address + " -> " + feet);
            assertTrue(chart.geographic(feet).normal().distance(address.normal()) < 2e-15);
            assertEquals(8650.25, chart.geographic(feet).altitudeMeters(), 0);
        }
    }

    @Test
    void poseVelocityIsTheDifferentialOfTheActualProjection() {
        for (var chart : EarthChart.ALL) {
            for (double x : new double[]{0, 19382, -6_000_000}) {
                var feet = new SpaceVector(x, 42, 213876);
                var velocity = new SpaceVector(.125, -.073, .21);
                var pose = chart.pose(feet, velocity, FlightOrientation.fromAngles(43, -21, 12));
                double step = .02;
                var previous = chart.geographic(feet.subtract(velocity.multiply(step))).toBody(EarthChart.RADIUS_METERS);
                var next = chart.geographic(feet.add(velocity.multiply(step))).toBody(EarthChart.RADIUS_METERS);
                assertTrue(next.subtract(previous).multiply(.5 / step).distance(pose.bodyVelocityMetersPerTick()) < 2e-7);
                assertTrue(pose.bodyPositionMeters().distance(chart.geographic(feet).toBody(EarthChart.RADIUS_METERS)) < 3e-9);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new EarthChart(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new EarthChart(CubeFace.POSITIVE_X, 26));
        assertThrows(IllegalArgumentException.class, () -> EarthChart.owner(null));
        var chart = new EarthChart(CubeFace.POSITIVE_X, 0);
        assertFalse(chart.contains(new SpaceVector(7e6, 10, 0)));
        assertThrows(IllegalArgumentException.class, () -> chart.normal(Double.NaN, 0));
    }
}
