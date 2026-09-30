package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanetaryPoseTest {
    private static final double EARTH_RADIUS = 6_371_000;

    @Test
    void velocityMatchesDifferentiatedPositionsAcrossAltitudesLongitudesAndPoles() {
        SpaceVector velocity = new SpaceVector(3.25, -.75, -7.125);
        double stepTicks = .1;
        for (double radius : new double[]{1_737_400, EARTH_RADIUS}) {
            for (double latitude : new double[]{-Math.PI / 2, -.9, 0, Math.PI / 4, Math.PI / 2}) {
                for (double longitude : new double[]{-Math.PI, -.4, 0, 2.7}) {
                    SurfacePatch patch = new SurfacePatch(radius, latitude, longitude, 4096, 64);
                    for (SpaceVector feet : new SpaceVector[]{new SpaceVector(0, 64, 0),
                            new SpaceVector(-3048, -100, 2751), new SpaceVector(radius * .03, radius * .2, -1100)}) {
                        SpaceVector before = patch.toBody(feet.subtract(velocity.multiply(stepTicks)));
                        SpaceVector after = patch.toBody(feet.add(velocity.multiply(stepTicks)));
                        SpaceVector difference = after.subtract(before).multiply(.5 / stepTicks);
                        assertVector(difference, patch.toBodyVelocity(feet, velocity), 3e-8);
                    }
                }
            }
        }
    }

    @Test
    void inverseVelocityMatchesDifferentiatedInversePositions() {
        SurfacePatch patch = new SurfacePatch(EARTH_RADIUS, .7, -2.3, 32768, 0);
        SpaceVector feet = new SpaceVector(28000, 18000, -17000);
        SpaceVector body = patch.toBody(feet);
        SpaceVector velocity = new SpaceVector(5.7, -3.8, 6.1);
        double stepTicks = .1;
        SpaceVector difference = patch.toLocal(body.add(velocity.multiply(stepTicks)))
                .subtract(patch.toLocal(body.subtract(velocity.multiply(stepTicks)))).multiply(.5 / stepTicks);
        assertVector(difference, patch.toLocalVelocity(feet, velocity), 3e-8);
    }

    @Test
    void differentialRetainsAltitudeScaleInsteadOfOnlyRotatingDirections() {
        SurfacePatch patch = new SurfacePatch(EARTH_RADIUS, 0, 0, 4096, 64);
        SpaceVector localVelocity = new SpaceVector(3, -7, 5);
        SpaceVector feet = new SpaceVector(0, EARTH_RADIUS + 64, 0);
        SpaceVector bodyVelocity = patch.toBodyVelocity(feet, localVelocity);
        assertVector(new SpaceVector(-7, -10, -6), bodyVelocity, 1e-12);
        assertTrue(bodyVelocity.distance(patch.toBodyDirection(0, 0, localVelocity)) > 5);
        assertVector(localVelocity, patch.toLocalVelocity(feet, bodyVelocity), 1e-12);
        assertEquals(0, patch.toBodyVelocity(feet, SpaceVector.ZERO).length());
        assertEquals(0, patch.toLocalVelocity(feet, SpaceVector.ZERO).length());
    }

    @Test
    void inverseVelocityRoundTripsWideFiniteRadiiSpeedsAndOffPatchPositions() {
        for (double radius : new double[]{10, 1_737_400, EARTH_RADIUS, 1e12}) {
            for (double latitude : new double[]{-Math.PI / 2, .35, Math.PI / 2}) {
                SurfacePatch patch = new SurfacePatch(radius, latitude, 1.3, 1, 0);
                for (double altitudeFraction : new double[]{-.8, 0, 4, 1e9}) {
                    SpaceVector feet = new SpaceVector(radius * 2.75, radius * altitudeFraction, -radius * .6);
                    for (double scale : new double[]{1e-120, 1, 1e120}) {
                        // A billion-radius altitude makes the Jacobian highly anisotropic; ordinary scales
                        // retain near-machine precision, while this extreme allows its conditioning error.
                        double tolerance = scale * (altitudeFraction > 10 ? 3e-5 : 2e-13);
                        SpaceVector localVelocity = new SpaceVector(3.25, -7.5, 5.125).multiply(scale);
                        SpaceVector bodyVelocity = patch.toBodyVelocity(feet, localVelocity);
                        assertVector(localVelocity, patch.toLocalVelocity(feet, bodyVelocity), tolerance);
                        SpaceVector arbitraryBodyVelocity = new SpaceVector(-1.3, 5.7, 3.9).multiply(scale);
                        assertVector(arbitraryBodyVelocity, patch.toBodyVelocity(feet,
                                patch.toLocalVelocity(feet, arbitraryBodyVelocity)), tolerance);
                    }
                }
            }
        }
    }

    @Test
    void localPosesRebaseThroughSeamsAndBothPolesWithoutChangingBodyPoseOrRoll() {
        SurfacePatch patch = new SurfacePatch(EARTH_RADIUS, Math.PI / 4, 0, 32768, 0);
        PlanetaryTopology topology = new PlanetaryTopology(1, 0, EARTH_RADIUS);
        FlightOrientation orientation = FlightOrientation.fromAngles(137, 123, -79);
        PlanetaryPose expected = PlanetaryPose.fromPatch(patch, new SpaceVector(11.25, 1432.5, -4.75),
                new SpaceVector(3, -5, 7), orientation);
        PlanetaryPose carried = expected;
        for (CubeFace face : CubeFace.values()) {
            PlanetaryTile tile = new PlanetaryTile(1, 0, face, 0, 0);
            for (double u : new double[]{0, .5, 1}) {
                PlanetaryFrame frame = topology.frame(tile, u, .5);
                carried = carried.inFrame(frame).toBody(frame);
                assertPose(expected, carried, 1e-7, 3e-13);
            }
        }
        FlightOrientation restored = patch.toLocalOrientation(11.25, -4.75, carried.bodyOrientation());
        assertVector(orientation.forward(), restored.forward(), 3e-14);
        assertVector(orientation.up(), restored.up(), 3e-14);
    }

    @Test
    void adjacentRotatedFaceRepresentationsShareOnePointAndVelocity() {
        PlanetaryTopology topology = new PlanetaryTopology(1, 12, EARTH_RADIUS);
        PlanetaryTile xTile = new PlanetaryTile(1, 12, CubeFace.POSITIVE_X, 2048, 0);
        TileNeighbor across = topology.neighbor(xTile, TileEdge.TOP);
        PlanetaryFrame before = topology.frame(xTile, .25, 0);
        PlanetaryFrame after = topology.frame(across.tile(), 1, across.crossingFraction(.25));
        PlanetaryPose expected = new PlanetaryPose.LocalPose(new SpaceVector(2, 1300, -4),
                new SpaceVector(.23, -.078, -.12), FlightOrientation.fromAngles(35, 84, 137)).toBody(before);
        PlanetaryPose.LocalPose rebased = expected.inFrame(after);
        assertTrue(rebased.orientation().forward().distance(expected.inFrame(before).orientation().forward()) > .05);
        assertPose(expected, rebased.toBody(after), 3e-9, 3e-14);
    }

    @Test
    void invalidInputsAndUnrepresentableKinematicsRejectWithoutInventedFallbacks() {
        SurfacePatch patch = new SurfacePatch(10, 0, 0, 1, 0);
        SpaceVector valid = new SpaceVector(0, 1, 0);
        SpaceVector extreme = new SpaceVector(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
        for (SpaceVector invalidFeet : new SpaceVector[]{null, new SpaceVector(0, -10, 0),
                new SpaceVector(0, -11, 0), extreme}) {
            assertThrows(IllegalArgumentException.class, () -> patch.toBodyVelocity(invalidFeet, valid));
            assertThrows(IllegalArgumentException.class, () -> patch.toLocalVelocity(invalidFeet, valid));
        }
        for (SpaceVector invalidVelocity : new SpaceVector[]{null, extreme}) {
            assertThrows(IllegalArgumentException.class, () -> patch.toBodyVelocity(valid, invalidVelocity));
            assertThrows(IllegalArgumentException.class, () -> patch.toLocalVelocity(valid, invalidVelocity));
        }
        assertThrows(IllegalArgumentException.class, () -> patch.toBodyVelocity(new SpaceVector(0, 1e300, 0),
                new SpaceVector(1e300, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> PlanetaryPose.fromPatch(null, valid, valid, FlightOrientation.IDENTITY));
        assertThrows(IllegalArgumentException.class,
                () -> PlanetaryPose.fromPatch(patch, null, valid, FlightOrientation.IDENTITY));
        assertThrows(IllegalArgumentException.class,
                () -> PlanetaryPose.fromPatch(patch, valid, valid, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanetaryPose(SpaceVector.ZERO, valid, FlightOrientation.IDENTITY));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanetaryPose(valid, extreme, FlightOrientation.IDENTITY));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanetaryPose.LocalPose(extreme, valid, FlightOrientation.IDENTITY));
        PlanetaryPose pose = new PlanetaryPose(valid, valid, FlightOrientation.IDENTITY);
        assertThrows(IllegalArgumentException.class, () -> pose.inFrame(null));
        PlanetaryPose.LocalPose local = new PlanetaryPose.LocalPose(SpaceVector.ZERO, valid, FlightOrientation.IDENTITY);
        assertThrows(IllegalArgumentException.class, () -> local.toBody(null));
    }

    private static void assertPose(PlanetaryPose expected, PlanetaryPose actual, double positionTolerance,
            double velocityTolerance) {
        assertVector(expected.bodyPositionMeters(), actual.bodyPositionMeters(), positionTolerance);
        assertVector(expected.bodyVelocityMetersPerTick(), actual.bodyVelocityMetersPerTick(), velocityTolerance);
        assertVector(expected.bodyOrientation().forward(), actual.bodyOrientation().forward(), 3e-14);
        assertVector(expected.bodyOrientation().up(), actual.bodyOrientation().up(), 3e-14);
    }

    private static void assertVector(SpaceVector expected, SpaceVector actual, double tolerance) {
        assertTrue(expected.distance(actual) <= tolerance,
                () -> "Expected " + expected + " but got " + actual + ", error " + expected.distance(actual));
    }
}
