package dev.lexawhatt.astraengine.surface;

import static org.junit.jupiter.api.Assertions.*;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

class PlanetarySpaceBoundaryTest {
    @Test void largeSweepsCannotTunnelThroughEitherSideOfThePlanet() {
        var frame = new BodyFixedFrame(new SpaceVector(149_597_870_700.0, 100, -8000), FlightOrientation.IDENTITY, 6_371_000);
        var start = frame.centerMeters().add(new SpaceVector(0, 0, -30_000_000));
        var end = frame.centerMeters().add(new SpaceVector(0, 0, 30_000_000));
        var hit = PlanetarySpaceBoundary.entry(frame, start, end, 1.62).orElseThrow();
        assertEquals((30_000_000 - 6_471_001.62) / 60_000_000, hit.fraction(), 1e-12);
        assertEquals(99_999.99, hit.feet().altitudeMeters());
        assertEquals(Math.PI / 2, hit.feet().longitudeRadians());
        assertTrue(PlanetarySpaceBoundary.entry(frame, start, start.multiply(2).subtract(frame.centerMeters()), 1.62).isEmpty());
    }

    @Test void tangentAndMissDoNotTriggerForcedEntry() {
        var frame = new BodyFixedFrame(SpaceVector.ZERO, FlightOrientation.IDENTITY, 1000);
        assertTrue(PlanetarySpaceBoundary.entry(frame, new SpaceVector(101000, 0, -200000),
                new SpaceVector(101000, 0, 200000), 0).isEmpty());
        assertTrue(PlanetarySpaceBoundary.entry(frame, new SpaceVector(201000, 0, -200000),
                new SpaceVector(201000, 0, 200000), 0).isEmpty());
    }

    @Test void chartVelocityInverseRetainsPhysicalMotionAtFacesPolesAndCorners() {
        for (var face : CubeFace.values()) {
            var chart = new EarthChart(face, 25, 3);
            for (double x : new double[]{0, EarthChart.RADIUS_METERS * .9999, -EarthChart.RADIUS_METERS * .9999}) {
                for (double z : new double[]{0, EarthChart.RADIUS_METERS * .9999}) {
                    var feet = new SpaceVector(x, -1600, z);
                    var velocity = new SpaceVector(17, -43, 26);
                    var actual = chart.pose(feet, chart.localVelocity(feet, velocity), FlightOrientation.IDENTITY);
                    assertTrue(actual.bodyVelocityMetersPerTick().distance(velocity) < 1e-10);
                }
            }
        }
    }
}
