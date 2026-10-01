package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarthLandingTargetTest {
    @Test
    void radialLandingRetainsTheSelectedGeographyAcrossFacesPolesAndVersions() {
        for (int version : new int[] {1, 2}) {
            var terrain = new ContinentalTerrain(version, ContinentalTerrain.SEED);
            for (double latitude : new double[] {-Math.PI / 2, -1, -.4, 0, .5, 1, Math.PI / 2}) {
                for (double longitude : new double[] {-Math.PI, -2.5, -1, 0, .8, 2.5}) {
                    var expected = new GeographicPosition(latitude, longitude, 0);
                    var normal = expected.normal();
                    var result = EarthLandingTarget.aim(terrain, normal.multiply(EarthChart.RADIUS_METERS + 100_000),
                            normal.multiply(-1)).orElseThrow();
                    var address = result.chart().geographic(result.localFeet());
                    assertTrue(address.normal().distance(normal) < 1e-12);
                    assertEquals(Math.floor(Math.max(0, terrain.sample(normal).heightMeters())), address.altitudeMeters());
                    assertEquals(version, result.chart().terrainVersion());
                }
            }
        }
    }

    @Test
    void obliqueRayChoosesTheVisibleLocationInsteadOfTheObserverNadir() {
        var terrain = new ContinentalTerrain(2, ContinentalTerrain.SEED);
        var origin = new SpaceVector(EarthChart.RADIUS_METERS + 30_000, 0, 0);
        var aimed = new GeographicPosition(.004, .006, 0);
        double height = Math.max(0, terrain.sample(aimed.normal()).heightMeters());
        var target = new GeographicPosition(aimed.latitudeRadians(), aimed.longitudeRadians(), height)
                .toBody(EarthChart.RADIUS_METERS);
        var result = EarthLandingTarget.aim(terrain, origin, target.subtract(origin)).orElseThrow();
        var reached = result.chart().geographic(result.localFeet());
        assertTrue(reached.normal().distance(aimed.normal()) * EarthChart.RADIUS_METERS < .01);
        assertTrue(Math.abs(result.localFeet().x()) + Math.abs(result.localFeet().z()) > 10_000);
    }

    @Test
    void missesInteriorAndUnboundedRequestsNeverBecomeFallbackLandings() {
        var terrain = new ContinentalTerrain(2, ContinentalTerrain.SEED);
        var outside = new SpaceVector(EarthChart.RADIUS_METERS + 50_000, 0, 0);
        assertTrue(EarthLandingTarget.aim(terrain, outside, new SpaceVector(1, 0, 0)).isEmpty());
        assertTrue(EarthLandingTarget.aim(terrain, outside, new SpaceVector(0, 1, 0)).isEmpty());
        assertTrue(EarthLandingTarget.aim(terrain, new SpaceVector(1, 0, 0), new SpaceVector(-1, 0, 0)).isEmpty());
        assertTrue(EarthLandingTarget.aim(terrain, outside.multiply(10), new SpaceVector(-1, 0, 0)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> EarthLandingTarget.aim(terrain, outside, SpaceVector.ZERO));
    }

    @Test
    void delayedViewKeepsItsGeographicPointWhileTheObserverMovesWithRespectToEarth() {
        var terrain = new ContinentalTerrain(2, ContinentalTerrain.SEED);
        var normal = new GeographicPosition(.002, -.003, 0).normal();
        for (double angle : new double[] {-.008, -.002, 0, .002, .008}) {
            var observer = new GeographicPosition(angle, .001, 200_000).toBody(EarthChart.RADIUS_METERS);
            var result = EarthLandingTarget.visible(terrain, observer, normal).orElseThrow();
            assertTrue(result.chart().geographic(result.localFeet()).normal().distance(normal) < 1e-12);
        }
    }

    @Test
    void geographicProposalRejectsFarSideInteriorAndInvalidNormals() {
        var terrain = new ContinentalTerrain(2, ContinentalTerrain.SEED);
        var observer = new SpaceVector(EarthChart.RADIUS_METERS + 200_000, 0, 0);
        assertTrue(EarthLandingTarget.visible(terrain, observer, new SpaceVector(-1, 0, 0)).isEmpty());
        assertTrue(EarthLandingTarget.visible(terrain, observer, new SpaceVector(0, 1, 0)).isEmpty());
        assertTrue(EarthLandingTarget.visible(terrain, observer.multiply(10), new SpaceVector(1, 0, 0)).isEmpty());
        assertTrue(EarthLandingTarget.visible(terrain, new SpaceVector(1, 0, 0), new SpaceVector(1, 0, 0)).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> EarthLandingTarget.visible(terrain, observer, new SpaceVector(2, 0, 0)));
    }

    @Test
    void tangentFrameRetainsFullCameraOrientationAtEveryFaceAndAltitudeBand() {
        var view = FlightOrientation.fromAngles(42, -27, 19);
        for (var chart : EarthChart.all(2)) {
            var frame = chart.tangentFrame(30_000, -65_000, chart.altitudeOriginMeters() + 200);
            var restored = frame.toLocalOrientation(frame.toBodyOrientation(view));
            assertTrue(restored.forward().distance(view.forward()) < 1e-12);
            assertTrue(restored.up().distance(view.up()) < 1e-12);
            assertEquals(chart.altitudeOriginMeters() + 200,
                    frame.originMeters().length() - EarthChart.RADIUS_METERS, 1e-8);
        }
    }
}
