package dev.lexawhatt.astraengine.cosmos;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmosGeneratorTest {
    @Test
    void solarReferencePreservesPhysicalSizesAndDistances() {
        CosmosSystem sol = CosmosGenerator.sol();
        assertEquals(CosmosSystem.Kind.SINGLE, sol.kind());
        assertEquals(SpaceVector.ZERO, sol.galaxyPosition());
        assertEquals(List.of("sun", "mercury", "venus", "earth", "mars", "jupiter", "saturn", "uranus", "neptune"),
                sol.bodies().stream().map(CelestialBody::id).toList());
        CelestialBody sun = sol.bodies().getFirst();
        CelestialBody earth = sol.bodies().get(3);
        CelestialBody neptune = sol.bodies().getLast();
        assertEquals(695_700_000, sun.radiusMeters());
        assertEquals(6_371_000, earth.radiusMeters());
        assertEquals(149_598_000_000d, earth.orbitMeters());
        assertEquals(4_514_953_000_000d, neptune.orbitMeters(), 0.01);
        assertEquals(365.256 * 86_400, earth.orbitalPeriodSeconds());
        assertEquals(109.198, sun.radiusMeters() / earth.radiusMeters(), 0.001);
        double sunAngularDegrees = Math.toDegrees(2 * Math.asin(sun.radiusMeters() / CosmosGenerator.AU));
        assertEquals(0.5329, sunAngularDegrees, 0.0001);
        assertTrue(neptune.orbitMeters() > 30 * CosmosGenerator.AU);
    }

    @Test
    void generationAndCanonicalIdsAreIndependentOfVisitOrder() {
        CosmosSystem expected = CosmosGenerator.generate(1234, -7, 2, 14);
        CosmosGenerator.generate(1234, 30, -90, 1);
        assertEquals(expected, CosmosGenerator.generate(1234, -7, 2, 14));
        assertEquals(expected, CosmosGenerator.byId(1234, "s_-7_2_14"));
        assertNotEquals(expected, CosmosGenerator.generate(1235, -7, 2, 14));
        assertEquals(CosmosGenerator.sol(), CosmosGenerator.generate(Long.MIN_VALUE, 0, 0, 0));
        assertEquals(CosmosGenerator.sol(), CosmosGenerator.byId(Long.MAX_VALUE, "sol"));
        for (String invalid : List.of("s_0_0_0", "s_01_2_3", "s_-0_2_3", "s_+1_2_3", "s_1_2",
                "s_1_2_3_", "s_2147483648_0_0", "../sol", "SOL")) {
            assertThrows(IllegalArgumentException.class, () -> CosmosGenerator.byId(1, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> CosmosGenerator.byId(1, null));
    }

    @Test
    void boundedNeighborhoodIsUniqueSortedAndClipsAtIntegerDomain() {
        List<CosmosSystem> neighborhood = CosmosGenerator.nearby(44, SpaceVector.ZERO, 2);
        assertEquals(125, neighborhood.size());
        assertEquals(125, neighborhood.stream().map(CosmosSystem::id).distinct().count());
        assertEquals("sol", neighborhood.getFirst().id());
        double distance = -1;
        for (CosmosSystem system : neighborhood) {
            assertTrue(system.galaxyPosition().length() >= distance);
            distance = system.galaxyPosition().length();
            assertEquals(system, CosmosGenerator.byId(44, system.id()));
        }
        assertThrows(UnsupportedOperationException.class, neighborhood::clear);
        assertEquals(1, CosmosGenerator.nearby(44, new SpaceVector(-4, 8, 0), 0).size());
        assertEquals(27, CosmosGenerator.nearby(44,
                new SpaceVector(Integer.MAX_VALUE * 4d, Integer.MAX_VALUE * 4d, Integer.MAX_VALUE * 4d), 2).size());
        assertThrows(IllegalArgumentException.class, () -> CosmosGenerator.nearby(1, SpaceVector.ZERO, 3));
        assertThrows(IllegalArgumentException.class,
                () -> CosmosGenerator.nearby(1, new SpaceVector(1e30, 0, 0), 1));
    }

    @Test
    void proceduralSystemsHaveSeparatedOrbitsAndDiverseMaterials() {
        EnumSet<CelestialBody.Kind> materials = EnumSet.noneOf(CelestialBody.Kind.class);
        boolean ringFound = false;
        for (int sector = 1; sector <= 1000; sector++) {
            CosmosSystem system = CosmosGenerator.generate(20260927, sector, -sector / 3, sector / 7);
            assertTrue(system.bodies().size() >= 3 && system.bodies().size() <= 11);
            double previousAphelion = 0;
            int planets = 0;
            for (CelestialBody body : system.bodies()) {
                materials.add(body.kind());
                ringFound |= body.ringOuterRatio() > 0;
                if (body.kind() == CelestialBody.Kind.STAR || body.kind() == CelestialBody.Kind.BLACK_HOLE) {
                    previousAphelion = Math.max(previousAphelion, body.orbitMeters() + body.radiusMeters());
                    continue;
                }
                planets++;
                double extent = body.radiusMeters() * Math.max(1, body.ringOuterRatio());
                assertTrue(body.orbitMeters() >= CosmosGenerator.AU);
                assertTrue(body.orbitMeters() <= 100 * CosmosGenerator.AU);
                assertTrue(body.orbitMeters() * (1 - body.eccentricity()) - extent > previousAphelion);
                previousAphelion = body.orbitMeters() * (1 + body.eccentricity()) + extent;
                assertTrue(Double.isFinite(body.positionAt(1e12).length()));
            }
            assertTrue(planets >= 2 && planets <= 9);
        }
        assertEquals(EnumSet.allOf(CelestialBody.Kind.class), materials);
        assertTrue(ringFound);
    }

    @Test
    void blackHolesRemainRareAcrossAReproducibleSample() {
        int blackHoles = 0;
        int supernovae = 0;
        int binaries = 0;
        int samples = 10_000;
        for (int sector = 1; sector <= samples; sector++) {
            CosmosSystem.Kind kind = CosmosGenerator.generate(491827, sector, 41, -19).kind();
            blackHoles += kind == CosmosSystem.Kind.BLACK_HOLE ? 1 : 0;
            supernovae += kind == CosmosSystem.Kind.SUPERNOVA ? 1 : 0;
            binaries += kind == CosmosSystem.Kind.BINARY ? 1 : 0;
        }
        assertTrue(blackHoles > samples * 0.01 && blackHoles < samples * 0.04);
        assertTrue(supernovae > samples * 0.01 && supernovae < samples * 0.04);
        assertTrue(binaries > samples * 0.14 && binaries < samples * 0.22);
    }

    @Test
    void keplerEllipseHasCorrectApsidesPeriodAndInclination() {
        CelestialBody body = new CelestialBody("test", "Test", CelestialBody.Kind.ROCKY,
                1_000, 1_000_000, 1_000, 0, Math.PI / 6, 0.2,
                new SpaceVector(0.5, 0.5, 0.5), 0, 0, 0, 0);
        assertEquals(800_000, body.positionAt(0).length(), 1e-7);
        assertEquals(1_200_000, body.positionAt(500).length(), 1e-7);
        assertEquals(0, body.positionAt(137).distance(body.positionAt(1137)), 1e-7);
        assertEquals(Math.tan(Math.PI / 6), body.positionAt(250).y() / body.positionAt(250).z(), 1e-12);
        assertEquals(0, body.positionAt(-137).distance(body.positionAt(863)), 1e-7);
        assertTrue(Double.isFinite(body.positionAt(Double.MAX_VALUE).length()));
        assertThrows(IllegalArgumentException.class, () -> body.positionAt(Double.NaN));
    }

    @Test
    void doubleVectorsPreserveLocalDetailBeforeFloatConversion() {
        double distantPosition = CosmosGenerator.AU * 30;
        SpaceVector object = new SpaceVector(distantPosition + 12.5, 0, 0);
        SpaceVector observer = new SpaceVector(distantPosition, 0, 0);
        assertEquals(12.5, object.subtract(observer).x());
        assertEquals(1, new SpaceVector(Double.MAX_VALUE, Double.MAX_VALUE, 0).normalized().length(), 1e-15);
        assertEquals(new SpaceVector(1, 0, 0), new SpaceVector(Double.MIN_VALUE, 0, 0).normalized());
        assertThrows(IllegalArgumentException.class, SpaceVector.ZERO::normalized);
        assertThrows(IllegalArgumentException.class, () -> new SpaceVector(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> observer.multiply(Double.POSITIVE_INFINITY));
    }

    @Test
    void descriptorsDefensivelyOwnTheirBodiesAndRejectInvalidData() {
        ArrayList<CelestialBody> bodies = new ArrayList<>(CosmosGenerator.sol().bodies());
        CosmosSystem system = new CosmosSystem("copy", "Copy", 3, CosmosSystem.Kind.SINGLE, SpaceVector.ZERO, bodies);
        bodies.clear();
        assertEquals(9, system.bodies().size());
        assertThrows(UnsupportedOperationException.class, () -> system.bodies().clear());
        CelestialBody earth = CosmosGenerator.sol().bodies().get(3);
        assertThrows(IllegalArgumentException.class, () -> new CosmosSystem("copy", "Copy", 3,
                CosmosSystem.Kind.SINGLE, SpaceVector.ZERO, List.of(earth, earth)));
        assertThrows(IllegalArgumentException.class, () -> new CelestialBody("bad", "Bad", CelestialBody.Kind.ROCKY,
                1000, 1_000_000, 1_000, 0, 0, 0.9, SpaceVector.ZERO, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CelestialBody("bad", "Bad", CelestialBody.Kind.ROCKY,
                1000, 1_000_000, 1_000, 0, 0, 0, SpaceVector.ZERO, 0, 0.9f, 2, 0));
    }
}
