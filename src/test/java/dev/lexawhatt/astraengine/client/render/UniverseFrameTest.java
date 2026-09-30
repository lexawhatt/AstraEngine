package dev.lexawhatt.astraengine.client.render;

import dev.lexawhatt.astraengine.cosmos.CosmicRegion;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.GalaxyDescriptor;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.UniverseGenerator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniverseFrameTest {
    @Test
    void allGalaxyKindsShareBoundedFiniteCameraRelativeFrames() {
        for (long seed : new long[] {0, 271, Long.MIN_VALUE, Long.MAX_VALUE}) {
            List<GalaxyDescriptor> galaxies = UniverseGenerator.galaxies(seed);
            List<UniverseFrame.RegionSource> regions = regions(seed, galaxies);
            for (SpaceVector observer : List.of(SpaceVector.ZERO, new SpaceVector(1e6, -1e6, 1e6))) {
                for (int quality = 0; quality <= 2; quality++) {
                    UniverseFrame frame = UniverseFrame.extract(galaxies, regions, observer, quality);
                    assertEquals(9, frame.galaxies().size());
                    assertTrue(frame.regions().size() <= 12 + quality * 6);
                    assertEquals(3, frame.galaxies().stream().map(g -> g.descriptor().kind()).distinct().count());
                    for (UniverseFrame.Galaxy galaxy : frame.galaxies()) {
                        assertFinite(galaxy.observerRadii());
                        assertTrue(galaxy.seed() >= 0 && galaxy.seed() < 4096);
                        SpaceVector restored = galaxy.descriptor().toUniverseLightYears(
                                galaxy.observerRadii().multiply(galaxy.descriptor().radiusLightYears()));
                        assertEquals(0, observer.distance(restored), 1e-8);
                    }
                    for (UniverseFrame.Region region : frame.regions()) {
                        assertFinite(region.observerRadii());
                        assertTrue(region.visibility() >= 0 && region.visibility() <= 1);
                        assertTrue(region.galaxySlot() >= 0 && region.galaxySlot() < 9);
                    }
                }
            }
        }
    }

    @Test
    void regionAnchorUsesItsHostAxesAndInactiveNucleusNeverGetsQuasarLight() {
        long seed = 271;
        var galaxies = UniverseGenerator.galaxies(seed);
        var regions = regions(seed, galaxies);
        CosmicRegion target = UniverseGenerator.regions(seed, 5).getFirst();
        UniverseFrame frame = UniverseFrame.extract(galaxies, regions, target.centerLightYears(), 2);
        UniverseFrame.Region nucleus = frame.regions().stream().filter(r -> r.descriptor().equals(target))
                .findFirst().orElseThrow();
        assertEquals(SpaceVector.ZERO, nucleus.observerRadii());
        assertEquals(CosmicRegion.Kind.QUASAR, nucleus.descriptor().kind());
        assertEquals(5, frame.galaxies().get(nucleus.galaxySlot()).descriptor().index());
        assertFalse(frame.regions().stream().anyMatch(region -> region.descriptor().systemId().equals("u_0_0")));
    }

    @Test
    void fractionalMotionIsContinuousAndSystemRebaseDoesNotAffectExtraction() {
        long seed = 8;
        var galaxies = UniverseGenerator.galaxies(seed);
        var regions = regions(seed, galaxies);
        SpaceVector absolute = new SpaceVector(19000, 120, -14);
        UniverseFrame first = UniverseFrame.extract(galaxies, regions, absolute, 1);
        SpaceVector origin = new SpaceVector(-2400, 100, 300);
        SpaceVector meters = absolute.subtract(origin).multiply(CosmosGenerator.LIGHT_YEAR);
        UniverseFrame rebased = UniverseFrame.extract(galaxies, regions,
                origin.add(meters.multiply(1 / CosmosGenerator.LIGHT_YEAR)), 1);
        assertEquals(first, rebased);
        UniverseFrame second = UniverseFrame.extract(galaxies, regions, absolute.add(new SpaceVector(0.001, 0, 0)), 1);
        for (int index = 0; index < 9; index++) {
            assertEquals(first.galaxies().get(index).descriptor(), second.galaxies().get(index).descriptor());
            assertTrue(first.galaxies().get(index).observerRadii()
                    .distance(second.galaxies().get(index).observerRadii()) < 1e-6);
        }
    }

    @Test
    void catalogPointsUseActualDescriptorsAndNeverDuplicateTheCurrentSystem() {
        long seed = 271;
        var galaxies = UniverseGenerator.galaxies(seed);
        var current = UniverseGenerator.byId(seed, "u_0_6");
        var observer = current.galaxyPosition();
        CatalogStarField cache = new CatalogStarField();
        var points = cache.extract(seed, current, observer, galaxies);
        assertTrue(points.size() <= CatalogStarField.LIMIT);
        assertFalse(points.isEmpty());
        for (CatalogStarField.Star point : points) {
            assertFalse(point.systemId().equals(current.id()));
            var descriptor = UniverseGenerator.byId(seed, point.systemId());
            assertEquals(0, descriptor.galaxyPosition().subtract(observer).normalized().distance(point.direction()), 1e-12);
            assertTrue(Float.isFinite(point.intensity()) && point.intensity() > 0);
        }
        assertEquals(points, cache.extract(seed, current, observer, galaxies));
        cache.clear();
        assertEquals(points, cache.extract(seed, current, observer, galaxies));
        assertTrue(cache.extract(seed, CosmosGenerator.sol(), SpaceVector.ZERO, galaxies).stream()
                .allMatch(star -> star.systemId().startsWith("s_")));
        var halo = UniverseGenerator.byId(seed, "u_0_4");
        assertFalse(cache.extract(seed, halo, halo.galaxyPosition(), galaxies).isEmpty());
        SpaceVector distantObserver = current.galaxyPosition().add(new SpaceVector(0.2, 0.1, -0.4));
        var fromSol = cache.extract(seed, CosmosGenerator.sol(), distantObserver, galaxies);
        var fromNucleus = cache.extract(seed, UniverseGenerator.byId(seed, "u_0_0"), distantObserver, galaxies);
        assertEquals(fromSol, fromNucleus);
    }

    @Test
    void malformedInputsAndMutableOutputsAreRejected() {
        var galaxies = UniverseGenerator.galaxies(1);
        assertThrows(IllegalArgumentException.class, () -> UniverseFrame.extract(null, List.of(), SpaceVector.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> UniverseFrame.extract(galaxies, List.of(), SpaceVector.ZERO, 3));
        UniverseFrame frame = UniverseFrame.extract(galaxies, regions(1, galaxies), SpaceVector.ZERO, 1);
        assertThrows(UnsupportedOperationException.class, () -> frame.galaxies().clear());
        assertThrows(UnsupportedOperationException.class, () -> frame.regions().clear());
    }

    private static List<UniverseFrame.RegionSource> regions(long seed, List<GalaxyDescriptor> galaxies) {
        List<UniverseFrame.RegionSource> result = new ArrayList<>();
        for (GalaxyDescriptor galaxy : galaxies) {
            for (CosmicRegion region : UniverseGenerator.regions(seed, galaxy.index())) {
                result.add(new UniverseFrame.RegionSource(galaxy, region));
            }
        }
        return result;
    }

    private static void assertFinite(SpaceVector vector) {
        assertTrue(Float.isFinite((float) vector.x()));
        assertTrue(Float.isFinite((float) vector.y()));
        assertTrue(Float.isFinite((float) vector.z()));
    }
}
