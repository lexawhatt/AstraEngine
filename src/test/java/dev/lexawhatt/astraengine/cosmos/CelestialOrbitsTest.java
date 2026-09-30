package dev.lexawhatt.astraengine.cosmos;

import dev.lexawhatt.astraengine.api.celestial.CelestialBodies;
import dev.lexawhatt.astraengine.api.celestial.CelestialSystems;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CelestialOrbitsTest {
    @Test
    void moonUsesNasaPhysicalScaleAroundMovingEarthWithoutChangingLegacySolarBodies() {
        CosmosSystem sol = CosmosGenerator.sol();
        CelestialBody moon = sol.bodies().get(9);
        assertEquals("moon", moon.id());
        assertEquals("earth", moon.parentId());
        assertEquals(1_737_400, moon.radiusMeters());
        assertEquals(384_400_000, moon.orbitMeters());
        assertEquals(27.3217 * 86_400, moon.orbitalPeriodSeconds());
        assertEquals(0.0549, moon.eccentricity());
        assertEquals(Math.toRadians(5.145), moon.inclinationRadians());
        assertEquals(CelestialBody.Kind.ROCKY, moon.kind());
        assertEquals(0, moon.atmosphere());
        for (double seconds : new double[]{0, 1234, moon.orbitalPeriodSeconds() / 2, 1e10}) {
            SpaceVector offset = sol.positionAt(moon, seconds).subtract(sol.positionAt("earth", seconds));
            assertEquals(0, offset.distance(moon.positionAt(seconds)), 0.0001);
            assertTrue(offset.length() >= moon.orbitMeters() * (1 - moon.eccentricity()) - 0.001);
            assertTrue(offset.length() <= moon.orbitMeters() * (1 + moon.eccentricity()) + 0.001);
            for (int index = 0; index < 9; index++) {
                CelestialBody legacy = sol.bodies().get(index);
                assertEquals("", legacy.parentId());
                assertEquals(legacy.positionAt(seconds), sol.positionAt(legacy, seconds));
            }
        }
        assertEquals(0, moon.positionAt(73).distance(moon.positionAt(73 + moon.orbitalPeriodSeconds())), 0.0001);
    }

    @Test
    void nestedOrbitsResolveForwardReferencesWithoutInheritingParentSpinOrOrbitalPlane() {
        CelestialBody parent = draft("planet", 1e9, 1e6).inclinationRadians(1.1).axialTiltRadians(2).build();
        CelestialBody child = draft("moon", 1e7, 1e4).parent("planet").inclinationRadians(-0.7).build();
        CelestialBody grandchild = draft("submoon", 1e5, 100).parent("moon").build();
        CosmosSystem system = system(List.of(grandchild, child, parent));
        double seconds = 19;
        SpaceVector expected = parent.positionAt(seconds).add(child.positionAt(seconds)).add(grandchild.positionAt(seconds));
        assertEquals(0, expected.distance(system.positionAt("submoon", seconds)), 1e-6);
        assertEquals(1_010_100_000, CelestialOrbits.maximumDistance(system.bodies(), grandchild));
        assertThrows(IllegalArgumentException.class, () -> system.positionAt("absent", seconds));
        assertThrows(IllegalArgumentException.class, () -> system.positionAt((String) null, seconds));
        assertThrows(IllegalArgumentException.class, () -> system.positionAt(child, Double.NaN));
    }

    @Test
    void missingSelfCyclicAndMalformedParentsFailBeforePublishingASystem() {
        assertThrows(IllegalArgumentException.class, () -> draft("moon", 1e6, 100).parent("moon").build());
        assertThrows(IllegalArgumentException.class, () -> draft("moon", 1e6, 100).parent(null).build());
        assertThrows(IllegalArgumentException.class, () -> draft("moon", 1e6, 100).parent("owner:planet").build());
        CelestialBody missing = draft("moon", 1e6, 100).parent("absent").build();
        assertThrows(IllegalArgumentException.class, () -> system(List.of(missing)));
        CelestialBody first = draft("first", 1e6, 100).parent("second").build();
        CelestialBody second = draft("second", 1e6, 100).parent("third").build();
        CelestialBody third = draft("third", 1e6, 100).parent("first").build();
        assertThrows(IllegalArgumentException.class, () -> system(List.of(first, second, third)));
        assertThrows(IllegalArgumentException.class,
                () -> CelestialOrbits.positionAt(List.of(first, second, third), first, 0));
        assertThrows(IllegalArgumentException.class,
                () -> CelestialOrbits.maximumDistance(List.of(missing), missing));
    }

    @Test
    void maximumSixtyFourBodyChainResolvesButExcessBodiesAreStillRejected() {
        ArrayList<CelestialBody> bodies = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            bodies.add(draft("body_" + index, 1e6, 100).parent(index == 0 ? "" : "body_" + (index - 1)).build());
        }
        Collections.reverse(bodies);
        CosmosSystem full = system(bodies);
        assertEquals(new SpaceVector(64e6, 0, 0), full.positionAt("body_63", 0));
        bodies.add(draft("extra", 1e6, 100).parent("body_63").build());
        assertThrows(IllegalArgumentException.class, () -> system(bodies));
    }

    @Test
    void customPublicationIncludesAncestorApoapsesInItsNavigationEnvelope() {
        CelestialBody parent = draft("planet", FlightDynamics.LOCAL_RADIUS * 0.6, 1e9).build();
        CelestialBody child = draft("moon", FlightDynamics.LOCAL_RADIUS * 0.6, 1e8).parent("planet").build();
        CosmosSystem tooWide = system(List.of(parent, child));
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.validateCustom(tooWide));
        CelestialBody inside = draft("moon", FlightDynamics.LOCAL_RADIUS * 0.2, 1e8).parent("planet").build();
        CelestialSystems.validateCustom(system(List.of(parent, inside)));
    }

    @Test
    void orbitalMotionBoundsIncludeMovingParentsEvenForStationaryChildOffsets() {
        CelestialBody parent = draft("planet", 1e8, 1e5).eccentricity(0.2).build();
        CelestialBody child = draft("moon", 0, 0).parent("planet").build();
        List<CelestialBody> bodies = List.of(parent, child);
        assertEquals(CelestialOrbits.displacementBound(bodies, parent, 100),
                CelestialOrbits.displacementBound(bodies, child, 100));
        assertEquals(CelestialOrbits.curvatureBound(bodies, parent, 100),
                CelestialOrbits.curvatureBound(bodies, child, 100));
        assertTrue(CelestialOrbits.curvatureBound(bodies, child, 100) > 0);
        assertEquals(0, CelestialOrbits.displacementBound(bodies, child, 0));
        assertThrows(IllegalArgumentException.class, () -> CelestialOrbits.curvatureBound(bodies, child, -1));
    }

    @Test
    void moonCollisionUsesResolvedEarthOrbitForSweepsAndPenetration() {
        CosmosSystem sol = CosmosGenerator.sol();
        CelestialBody moon = sol.bodies().get(9);
        double seconds = 1234;
        SpaceVector center = sol.positionAt(moon, seconds);
        SpaceVector start = center.add(new SpaceVector(0, 0, -moon.radiusMeters() * 3));
        SpaceVector end = center.add(new SpaceVector(0, 0, moon.radiusMeters() * 3));
        assertFalse(FlightDynamics.clearSegment(start, end, sol.bodies(), seconds, seconds));
        FlightDynamics.State stopped = FlightDynamics.step(new FlightDynamics.State(start, SpaceVector.ZERO),
                new FlightDynamics.Input(1, 0, 0, FlightOrientation.IDENTITY, false),
                1e9, 0.05, sol.bodies(), seconds);
        assertEquals(SpaceVector.ZERO, stopped.velocity());
        assertEquals(FlightDynamics.safeRadius(moon) + 1, stopped.position().distance(center), 0.001);
        FlightDynamics.State evacuated = FlightDynamics.step(new FlightDynamics.State(center, SpaceVector.ZERO),
                new FlightDynamics.Input(0, 0, 0, FlightOrientation.IDENTITY, false),
                100.0, 0.05, sol.bodies(), seconds);
        assertEquals(FlightDynamics.safeRadius(moon) + 1, evacuated.position().distance(center), 0.001);
    }

    private static CelestialBodies.Builder draft(String id, double orbit, double period) {
        return CelestialBodies.planet(id, id, CelestialBody.Kind.ROCKY, 1000).orbit(orbit, period);
    }

    private static CosmosSystem system(List<CelestialBody> bodies) {
        return new CosmosSystem("verification:moons", "Moons", 1, CosmosSystem.Kind.SINGLE, SpaceVector.ZERO, bodies);
    }
}
