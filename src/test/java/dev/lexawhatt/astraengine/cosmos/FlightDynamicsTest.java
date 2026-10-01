package dev.lexawhatt.astraengine.cosmos;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for free-camera units, local translation, immediate stop, and swept collision. */
class FlightDynamicsTest {
    private static final SpaceVector ZERO = new SpaceVector(0, 0, 0);

    @Test
    void movementUsesCameraYawAndSelectedContinuousSpeed() {
        FlightDynamics.State initial = new FlightDynamics.State(ZERO, ZERO);
        FlightDynamics.State north = FlightDynamics.step(initial,
                new FlightDynamics.Input(1, 0, 0, 0, 0, false), 0, 0.05, List.of(), 0);
        assertTrue(north.position().z() > 0);
        assertEquals(0, north.position().x());
        assertEquals(FlightDynamics.speed(0), north.velocity().length(), 1e-10);
        assertEquals(5, north.position().z(), 1e-10);
        FlightDynamics.State west = FlightDynamics.step(initial,
                new FlightDynamics.Input(1, 0, 0, 90, 0, false), 0, 0.05, List.of(), 0);
        assertTrue(west.position().x() < 0);
        assertEquals(0, west.position().z(), 1e-12);
        FlightDynamics.State down = FlightDynamics.step(initial,
                new FlightDynamics.Input(1, 0, 0, 0, 45, false), 0, 0.05, List.of(), 0);
        assertTrue(down.position().y() < 0);
    }

    @Test
    void diagonalInputIsNormalizedAndReleaseOrBrakeStopsWithoutDrift() {
        FlightDynamics.State initial = new FlightDynamics.State(ZERO, ZERO);
        FlightDynamics.State diagonal = FlightDynamics.step(initial,
                new FlightDynamics.Input(1, 1, 1, 0, 0, false), 0, 0.1, List.of(), 0);
        FlightDynamics.State forward = FlightDynamics.step(initial,
                new FlightDynamics.Input(1, 0, 0, 0, 0, false), 0, 0.1, List.of(), 0);
        assertEquals(forward.velocity().length(), diagonal.velocity().length(), 1e-10);
        FlightDynamics.State coast = FlightDynamics.step(forward,
                new FlightDynamics.Input(0, 0, 0, 0, 0, false), 0, 0.1, List.of(), 0);
        FlightDynamics.State brake = FlightDynamics.step(forward,
                new FlightDynamics.Input(0, 0, 0, 0, 0, true), 0, 0.1, List.of(), 0);
        assertEquals(ZERO, coast.velocity());
        assertEquals(ZERO, brake.velocity());
        assertEquals(forward.position(), coast.position());
        assertEquals(forward.position(), brake.position());
        FlightDynamics.State heldBrake = FlightDynamics.step(forward,
                new FlightDynamics.Input(1, 1, 1, FlightOrientation.IDENTITY, true), 0, 0.1, List.of(), 0);
        assertEquals(forward.position(), heldBrake.position());
        assertEquals(ZERO, heldBrake.velocity());
        assertEquals(initial, FlightDynamics.step(initial,
                new FlightDynamics.Input(1, 0, 0, 0, 0, false), 0, 0, List.of(), 0));
    }

    @Test
    void sweptCollisionStopsEvenWhenOneTickWouldCrossWholePlanet() {
        CelestialBody body = body();
        FlightDynamics.State moving = new FlightDynamics.State(new SpaceVector(0, 0, -1_000_000),
                new SpaceVector(0, 0, 100_000_000));
        FlightDynamics.State next = FlightDynamics.step(moving,
                new FlightDynamics.Input(1, 0, 0, 0, 0, false), 3, 0.05, List.of(body), 0);
        assertTrue(next.position().z() < -body.radiusMeters());
        assertTrue(next.position().z() > moving.position().z());
        assertEquals(0, next.velocity().length());
        FlightDynamics.State penetrating = FlightDynamics.step(new FlightDynamics.State(ZERO, ZERO),
                new FlightDynamics.Input(0, 0, 0, 0, 0, true), 0, 0.05, List.of(body), 0);
        assertTrue(penetrating.position().length() > body.radiusMeters());
    }

    @Test
    void finiteBoundsAndSpeedLimitsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new FlightDynamics.Input(Float.NaN, 0, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new FlightDynamics.Input(2, 0, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new FlightDynamics.Input(0, 0, 0, 181, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new FlightDynamics.State(ZERO,
                new SpaceVector(FlightDynamics.MAX_SPEED * 2, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> FlightDynamics.speed(6));
        FlightDynamics.State edge = new FlightDynamics.State(new SpaceVector(Math.nextDown(FlightDynamics.MAX_POSITION), 0, 0),
                new SpaceVector(FlightDynamics.speed(5), 0, 0));
        FlightDynamics.State next = FlightDynamics.step(edge,
                new FlightDynamics.Input(1, 0, 0, -90, 0, false), 5, 0.05, List.of(), 0);
        assertEquals(FlightDynamics.MAX_POSITION, next.position().length(), 1);
        assertEquals(0, next.velocity().length());
    }

    @Test
    void observationsKeepScaleFaceTargetsAndExposeLitPlanetHemispheres() {
        CosmosSystem sol = CosmosGenerator.sol();
        for (String id : List.of("earth", "saturn", "moon")) {
            CelestialBody planet = sol.bodies().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
            FlightDynamics.Observation observation = FlightDynamics.observation(sol, planet, 100);
            SpaceVector center = sol.positionAt(planet, 100);
            double radii = planet.ringOuterRatio() > 0 ? 8 : 2;
            assertEquals(planet.radiusMeters() * radii, observation.position().distance(center), 0.001);
            SpaceVector observerDirection = observation.position().subtract(center).normalized();
            SpaceVector sunDirection = center.multiply(-1).normalized();
            assertTrue(observerDirection.dot(sunDirection) > 0.8, "Observation should reveal the illuminated hemisphere");
            double yaw = Math.toRadians(observation.yaw()), pitch = Math.toRadians(observation.pitch());
            SpaceVector facing = new SpaceVector(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
                    Math.cos(yaw) * Math.cos(pitch));
            assertTrue(facing.dot(center.subtract(observation.position()).normalized()) > 0.999999999);
        }
        CelestialBody hole = new CelestialBody("hole", "Hole", CelestialBody.Kind.BLACK_HOLE,
                100_000, 0, 0, 0, 0, 0, new SpaceVector(1, 0.5, 0.2), 0, 0, 0, 0);
        CosmosSystem blackHole = new CosmosSystem("black_hole", "Black hole", 1, CosmosSystem.Kind.BLACK_HOLE,
                ZERO, List.of(hole));
        assertEquals(24 * hole.radiusMeters(), FlightDynamics.observation(blackHole, hole, 0).position().length(), 1e-6);
        CosmosSystem remnant = new CosmosSystem("remnant", "Remnant", 1, CosmosSystem.Kind.SUPERNOVA, ZERO, List.of(hole));
        assertEquals(60 * hole.radiusMeters(), FlightDynamics.observation(remnant, hole, 0).position().length(), 1e-6);
    }


    @Test
    void rolledCameraMovesAlongItsOwnUpAndLeftWithContinuousSpeed() {
        FlightOrientation rolled = FlightOrientation.fromAngles(0, 0, 90);
        FlightDynamics.State initial = new FlightDynamics.State(ZERO, ZERO);
        FlightDynamics.State left = FlightDynamics.step(initial,
                new FlightDynamics.Input(0, 1, 0, rolled, false), 137.25, 0.05, List.of(), 0);
        assertEquals(0, left.position().x(), 1e-10);
        assertEquals(137.25 * 0.05, left.position().y(), 1e-10);
        FlightDynamics.State up = FlightDynamics.step(initial,
                new FlightDynamics.Input(0, 0, 1, rolled, false), 137.25, 0.05, List.of(), 0);
        assertEquals(-137.25 * 0.05, up.position().x(), 1e-10);
        assertEquals(0, up.position().y(), 1e-10);
        FlightOrientation inverted = FlightOrientation.fromAngles(0, 180, 0);
        FlightDynamics.State backward = FlightDynamics.step(initial,
                new FlightDynamics.Input(1, 0, 0, inverted, false), 137.25, 0.05, List.of(), 0);
        assertEquals(-137.25 * 0.05, backward.position().z(), 1e-10);
        assertEquals(0, backward.position().y(), 1e-10);
    }

    @Test
    void continuousSpeedIsBoundedWithoutQuantizingToFormerGears() {
        assertEquals(1, FlightDynamics.validateSpeed(1));
        assertEquals(1234.56789, FlightDynamics.validateSpeed(1234.56789));
        assertEquals(FlightDynamics.MAX_SPEED, FlightDynamics.validateSpeed(FlightDynamics.MAX_SPEED));
        for (double bad : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Math.nextUp(FlightDynamics.MAX_SPEED)}) {
            assertThrows(IllegalArgumentException.class, () -> FlightDynamics.validateSpeed(bad));
        }
        for (int gear = 0; gear < FlightDynamics.speedCount(); gear++) {
            assertEquals(gear, FlightDynamics.legacySpeedIndex(FlightDynamics.speed(gear)));
        }
    }

    @Test
    void manualGalacticSpeedCrossesTheFormerLocalWallWithoutChangingLegacyGears() {
        FlightDynamics.State initial = new FlightDynamics.State(new SpaceVector(FlightDynamics.LOCAL_RADIUS - 1, 0, 0), ZERO);
        FlightDynamics.State moved = FlightDynamics.step(initial,
                new FlightDynamics.Input(1, 0, 0, -90, 0, false), CosmosGenerator.LIGHT_YEAR,
                0.05, List.of(), 0);
        assertTrue(moved.position().x() > FlightDynamics.LOCAL_RADIUS);
        assertEquals(CosmosGenerator.LIGHT_YEAR, moved.velocity().length(), 10);
        assertEquals(10 * CosmosGenerator.AU, FlightDynamics.speed(5));
        assertEquals(FlightDynamics.LOCAL_MAX_SPEED, FlightDynamics.speed(5));
        assertEquals(1_000_000 * CosmosGenerator.LIGHT_YEAR, FlightDynamics.MAX_POSITION);
    }

    private static CelestialBody body() {
        return new CelestialBody("test", "Test", CelestialBody.Kind.ROCKY, 100_000, 0, 0, 0, 0, 0,
                new SpaceVector(0.4, 0.4, 0.4), 0, 0, 0, 0);
    }
}
