package dev.lexawhatt.astraengine.cosmos;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Swept chart arrival at galactic speeds, preserving local precision and never inventing hidden destinations. */
class GalacticNavigationTest {
    private static final double LY = CosmosGenerator.LIGHT_YEAR;

    @Test
    void fastSegmentStopsAtFirstChartedEntryEvenWhenBothEndpointsAreOutside() {
        CosmosSystem origin = system("origin", SpaceVector.ZERO);
        CosmosSystem near = system("near", new SpaceVector(0, 0, 4));
        CosmosSystem far = system("far", new SpaceVector(0, 0, 8));
        SpaceVector end = new SpaceVector(0, 0, 500 * LY);
        GalacticNavigation.Arrival hit = GalacticNavigation.firstArrival(origin, SpaceVector.ZERO, end,
                List.of(far, origin, near)).orElseThrow();
        double radius = GalacticNavigation.arrivalRadiusMeters(near);
        assertEquals(near, hit.system());
        assertEquals(0, new SpaceVector(0, 0, -radius).distance(hit.position()), 0.001);
        assertEquals((4 * LY - radius) / end.z(), hit.fraction(), 1e-15);
        assertEquals(far, GalacticNavigation.firstArrival(origin, SpaceVector.ZERO, end, List.of(far))
                .orElseThrow().system());
        assertTrue(GalacticNavigation.firstArrival(origin, SpaceVector.ZERO, end, List.of(origin)).isEmpty());
    }

    @Test
    void noMovementAlreadyInsideAndOppositeDirectionDoNotCreateVisits() {
        CosmosSystem origin = system("origin", SpaceVector.ZERO);
        CosmosSystem colocated = system("colocated", SpaceVector.ZERO);
        CosmosSystem ahead = system("ahead", new SpaceVector(0, 0, 4));
        assertTrue(GalacticNavigation.firstArrival(origin, SpaceVector.ZERO, SpaceVector.ZERO,
                List.of(ahead)).isEmpty());
        assertTrue(GalacticNavigation.firstArrival(origin, SpaceVector.ZERO, new SpaceVector(0, 0, LY),
                List.of(colocated)).isEmpty());
        assertTrue(GalacticNavigation.firstArrival(origin, SpaceVector.ZERO, new SpaceVector(0, 0, -8 * LY),
                List.of(ahead)).isEmpty());
        SpaceVector outside = new SpaceVector(0, 0, LY);
        assertEquals(colocated, GalacticNavigation.firstArrival(origin, outside, SpaceVector.ZERO,
                List.of(colocated)).orElseThrow().system());
    }

    @Test
    void offAxisSweepUsesActualDistanceAndDeterministicTieOrder() {
        CosmosSystem origin = system("origin", SpaceVector.ZERO);
        double radius = GalacticNavigation.arrivalRadiusMeters(origin);
        CosmosSystem hit = system("a_hit", new SpaceVector(radius * 0.5 / LY, 0, 4));
        CosmosSystem tie = system("z_tie", hit.galaxyPosition());
        CosmosSystem missed = system("missed", new SpaceVector(radius * 1.1 / LY, 0, 2));
        GalacticNavigation.Arrival reached = GalacticNavigation.firstArrival(origin, SpaceVector.ZERO,
                new SpaceVector(0, 0, 5 * LY), List.of(missed, tie, hit)).orElseThrow();
        assertEquals(hit, reached.system());
        assertEquals(radius, reached.position().length(), 0.01);
        assertEquals(-radius * 0.5, reached.position().x(), 0.01);
        assertTrue(reached.position().z() < 0);
        assertFalse(reached.fraction() == 1);
    }

    @Test
    void diagonalColocatedArrivalCannotImmediatelyRecaptureItsBoundary() {
        CosmosSystem origin = system("origin", SpaceVector.ZERO);
        SpaceVector center = new SpaceVector(1.6233126196437304, 0.9310407640535683, 2.581137373952016);
        CosmosSystem first = system("a_first", center);
        CosmosSystem second = system("b_second", center);
        SpaceVector motion = center.multiply(2 * LY);
        GalacticNavigation.Arrival hit = GalacticNavigation.firstArrival(origin, SpaceVector.ZERO, motion,
                List.of(second, first)).orElseThrow();
        double radius = GalacticNavigation.arrivalRadiusMeters(first);
        assertEquals(first, hit.system());
        assertEquals(radius, hit.position().length(), Math.ulp(radius) * 2);
        SpaceVector next = hit.position().add(motion.normalized().multiply(100));
        assertTrue(GalacticNavigation.firstArrival(first, hit.position(), next, List.of(first, second)).isEmpty(),
                "A colocated system must not capture again because of galactic projection roundoff");
        SpaceVector outside = hit.position().multiply(2);
        SpaceVector inside = hit.position().multiply(0.5);
        assertEquals(second, GalacticNavigation.firstArrival(first, outside, inside, List.of(first, second))
                .orElseThrow().system(), "Leaving and deliberately reentering still visits the colocated system");
    }

    @Test
    void rebasePreservesGlobalEntryAtLargeObserverCoordinates() {
        CosmosSystem origin = system("origin", new SpaceVector(900_000, -700_000, 100_000));
        CosmosSystem target = system("target", origin.galaxyPosition().add(new SpaceVector(100_000, 1, 0)));
        SpaceVector start = new SpaceVector(99_000 * LY, LY, 0);
        SpaceVector end = new SpaceVector(101_000 * LY, LY, 0);
        GalacticNavigation.Arrival reached = GalacticNavigation.firstArrival(origin, start, end,
                List.of(target)).orElseThrow();
        double radius = GalacticNavigation.arrivalRadiusMeters(target);
        assertEquals(0, new SpaceVector(-radius, 0, 0).distance(reached.position()), 0.001);
        SpaceVector oldOriginEntry = start.add(end.subtract(start).multiply(reached.fraction()));
        SpaceVector newOriginEntry = target.galaxyPosition().subtract(origin.galaxyPosition()).multiply(LY)
                .add(reached.position());
        assertTrue(oldOriginEntry.distance(newOriginEntry) <= Math.ulp(newOriginEntry.x()) * 2);
    }

    @Test
    void arrivalEnvelopeTracksSystemExtentWithinExplicitLocalBounds() {
        CosmosSystem compact = system("compact", SpaceVector.ZERO);
        assertEquals(64 * CosmosGenerator.AU, GalacticNavigation.arrivalRadiusMeters(compact));
        CelestialBody outer = new CelestialBody("outer", "Outer", CelestialBody.Kind.ICE, 1_000_000,
                100 * CosmosGenerator.AU, 1e8, 0, 0, 0.2, new SpaceVector(0.5, 0.5, 1), 0, 0, 0, 0);
        CosmosSystem extended = new CosmosSystem("extended", "Extended", 0, CosmosSystem.Kind.SINGLE,
                SpaceVector.ZERO, List.of(outer));
        assertEquals((outer.orbitMeters() * 1.2 + FlightDynamics.safeRadius(outer)) * 1.5,
                GalacticNavigation.arrivalRadiusMeters(extended));
        CelestialBody huge = new CelestialBody("huge", "Huge", CelestialBody.Kind.STAR, 1e12,
                1e15, 1e10, 0, 0, 0, new SpaceVector(1, 1, 1), 0, 0, 0, 0);
        assertEquals(FlightDynamics.LOCAL_RADIUS, GalacticNavigation.arrivalRadiusMeters(new CosmosSystem(
                "huge", "Huge", 0, CosmosSystem.Kind.SINGLE, SpaceVector.ZERO, List.of(huge))));
        assertThrows(IllegalArgumentException.class, () -> GalacticNavigation.firstArrival(compact,
                SpaceVector.ZERO, new SpaceVector(FlightDynamics.MAX_POSITION * 2, 0, 0), List.of()));
        assertThrows(IllegalArgumentException.class, () -> GalacticNavigation.arrivalRadiusMeters(null));
    }

    private static CosmosSystem system(String id, SpaceVector lightYears) {
        CelestialBody star = new CelestialBody("star", "Star", CelestialBody.Kind.STAR, 695_700_000,
                0, 0, 0, 0, 0, new SpaceVector(1, 1, 1), 0, 0, 0, 0);
        return new CosmosSystem(id, id, 0, CosmosSystem.Kind.SINGLE, lightYears, List.of(star));
    }
}
