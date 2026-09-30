package dev.lexawhatt.astraengine.cosmos;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Continuous local approach preserves scale, settles gently and refuses unsafe routes without relocating. */
class BodyApproachTest {
    @Test
    void earthToSunAimsBeforeMovingAndArrivesAtPredictedObservationWithoutJump() {
        CosmosSystem sol = CosmosGenerator.sol();
        CelestialBody earth = body(sol, "earth"), sun = body(sol, "sun");
        FlightDynamics.Observation source = FlightDynamics.observation(sol, earth, 0);
        FlightOrientation initial = FlightOrientation.fromAngles(123, 67, -38);
        BodyApproach route = plan(sol, sun, source.position(), initial, 0);
        assertEquals(source.position(), route.frame(0).state().position());
        assertEquals(initial, route.frame(0).orientation());
        assertEquals(source.position(), route.frame(BodyApproach.AIM_TICKS).state().position());
        assertTrue(route.frame(BodyApproach.AIM_TICKS + 10).state().position().distance(source.position()) > 1000);
        FlightDynamics.Observation arrival = FlightDynamics.observation(sol, sun, route.durationTicks() / 20.0);
        BodyApproach.Frame last = route.frame(route.durationTicks());
        assertEquals(arrival.position(), last.state().position());
        assertEquals(SpaceVector.ZERO, last.state().velocity());
        assertTrue(last.state().position().distance(route.frame(route.durationTicks() - 4).state().position())
                < sun.radiusMeters() * 0.1);
        double priorDistance = Double.POSITIVE_INFINITY;
        for (int tick = BodyApproach.AIM_TICKS; tick <= route.durationTicks(); tick++) {
            BodyApproach.Frame frame = route.frame(tick);
            double remaining = frame.state().position().distance(arrival.position());
            assertTrue(remaining <= priorDistance + 0.01);
            priorDistance = remaining;
            assertTrue(frame.state().velocity().length() <= FlightDynamics.LOCAL_MAX_SPEED);
            assertTrue(frame.orientation().forward().dot(sun.positionAt(tick / 20.0)
                    .subtract(frame.state().position()).normalized()) > 0.999999);
        }
    }

    @Test
    void eachSolarBodyHasAContinuousBoundedRouteFromEarthAndMovingArrival() {
        CosmosSystem sol = CosmosGenerator.sol();
        double startSeconds = 1234;
        FlightDynamics.Observation source = FlightDynamics.observation(sol, body(sol, "earth"), startSeconds);
        for (CelestialBody target : sol.bodies()) {
            BodyApproach route = plan(sol, target, source.position(), source.orientation(), startSeconds);
            assertTrue(route.durationTicks() <= BodyApproach.MAX_TICKS);
            BodyApproach.Frame previous = route.frame(0);
            for (int tick = 1; tick <= route.durationTicks(); tick++) {
                BodyApproach.Frame next = route.frame(tick);
                assertTrue(FlightDynamics.clearSegment(previous.state().position(), next.state().position(),
                        sol.bodies(), startSeconds + (tick - 1) / 20.0, startSeconds + tick / 20.0), target.id());
                assertTrue(next.state().velocity().length() <= FlightDynamics.LOCAL_MAX_SPEED);
                previous = next;
            }
            FlightDynamics.Observation predicted = FlightDynamics.observation(sol, target,
                    startSeconds + route.durationTicks() / 20.0);
            assertEquals(predicted.position(), previous.state().position());
            double radius = target.ringOuterRatio() > 0 ? 8 : 4;
            assertEquals(Math.max(target.radiusMeters() * radius, 100_000), previous.state().position()
                    .distance(sol.positionAt(target, startSeconds + route.durationTicks() / 20.0)), 0.01);
        }
    }

    @Test
    void blockedStraightPathUsesCheckedArcInsteadOfCrossingTheStar() {
        CelestialBody star = stationary();
        CosmosSystem system = new CosmosSystem("test", "Test", 1, CosmosSystem.Kind.SINGLE,
                SpaceVector.ZERO, List.of(star));
        SpaceVector start = new SpaceVector(0, 0, star.radiusMeters() * 10);
        SpaceVector end = FlightDynamics.observation(system, star, 0).position();
        assertTrue(!FlightDynamics.clearSegment(start, end, system.bodies(), 0, 12));
        BodyApproach route = plan(system, star, start, FlightOrientation.IDENTITY, 0);
        SpaceVector previous = start;
        FlightOrientation previousOrientation = route.frame(0).orientation();
        boolean crossedPole = false;
        for (int tick = 1; tick <= route.durationTicks(); tick++) {
            BodyApproach.Frame frame = route.frame(tick);
            SpaceVector next = frame.state().position();
            assertTrue(FlightDynamics.clearSegment(previous, next, system.bodies(), (tick - 1) / 20.0, tick / 20.0));
            assertTrue(previousOrientation.up().dot(frame.orientation().up()) > 0.98,
                    "A pole-crossing detour must not flip camera roll");
            assertTrue(previousOrientation.forward().dot(frame.orientation().forward()) > 0.98);
            crossedPole |= Math.abs(frame.orientation().forward().y()) > 0.99;
            previous = next;
            previousOrientation = frame.orientation();
        }
        assertTrue(crossedPole, "The curved fixture must exercise the world-up singularity");
    }

    @Test
    void nearTargetAndRepeatedPlanningRemainDeterministicAndDoNotTeleport() {
        CelestialBody star = stationary();
        CosmosSystem system = new CosmosSystem("test", "Test", 1, CosmosSystem.Kind.SINGLE,
                SpaceVector.ZERO, List.of(star));
        FlightDynamics.Observation observation = FlightDynamics.observation(system, star, 0);
        BodyApproach first = plan(system, star, observation.position(), observation.orientation(), 0);
        BodyApproach second = plan(system, star, observation.position(), observation.orientation(), 0);
        assertEquals(first.durationTicks(), second.durationTicks());
        for (int tick = 0; tick <= first.durationTicks(); tick++) {
            assertEquals(first.frame(tick), second.frame(tick));
            assertTrue(first.frame(tick).state().position().distance(observation.position()) < 1e-8);
        }
        assertTrue(BodyApproach.plan(system, star, new FlightDynamics.State(SpaceVector.ZERO, SpaceVector.ZERO),
                FlightOrientation.IDENTITY, 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> first.frame(first.durationTicks() + 1));
    }

    @Test
    void distantRoutesRespectContinuousSpeedAndPositionLimits() {
        CelestialBody star = stationary();
        CosmosSystem system = new CosmosSystem("test", "Test", 1, CosmosSystem.Kind.SINGLE,
                SpaceVector.ZERO, List.of(star));
        SpaceVector start = new SpaceVector(0, 0, -FlightDynamics.LOCAL_RADIUS * 0.9);
        BodyApproach route = plan(system, star, start, FlightOrientation.IDENTITY, 0);
        assertTrue(route.durationTicks() > 240);
        assertTrue(route.durationTicks() <= BodyApproach.MAX_TICKS);
        double maxSpeed = 0;
        for (int tick = 1; tick <= route.durationTicks(); tick++) {
            BodyApproach.Frame frame = route.frame(tick);
            maxSpeed = Math.max(maxSpeed, frame.state().velocity().length());
            assertTrue(frame.state().position().length() <= FlightDynamics.LOCAL_RADIUS);
        }
        assertTrue(maxSpeed <= FlightDynamics.LOCAL_MAX_SPEED);
        assertTrue(maxSpeed > FlightDynamics.LOCAL_MAX_SPEED * 0.8);
        FlightDynamics.State galactic = new FlightDynamics.State(
                new SpaceVector(0, 0, -FlightDynamics.MAX_POSITION * 0.9), SpaceVector.ZERO);
        assertTrue(BodyApproach.plan(system, star, galactic, FlightOrientation.IDENTITY, 0).isEmpty(),
                "A galactic-distance approach must refuse the duration instead of overflowing its tick count");
    }

    @Test
    void orbitalSweepDetectsBodyPassingThroughAnOtherwiseStationaryCamera() {
        CelestialBody moving = new CelestialBody("moving", "Moving", CelestialBody.Kind.ROCKY,
                100_000, 1_000_000, 4, 0, 0, 0, new SpaceVector(0.5, 0.5, 0.5), 0, 0, 0, 0);
        SpaceVector camera = new SpaceVector(0, 0, 1_000_000);
        assertTrue(FlightDynamics.clearSegment(camera, camera, List.of(moving), 0, 0));
        assertTrue(FlightDynamics.clearSegment(camera, camera, List.of(moving), 2, 2));
        assertTrue(!FlightDynamics.clearSegment(camera, camera, List.of(moving), 0, 2));
    }

    @Test
    void smallCurrentTargetRouteKeepsConstantAxesAtLargeCoordinatesAndLateClock() {
        CelestialBody remote = new CelestialBody("remote", "Remote", CelestialBody.Kind.STAR,
                1_000_000, 1.0e14, 1.0e15, 0, 0, 0, new SpaceVector(1, 0.8, 0.5), 0, 0, 0, 0);
        CosmosSystem system = new CosmosSystem("remote", "Remote", 1, CosmosSystem.Kind.SINGLE,
                SpaceVector.ZERO, List.of(remote));
        // The saved exploration clock permits up to 1e12 ticks, or 5e10 seconds.
        for (double seconds : new double[]{0, 5.0e10 - 60}) {
            FlightDynamics.Observation source = FlightDynamics.observation(system, remote, seconds);
            BodyApproach route = plan(system, remote, source.position(), source.orientation(), seconds);
            SpaceVector destination = FlightDynamics.observation(system, remote,
                    seconds + route.durationTicks() / 20.0).position();
            assertTrue(source.position().distance(destination) < 10);
            for (int tick = 0; tick <= route.durationTicks(); tick++) {
                SpaceVector position = route.frame(tick).state().position();
                assertEquals(source.position().y(), position.y(), "A constant axis must not acquire idle movement");
                assertTrue(position.x() >= Math.min(source.position().x(), destination.x()));
                assertTrue(position.x() <= Math.max(source.position().x(), destination.x()));
            }
            assertEquals(destination, route.frame(route.durationTicks()).state().position());
        }
    }

    @Test
    void earthMarsThenSunUsesAContinuousInitialAimAfterTwentyIdleTicks() {
        CosmosSystem sol = CosmosGenerator.sol();
        FlightDynamics.Observation source = FlightDynamics.observation(sol, body(sol, "earth"), 0);
        for (double startSeconds : new double[]{0, 2, 1234, 5.0e10 - 60}) {
            BodyApproach mars = plan(sol, body(sol, "mars"), source.position(), source.orientation(), startSeconds);
            BodyApproach.Frame arrived = mars.frame(mars.durationTicks());
            BodyApproach sun = plan(sol, body(sol, "sun"), arrived.state().position(), arrived.orientation(),
                    startSeconds + mars.durationTicks() / 20.0 + 1);
            FlightOrientation previous = sun.frame(0).orientation();
            for (int tick = 1; tick <= BodyApproach.AIM_TICKS; tick++) {
                FlightOrientation current = sun.frame(tick).orientation();
                // A fixed target's cubic-eased half-turn has maximum rate 180 * 1.5 / 40 degrees/tick.
                assertTrue(angleDegrees(previous, current) <= 6.75, "Sun aiming exceeded its continuous angular bound");
                previous = current;
            }
        }
    }

    @Test
    void movingTargetCrossingTheAimingHalfTurnDoesNotSwitchQuaternionBranch() {
        CelestialBody moving = new CelestialBody("moving", "Moving", CelestialBody.Kind.STAR,
                1.0e7, 1.0e9, 5000, 0, 0, 0, new SpaceVector(1, 0.8, 0.5), 0, 0, 0, 0);
        CosmosSystem system = new CosmosSystem("moving", "Moving", 1, CosmosSystem.Kind.SINGLE,
                SpaceVector.ZERO, List.of(moving));
        // The target passes exactly opposite this view one second into the two-second aiming interval.
        FlightOrientation initial = FlightOrientation.fromAngles(90 + 360.0 / 5000, 0, 0);
        BodyApproach route = plan(system, moving, SpaceVector.ZERO, initial, 0);
        FlightOrientation previous = initial;
        for (int tick = 1; tick <= BodyApproach.AIM_TICKS; tick++) {
            FlightOrientation current = route.frame(tick).orientation();
            // Fixed half-turn bound plus the target's 0.0036-degree orbital motion per tick.
            assertTrue(angleDegrees(previous, current) <= 6.76, "Moving target switched the initial aiming arc");
            previous = current;
        }
        SpaceVector target = moving.positionAt(BodyApproach.AIM_TICKS / 20.0).normalized();
        assertTrue(previous.forward().dot(target) > 0.999999999999);
    }

    private static double angleDegrees(FlightOrientation from, FlightOrientation to) {
        double dot = Math.abs(from.x() * to.x() + from.y() * to.y() + from.z() * to.z() + from.w() * to.w());
        return Math.toDegrees(2 * Math.acos(Math.clamp(dot, 0, 1)));
    }

    private static BodyApproach plan(CosmosSystem system, CelestialBody target, SpaceVector position,
            FlightOrientation orientation, double seconds) {
        FlightDynamics.State state = new FlightDynamics.State(position, SpaceVector.ZERO);
        return BodyApproach.plan(system, target, state, orientation, seconds)
                .orElseThrow(() -> new AssertionError("No approach for " + target.id()));
    }

    private static CelestialBody body(CosmosSystem system, String id) {
        return system.bodies().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    private static CelestialBody stationary() {
        return new CelestialBody("star", "Star", CelestialBody.Kind.STAR, 1_000_000, 0, 0, 0, 0, 0,
                new SpaceVector(1, 0.8, 0.5), 0, 0, 0, 0);
    }
}
