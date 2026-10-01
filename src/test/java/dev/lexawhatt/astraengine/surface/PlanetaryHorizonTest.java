package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanetaryHorizonTest {
    private static final double EARTH_RADIUS = 6_371_000;
    private static final double MOON_RADIUS = 1_737_400;

    @Test
    void physicalHorizonDistanceAndDipAgreeForEarthAndMoon() {
        assertEquals(4654.18123, PlanetaryHorizon.horizonDistance(EARTH_RADIUS, 1.7), .00001);
        assertEquals(112884.89713, PlanetaryHorizon.horizonDistance(EARTH_RADIUS, 1000), .00001);
        assertEquals(2430.46557, PlanetaryHorizon.horizonDistance(MOON_RADIUS, 1.7), .00001);
        for (double radius : new double[]{MOON_RADIUS, EARTH_RADIUS, 1e12}) {
            for (double altitude : new double[]{1e-12, 1.7, 100, 1000, 100000}) {
                double distance = PlanetaryHorizon.horizonDistance(radius, altitude);
                double dip = PlanetaryHorizon.horizonDip(radius, altitude);
                assertEquals(distance / (radius + altitude), Math.sin(dip), 3e-16);
                assertTrue(dip > 0 && dip < Math.PI / 2);
            }
        }
        assertTrue(PlanetaryHorizon.horizonDip(MOON_RADIUS, 1.7)
                > PlanetaryHorizon.horizonDip(EARTH_RADIUS, 1.7));
    }

    @Test
    void tinyAltitudeAndWideFiniteRangeDoNotCancelOrOverflow() {
        for (double radius : new double[]{10, EARTH_RADIUS, 1e12}) {
            double tinyAltitude = 1e-12;
            assertEquals(Math.sqrt(2 * radius * tinyAltitude),
                    PlanetaryHorizon.horizonDistance(radius, tinyAltitude), 1e-14);
            assertEquals(tinyAltitude, PlanetaryHorizon.firstIntersection(radius, tinyAltitude,
                    new SpaceVector(0, -1, 0)).orElseThrow(), 1e-26);
            assertTrue(Double.isFinite(PlanetaryHorizon.horizonDistance(radius, Double.MAX_VALUE)));
            assertTrue(Double.isFinite(PlanetaryHorizon.firstIntersection(radius, Double.MAX_VALUE,
                    new SpaceVector(0, -1, 0)).orElseThrow()));
        }
    }

    @Test
    void analyticTangentsHitAndNearbySkyMissesAtBothPlanetaryScales() {
        for (double radius : new double[]{MOON_RADIUS, EARTH_RADIUS}) {
            for (double altitude : new double[]{1e-8, 1.7, 100, 1000, 100000, 1e9}) {
                double dip = PlanetaryHorizon.horizonDip(radius, altitude);
                double distance = PlanetaryHorizon.horizonDistance(radius, altitude);
                SpaceVector tangent = new SpaceVector(radius / (radius + altitude),
                        -distance / (radius + altitude), 0).normalized();
                double hit = PlanetaryHorizon.firstIntersection(radius, altitude, tangent)
                        .orElseThrow();
                assertEquals(distance, hit, Math.max(1e-7, distance * 2e-12));
                assertFalse(PlanetaryHorizon.firstIntersection(radius, altitude,
                        rayBelowHorizontal(dip - 1e-8)).isPresent());
                assertTrue(PlanetaryHorizon.firstIntersection(radius, altitude,
                        rayBelowHorizontal(dip + 1e-8)).orElseThrow() < distance);
            }
        }
    }

    @Test
    void zeroAltitudeHasExplicitSurfaceContactSemantics() {
        assertEquals(0, PlanetaryHorizon.horizonDistance(EARTH_RADIUS, 0));
        assertEquals(0, PlanetaryHorizon.horizonDip(EARTH_RADIUS, 0));
        assertEquals(0, PlanetaryHorizon.firstIntersection(EARTH_RADIUS, 0,
                new SpaceVector(0, -1, 0)).orElseThrow());
        assertEquals(0, PlanetaryHorizon.firstIntersection(EARTH_RADIUS, 0,
                new SpaceVector(1, 0, 0)).orElseThrow());
        assertFalse(PlanetaryHorizon.firstIntersection(EARTH_RADIUS, 0,
                new SpaceVector(0, 1, 0)).isPresent());
        assertFalse(PlanetaryHorizon.firstIntersection(EARTH_RADIUS, 1.7,
                new SpaceVector(1, 0, 0)).isPresent());
    }

    @Test
    void narrowDistantDiscUsesHorizontalRayComponentsWithoutLosingItsSilhouette() {
        double altitude = 1e12;
        double radius = 10;
        double angularRadius = radius / (radius + altitude);
        assertTrue(PlanetaryHorizon.firstIntersection(radius, altitude,
                new SpaceVector(angularRadius * .5, -1, 0).normalized()).isPresent());
        assertFalse(PlanetaryHorizon.firstIntersection(radius, altitude,
                new SpaceVector(angularRadius * 2, -1, 0).normalized()).isPresent());
    }

    @Test
    void projectedOceanFallsBelowTangentAndRetainsNearMeterOffsets() {
        SurfacePatch patch = new SurfacePatch(EARTH_RADIUS, 0, 0, 32768, 64);
        SpaceVector camera = new SpaceVector(0, 65.7, 0);
        SpaceVector close = PlanetaryHorizon.project(patch, camera, new SpaceVector(.1, 65.8, -.2));
        assertEquals(.1, close.x(), 1e-7);
        assertEquals(.1, close.y(), 1e-8);
        assertEquals(-.2, close.z(), 1e-7);
        SpaceVector sea = PlanetaryHorizon.project(patch, camera, new SpaceVector(10000, 64, 0));
        assertEquals(-1.7 - 10000.0 * 10000 / (2 * EARTH_RADIUS), sea.y(), .0001);
        assertEquals(0, PlanetaryHorizon.project(patch, camera, camera).length());
    }

    @Test
    void projectionAtOffAxisColumnsAndPolesRetainsExactPatchMapping() {
        for (double latitude : new double[]{-Math.PI / 2, -.8, 0, .9, Math.PI / 2}) {
            SurfacePatch patch = new SurfacePatch(EARTH_RADIUS, latitude, -1.7, 32768, -32);
            SpaceVector camera = new SpaceVector(21000, 178.25, -31000);
            SpaceVector point = new SpaceVector(-25000, 530.5, 17500);
            SpaceVector bodyDifference = patch.toBody(point).subtract(patch.toBody(camera));
            SpaceVector relative = PlanetaryHorizon.project(patch, camera, point);
            assertEquals(bodyDifference.length(), relative.length(), 1e-8);
            assertTrue(bodyDifference.distance(patch.toBodyDirection(camera.x(), camera.z(), relative)) < 1e-8);
        }
    }

    @Test
    void changingPatchAnchorPreservesTheSameBodyObserverAndApparentHorizon() {
        SurfacePatch first = new SurfacePatch(EARTH_RADIUS, .7, -1.5, 32768, 64);
        SurfacePatch second = new SurfacePatch(EARTH_RADIUS, .705, -1.497, 32768, 0);
        SpaceVector firstCamera = new SpaceVector(15230, 65.7, -28910);
        SpaceVector firstPoint = new SpaceVector(-4200, 164, -8750);
        SpaceVector secondCamera = second.toLocal(first.toBody(firstCamera));
        SpaceVector secondPoint = second.toLocal(first.toBody(firstPoint));
        assertTrue(PlanetaryHorizon.project(first, firstCamera, firstPoint)
                .distance(PlanetaryHorizon.project(second, secondCamera, secondPoint)) < 1e-8);
        assertEquals(PlanetaryHorizon.horizonDip(EARTH_RADIUS, firstCamera.y() - first.seaY()),
                PlanetaryHorizon.horizonDip(EARTH_RADIUS, secondCamera.y() - second.seaY()), 1e-12);
    }

    @Test
    void towerDisappearsBottomFirstAndRaisingObserverRevealsItsBase() {
        SurfacePatch patch = new SurfacePatch(EARTH_RADIUS, .5, 1.2, 32768, 0);
        SpaceVector groundCamera = new SpaceVector(0, 1.7, 0);
        SpaceVector highCamera = new SpaceVector(0, 100, 0);
        SpaceVector towerBase = new SpaceVector(10000, 1, 0);
        SpaceVector towerTop = new SpaceVector(10000, 100, 0);
        assertTrue(occluded(patch, groundCamera, towerBase));
        assertFalse(occluded(patch, groundCamera, towerTop));
        assertFalse(occluded(patch, highCamera, towerBase));
        assertFalse(occluded(patch, highCamera, towerTop));
    }

    @Test
    void invalidGeometryRejectsInsteadOfInventingAReferenceSurface() {
        SpaceVector downward = new SpaceVector(0, -1, 0);
        for (double radius : new double[]{0, -1, 1e13, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> PlanetaryHorizon.horizonDistance(radius, 1));
            assertThrows(IllegalArgumentException.class, () -> PlanetaryHorizon.horizonDip(radius, 1));
            assertThrows(IllegalArgumentException.class, () -> PlanetaryHorizon.firstIntersection(radius, 1, downward));
        }
        for (double altitude : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> PlanetaryHorizon.horizonDistance(EARTH_RADIUS, altitude));
            assertThrows(IllegalArgumentException.class, () -> PlanetaryHorizon.horizonDip(EARTH_RADIUS, altitude));
            assertThrows(IllegalArgumentException.class,
                    () -> PlanetaryHorizon.firstIntersection(EARTH_RADIUS, altitude, downward));
        }
        for (SpaceVector ray : new SpaceVector[]{null, SpaceVector.ZERO, new SpaceVector(0, -2, 0)}) {
            assertThrows(IllegalArgumentException.class, () -> PlanetaryHorizon.firstIntersection(EARTH_RADIUS, 1, ray));
        }
        SurfacePatch patch = new SurfacePatch(EARTH_RADIUS, 0, 0, 32768, 0);
        assertThrows(IllegalArgumentException.class, () -> PlanetaryHorizon.project(null, SpaceVector.ZERO, SpaceVector.ZERO));
        assertThrows(IllegalArgumentException.class, () -> PlanetaryHorizon.project(patch, null, SpaceVector.ZERO));
        assertThrows(IllegalArgumentException.class, () -> PlanetaryHorizon.project(patch, SpaceVector.ZERO, null));
        assertThrows(IllegalArgumentException.class, () -> PlanetaryHorizon.project(patch,
                new SpaceVector(0, -EARTH_RADIUS, 0), SpaceVector.ZERO));
    }

    private static SpaceVector rayBelowHorizontal(double dip) {
        return new SpaceVector(Math.cos(dip), -Math.sin(dip), 0);
    }

    private static boolean occluded(SurfacePatch patch, SpaceVector camera, SpaceVector target) {
        SpaceVector relative = PlanetaryHorizon.project(patch, camera, target);
        OptionalDouble intersection = PlanetaryHorizon.firstIntersection(patch.radiusMeters(), camera.y() - patch.seaY(),
                relative.normalized());
        return intersection.isPresent() && intersection.orElseThrow() < relative.length() - 1e-5;
    }
}
