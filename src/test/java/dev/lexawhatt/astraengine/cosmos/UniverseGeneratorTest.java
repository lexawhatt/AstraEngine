package dev.lexawhatt.astraengine.cosmos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Additive galaxy identities, common spatial population and finite local navigation remain deterministic. */
class UniverseGeneratorTest {
    @Test
    void atlasIsDeterministicSeparatedAndWithinManualFlightReach() {
        for (long seed : new long[]{0, 42, Long.MIN_VALUE, Long.MAX_VALUE}) {
            List<GalaxyDescriptor> galaxies = UniverseGenerator.galaxies(seed);
            assertEquals(UniverseGenerator.GALAXY_COUNT, galaxies.size());
            assertEquals(galaxies, UniverseGenerator.galaxyDescriptors(seed));
            assertThrows(UnsupportedOperationException.class, () -> galaxies.clear());
            Set<GalaxyDescriptor.Kind> kinds = new HashSet<>();
            for (GalaxyDescriptor galaxy : galaxies) {
                kinds.add(galaxy.kind());
                assertEquals(galaxy, UniverseGenerator.galaxy(seed, galaxy.index()));
                assertTrue(galaxy.centerLightYears().distance(UniverseGenerator.MILKY_WAY_CENTER_LIGHT_YEARS) < 400_000);
                assertEquals(galaxy.index() == 5, galaxy.activeNucleus());
                for (GalaxyDescriptor other : galaxies) {
                    if (other.index() == galaxy.index()) { continue; }
                    double distance = galaxy.centerLightYears().distance(other.centerLightYears());
                    assertTrue(distance > 150_000, "Distinct galaxy envelopes must not become one composite disk");
                    assertTrue(distance + galaxy.radiusLightYears() + other.radiusLightYears() < 1_000_000,
                            "Atlas destinations must remain reachable within the existing manual flight bound");
                }
            }
            assertEquals(Set.of(GalaxyDescriptor.Kind.SPIRAL, GalaxyDescriptor.Kind.ELLIPTICAL,
                    GalaxyDescriptor.Kind.IRREGULAR), kinds);
            assertEquals(UniverseGenerator.MILKY_WAY_CENTER_LIGHT_YEARS, galaxies.getFirst().centerLightYears());
            assertEquals(50_000, galaxies.getFirst().radiusLightYears());
        }
        assertNotEquals(UniverseGenerator.galaxy(42, 1), UniverseGenerator.galaxy(43, 1));
    }

    @Test
    void orientationAndDensityUseOneAbsoluteCoordinateFrame() {
        for (GalaxyDescriptor galaxy : UniverseGenerator.galaxies(42)) {
            SpaceVector local = new SpaceVector(galaxy.radiusLightYears() * 0.3,
                    galaxy.thicknessLightYears() * 0.4, -galaxy.radiusLightYears() * 0.2);
            SpaceVector absolute = galaxy.toUniverseLightYears(local);
            assertEquals(0, galaxy.toLocalLightYears(absolute).distance(local), 1e-8);
            assertEquals(0, galaxy.toLocalLightYears(galaxy.centerLightYears()).length(), 1e-12);
            double centerDensity = galaxy.density(galaxy.centerLightYears());
            assertTrue(centerDensity > 0.3 && centerDensity <= 1);
            SpaceVector beyond = galaxy.toUniverseLightYears(new SpaceVector(galaxy.radiusLightYears() * 2, 0, 0));
            assertEquals(0, galaxy.density(beyond));
            assertEquals(0, UniverseGenerator.populationDensity(42, galaxy.index(), beyond));
        }
        GalaxyDescriptor spiral = UniverseGenerator.galaxy(42, 0);
        GalaxyDescriptor elliptical = UniverseGenerator.galaxy(42, 2);
        assertEquals(0, spiral.density(spiral.toUniverseLightYears(new SpaceVector(0, spiral.radiusLightYears() * 0.5, 0))));
        assertTrue(elliptical.density(elliptical.toUniverseLightYears(
                new SpaceVector(0, elliptical.radiusLightYears() * 0.5, 0))) > 0);
    }

    @Test
    void everyRegionIsARealNavigableSystemAndQuasarBelongsToAnActiveNucleus() {
        Set<String> identities = new HashSet<>();
        for (GalaxyDescriptor galaxy : UniverseGenerator.galaxies(42)) {
            List<CosmicRegion> regions = UniverseGenerator.regions(42, galaxy.index());
            List<CosmosSystem> systems = UniverseGenerator.landmarkSystems(42, galaxy.index());
            assertEquals(UniverseGenerator.REGION_COUNT, regions.size());
            assertEquals(regions.size(), systems.size());
            assertThrows(UnsupportedOperationException.class, () -> regions.clear());
            for (CosmicRegion region : regions) {
                CosmosSystem system = systems.get(region.index());
                assertTrue(identities.add(system.id()));
                assertEquals(region.systemId(), system.id());
                assertEquals(region.centerLightYears(), system.galaxyPosition());
                assertEquals(system, UniverseGenerator.byId(42, system.id()));
                assertEquals(system, CosmosGenerator.byId(42, system.id()));
                assertEquals(galaxy.index(), UniverseGenerator.galaxyIndex(system.id()));
                assertTrue(UniverseGenerator.isAtlasSystemId(system.id()));
                assertTrue(system.bodies().size() >= 1 && system.bodies().size() <= CosmosSystem.MAX_BODIES);
                assertEquals(1, region.influence(region.centerLightYears()));
                assertTrue(UniverseGenerator.populationDensity(42, galaxy.index(), region.centerLightYears()) >= 0.25);
                if (region.kind() == CosmicRegion.Kind.QUASAR) {
                    assertTrue(galaxy.activeNucleus());
                    assertEquals(0, region.index());
                }
                for (CelestialBody body : system.bodies()) {
                    assertTrue(body.orbitMeters() * (1 + body.eccentricity())
                            + FlightDynamics.safeRadius(body) < FlightDynamics.LOCAL_RADIUS);
                    assertTrue(FlightDynamics.observation(system, body, 0).position().length() < FlightDynamics.LOCAL_RADIUS);
                }
            }
            CosmosSystem nucleus = systems.getFirst();
            assertEquals(CosmosSystem.Kind.BLACK_HOLE, nucleus.kind());
            assertEquals(CelestialBody.Kind.BLACK_HOLE, nucleus.bodies().getFirst().kind());
            assertTrue(nucleus.bodies().stream().filter(body -> body.kind() == CelestialBody.Kind.STAR).count() >= 7);
            assertTrue(nucleus.bodies().getFirst().radiusMeters() <= 1e12,
                    "The extended luminous nucleus must never become a galaxy-sized event horizon");
            assertEquals(CosmosSystem.Kind.SUPERNOVA, systems.get(5).kind());
        }
        assertEquals(2953 * 4_300_000.0, UniverseGenerator.byId(42, "u_0_0").bodies().getFirst().radiusMeters());
        assertEquals(CosmicRegion.Kind.QUASAR, UniverseGenerator.regions(42, 5).getFirst().kind());
    }

    @Test
    void localPopulationSharesStablePositionsWithLookupAndFollowsRegions() {
        long seed = 42;
        GalaxyDescriptor galaxy = UniverseGenerator.galaxy(seed, 1);
        List<CosmosSystem> population = UniverseGenerator.nearby(seed, 1, galaxy.centerLightYears(), 2);
        assertTrue(!population.isEmpty() && population.size() <= 125);
        assertEquals(population, UniverseGenerator.nearby(seed, UniverseGenerator.byId(seed, "u_1_0"), 2));
        double priorDistance = -1;
        for (CosmosSystem system : population) {
            assertTrue(system.id().startsWith("v_1_"));
            assertTrue(CosmosIds.isBuiltin(system.id()));
            assertFalse(UniverseGenerator.isAtlasSystemId(system.id()));
            assertEquals(system, UniverseGenerator.find(seed, system.id()).orElseThrow());
            assertTrue(UniverseGenerator.populationDensity(seed, 1, system.galaxyPosition()) > 0);
            double distance = system.galaxyPosition().distance(galaxy.centerLightYears());
            assertTrue(distance >= priorDistance);
            priorDistance = distance;
        }
        CosmicRegion halo = UniverseGenerator.regions(seed, 0).get(4);
        GalaxyDescriptor milkyWay = UniverseGenerator.galaxy(seed, 0);
        assertEquals(0, milkyWay.density(halo.centerLightYears()));
        assertTrue(UniverseGenerator.populationDensity(seed, 0, halo.centerLightYears()) >= 0.95,
                "A globular cluster must contain systems even outside the thin galactic disk");
        assertTrue(!UniverseGenerator.nearby(seed, 0, halo.centerLightYears(), 2).isEmpty());
        assertTrue(UniverseGenerator.find(seed, "v_1_100000_0_0").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> UniverseGenerator.byId(seed, "v_1_100000_0_0"));
        assertThrows(IllegalArgumentException.class, () -> UniverseGenerator.nearby(seed, 1, SpaceVector.ZERO, 3));
    }

    @Test
    void legacySystemsAndNeighborhoodsRemainExactlyUnchanged() {
        for (long seed : new long[]{0, 42, Long.MIN_VALUE, Long.MAX_VALUE}) {
            assertSame(CosmosGenerator.sol(), UniverseGenerator.byId(seed, "sol"));
            for (int[] coordinates : List.of(new int[]{1, -2, 3}, new int[]{-19, 7, 82},
                    new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE, 1})) {
                CosmosSystem original = CosmosGenerator.generate(seed, coordinates[0], coordinates[1], coordinates[2]);
                assertEquals(original, UniverseGenerator.find(seed, original.id()).orElseThrow());
            }
            assertEquals(CosmosGenerator.nearby(seed, SpaceVector.ZERO, 1),
                    UniverseGenerator.nearby(seed, CosmosGenerator.sol(), 1));
        }
    }

    @Test
    void universeIdentitiesAreStrictAndDoNotAliasCustomContent() {
        for (String id : List.of("u_0_0", "u_8_6", "v_0_0_0_0", "v_8_-2147483648_2147483647_0")) {
            assertTrue(UniverseGenerator.isUniverseId(id));
            assertTrue(CosmosIds.isBuiltin(id));
            assertFalse(CosmosIds.isCustom(id));
        }
        for (String id : List.of("u_00_0", "u_0_00", "u_9_0", "u_0_7", "u_-1_0", "u_+1_0",
                "v_1_00_0_0", "v_1_-0_0_0", "v_1_+1_0_0", "v_1_0_0", "v_1_2147483648_0_0",
                "mod:u_0_0", "sol", "s_1_0_0")) {
            assertFalse(UniverseGenerator.isUniverseId(id), id);
            assertFalse(UniverseGenerator.isAtlasSystemId(id), id);
        }
        assertTrue(CosmosIds.isCustom("mod:u_0_0"));
        assertFalse(UniverseGenerator.isUniverseId(null));
        assertThrows(IllegalArgumentException.class, () -> UniverseGenerator.find(42, "mod:u_0_0"));
        assertThrows(IllegalArgumentException.class, () -> UniverseGenerator.galaxy(42, 9));
        assertThrows(IllegalArgumentException.class, () -> UniverseGenerator.galaxyIndex("sol"));
    }
}
