package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EarthChartRebaseTest {
    private static final SpaceVector MOTION = new SpaceVector(.12, -.08, .31);
    private static final FlightOrientation VIEW = FlightOrientation.fromAngles(41, -27, 13);

    @Test
    void everyFaceEdgeCornerAndBandRetainsThePhysicalPoseAndDifferential() {
        double radius = EarthChart.RADIUS_METERS;
        int checked = 0;
        for (EarthChart source : EarthChart.all(ContinentalTerrain.CURRENT_VERSION)) {
            for (double x : new double[]{-radius - .25, -radius, -radius + .25, 0, radius - .25, radius, radius + .25}) {
                for (double z : new double[]{-radius - .25, -radius, 0, radius, radius + .25}) {
                    for (double y : new double[]{EarthChart.MIN_Y - .25, 0, EarthChart.MIN_Y + EarthChart.HEIGHT + .25}) {
                        var feet = new SpaceVector(x, y, z);
                        var result = EarthChartRebase.resolve(source, feet, MOTION, VIEW);
                        if ((source.band() == EarthChart.MIN_BAND && y < EarthChart.MIN_Y)
                                || (source.band() == EarthChart.MAX_BAND && y >= EarthChart.MIN_Y + EarthChart.HEIGHT)) {
                            assertTrue(result.isEmpty()); continue;
                        }
                        EarthChartRebase rebased = result.orElseThrow();
                        assertEquals(source.terrainVersion(), rebased.chart().terrainVersion());
                        assertTrue(rebased.chart().contains(rebased.feet()));
                        var pose = rebased.chart().pose(rebased.feet(), rebased.velocity(), rebased.orientation());
                        assertTrue(point(source, feet).distance(pose.bodyPositionMeters()) < 5e-9);
                        double epsilon = .02;
                        SpaceVector numericalVelocity = point(source, feet.add(MOTION.multiply(epsilon)))
                                .subtract(point(source, feet.subtract(MOTION.multiply(epsilon)))).multiply(.5 / epsilon);
                        assertTrue(numericalVelocity.distance(pose.bodyVelocityMetersPerTick()) < 2e-7);
                        var physicalView = source.tangentFrame(x, z, y + source.altitudeOriginMeters()).toBodyOrientation(VIEW);
                        assertTrue(physicalView.forward().distance(pose.bodyOrientation().forward()) < 3e-15);
                        assertTrue(physicalView.up().distance(pose.bodyOrientation().up()) < 3e-15);
                        var inverse = new EarthChartTransform(rebased.chart(), source);
                        assertTrue(inverse.position(rebased.feet()).distance(feet) < 4e-9);
                        assertTrue(inverse.velocity(rebased.feet(), rebased.velocity()).distance(MOTION) < 1e-12);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 3000);
    }

    @Test
    void altitudeTiesDoNotRoundIntoAnotherBandAndOrdinaryMotionDoesNotDrift() {
        for (EarthChart chart : EarthChart.ALL) {
            var feet = new SpaceVector(23.4, Math.nextDown((double) EarthChart.MIN_Y + EarthChart.HEIGHT), -12.1);
            var unchanged = EarthChartRebase.resolve(chart, feet, MOTION, VIEW).orElseThrow();
            assertSame(feet, unchanged.feet()); assertSame(MOTION, unchanged.velocity()); assertSame(VIEW, unchanged.orientation());
            for (double y : new double[]{Math.nextDown((double) EarthChart.MIN_Y), (double) EarthChart.MIN_Y + EarthChart.HEIGHT}) {
                var result = EarthChartRebase.resolve(chart, new SpaceVector(0, y, 0), MOTION, VIEW);
                int expectedBand = chart.band() + (y < EarthChart.MIN_Y ? -1 : 1);
                if (expectedBand < EarthChart.MIN_BAND || expectedBand > EarthChart.MAX_BAND) { assertTrue(result.isEmpty()); }
                else {
                    var changed = result.orElseThrow();
                    assertEquals(expectedBand, changed.chart().band());
                    assertTrue(changed.chart().contains(changed.feet()));
                    assertSame(MOTION, changed.velocity()); assertSame(VIEW, changed.orientation());
                }
            }
        }
    }

    @Test
    void largeJumpsAndMissingInputsCannotInventAStorageDestination() {
        var source = new EarthChart(CubeFace.POSITIVE_X, 0);
        for (SpaceVector feet : new SpaceVector[]{new SpaceVector(7e6, 0, 0), new SpaceVector(0, 2200, 0),
                new SpaceVector(0, -2200, 0), new SpaceVector(Double.MAX_VALUE, 0, 0)}) {
            assertTrue(EarthChartRebase.resolve(source, feet, MOTION, VIEW).isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> EarthChartRebase.resolve(null, SpaceVector.ZERO, MOTION, VIEW));
        assertThrows(IllegalArgumentException.class, () -> EarthChartRebase.resolve(source, SpaceVector.ZERO, null, VIEW));
        var opposite = new EarthChart(CubeFace.NEGATIVE_X, 0);
        assertThrows(IllegalArgumentException.class, () -> new EarthChartTransform(source, opposite).position(SpaceVector.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new EarthChartTransform(source,
                new EarthChart(CubeFace.POSITIVE_X, 0, ContinentalTerrain.CURRENT_VERSION)));
    }

    private static SpaceVector point(EarthChart source, SpaceVector feet) {
        return source.normal(feet.x(), feet.z()).multiply(EarthChart.RADIUS_METERS + source.altitudeOriginMeters() + feet.y());
    }
}
