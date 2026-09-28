package dev.lexawhatt.astraengine.cosmos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full camera orientation remains orthonormal across poles, roll, interpolation, and encoding boundaries. */
class FlightOrientationTest {
    @Test
    void idleRotationPreservesExactPersistedComponentsWithoutRenormalizing() {
        FlightOrientation saved = new FlightOrientation(0, 0, 0, 1 + 1e-8);
        FlightOrientation current = saved;
        for (int frame = 0; frame < 1000; frame++) {
            current = current.rotateLocal(0, -0.0, 0);
            assertSame(saved, current);
        }
        assertThrows(IllegalArgumentException.class, () -> saved.rotateLocal(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> saved.rotateLocal(0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> saved.rotateLocal(0, 0, Double.NEGATIVE_INFINITY));
    }

    @Test
    void localBasisStaysOrthonormalThroughRepeatedPoleCrossingsAndRoll() {
        FlightOrientation orientation = FlightOrientation.IDENTITY;
        for (int frame = 0; frame < 5000; frame++) {
            orientation = orientation.rotateLocal(0.31, 1.7, 0.45);
            assertEquals(1, orientation.forward().length(), 1e-12);
            assertEquals(1, orientation.left().length(), 1e-12);
            assertEquals(1, orientation.up().length(), 1e-12);
            assertEquals(0, orientation.forward().dot(orientation.left()), 1e-12);
            assertEquals(0, orientation.left().dot(orientation.up()), 1e-12);
            assertEquals(0, orientation.up().dot(orientation.forward()), 1e-12);
        }
    }

    @Test
    void hostAnglesDescribeEquivalentViewBeyondBothPolesIncludingRoll() {
        for (double yaw : new double[]{-180, -67, 0, 90, 173}) {
            for (double pitch : new double[]{-270, -180, -90.001, -90, -89.999, 0, 89.999, 90, 90.001, 180, 270}) {
                for (double roll : new double[]{-155, 0, 37, 90, 180}) {
                    FlightOrientation original = FlightOrientation.fromAngles(yaw, pitch, roll);
                    FlightOrientation host = FlightOrientation.fromAngles(original.yaw(), original.pitch(), original.roll());
                    assertTrue(original.forward().distance(host.forward()) < 1e-6);
                    assertTrue(original.up().distance(host.up()) < 1e-6);
                    assertTrue(original.left().distance(host.left()) < 1e-6);
                }
            }
        }
        assertTrue(FlightOrientation.fromAngles(0, 91, 0).forward().z() < 0);
    }

    @Test
    void localYawFollowsRolledCameraInsteadOfWorldVertical() {
        FlightOrientation rolled = FlightOrientation.fromAngles(0, 0, 90);
        FlightOrientation turned = rolled.rotateLocal(90, 0, 0);
        assertEquals(0, turned.forward().x(), 1e-12);
        assertEquals(-1, turned.forward().y(), 1e-12);
        assertEquals(0, turned.forward().z(), 1e-12);
    }

    @Test
    void interpolationTakesShortestArcAndIsInvariantToAntipodalEncoding() {
        FlightOrientation from = FlightOrientation.fromAngles(179, 13, 45);
        FlightOrientation to = FlightOrientation.fromAngles(-179, 13, 45);
        FlightOrientation midpoint = from.interpolate(to, 0.5);
        assertTrue(from.forward().dot(midpoint.forward()) > 0.999);
        FlightOrientation negative = new FlightOrientation(-from.x(), -from.y(), -from.z(), -from.w());
        assertTrue(from.forward().distance(from.interpolate(negative, 0.5).forward()) < 1e-12);
        assertEquals(from, from.interpolate(to, 0));
        assertEquals(to, from.interpolate(to, 1));
    }

    @Test
    void strictEncodingRejectsMalformedValuesAndPreservesAcceptedComponents() {
        for (double[] values : new double[][]{{0, 0, 0, 0}, {0, 0, 0, 2}, {Double.NaN, 0, 0, 1},
                {0, 0, Double.POSITIVE_INFINITY, 1}, {1e200, 0, 0, 1}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new FlightOrientation(values[0], values[1], values[2], values[3]));
        }
        double accepted = 1 + 1e-8;
        assertEquals(accepted, new FlightOrientation(0, 0, 0, accepted).w());
        assertThrows(IllegalArgumentException.class, () -> FlightOrientation.normalized(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> FlightOrientation.fromAngles(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> FlightOrientation.IDENTITY.interpolate(null, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> FlightOrientation.IDENTITY.interpolate(FlightOrientation.IDENTITY, Double.NaN));
    }
}
