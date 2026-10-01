package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinentalRegionTest {
    @Test
    void permanentIdsAreStrictAndUnsupportedWorldsHaveNoImplicitBinding() {
        assertEquals(3, ContinentalRegion.values().length);
        for (ContinentalRegion region : ContinentalRegion.values()) {
            assertEquals(region, ContinentalRegion.byId(region.id()));
            assertEquals(region, ContinentalRegion.forDimension(region.dimensionId()).orElseThrow());
            assertEquals("astraengine:continental_" + region.id(), region.dimensionId());
        }
        for (String id : new String[]{null, "", "COAST", " coast", "astraengine:continental_coast", "missing"}) {
            assertThrows(IllegalArgumentException.class, () -> ContinentalRegion.byId(id));
        }
        for (String id : new String[]{null, "", "minecraft:overworld", "astraengine:terrain_highlands",
                "astraengine:surface_earth", "continental_coast", "other:continental_coast"}) {
            assertTrue(ContinentalRegion.forDimension(id).isEmpty());
        }
    }

    @Test
    void physicalAltitudeOffsetsTranslateMetersWithoutRescalingThePlanet() {
        for (ContinentalRegion region : ContinentalRegion.values()) {
            assertEquals(ContinentalTerrain.RADIUS_METERS, region.patch().radiusMeters());
            for (SpaceVector host : new SpaceVector[]{new SpaceVector(.5, 0, -.5),
                    new SpaceVector(2048.25, -1900.75, -4096.5), new SpaceVector(-1350, 1900, 400)}) {
                SpaceVector body = region.toBody(host);
                GeographicPosition geographic = GeographicPosition.fromBody(body, ContinentalTerrain.RADIUS_METERS);
                assertEquals(host.y() + region.altitudeOriginMeters(), geographic.altitudeMeters(), 2e-9);
                assertEquals(1, body.distance(region.toBody(host.add(new SpaceVector(0, 1, 0)))), 2e-9);
                assertTrue(body.normalized().distance(region.patch().normal(host.x(), host.z())) < 1e-14);
            }
        }
        assertEquals(7168, ContinentalRegion.ALPINE.altitudeOriginMeters());
        assertEquals(-5120, ContinentalRegion.ABYSS.altitudeOriginMeters());
        assertEquals(0, ContinentalRegion.COAST.altitudeOriginMeters());
    }

    @Test
    void hostBodyGeographicRoundTripsRetainPositionsAcrossTheLongitudeSeam() {
        for (ContinentalRegion region : ContinentalRegion.values()) {
            int halfWidth = region.patch().halfWidth();
            for (double x : new double[]{-halfWidth, -.5, 0, .5, halfWidth}) {
                for (double z : new double[]{-halfWidth, -.5, 0, .5, halfWidth}) {
                    SpaceVector host = new SpaceVector(x, 1523.125, z);
                    SpaceVector body = region.toBody(host);
                    GeographicPosition geographic = GeographicPosition.fromBody(body, ContinentalTerrain.RADIUS_METERS);
                    SpaceVector restored = region.toLocal(geographic.toBody(ContinentalTerrain.RADIUS_METERS));
                    assertTrue(host.distance(restored) < 1e-8, "Region conversion lost its permanent address");
                    assertTrue(region.patch().contains(x, z));
                }
            }
            assertFalse(region.patch().contains(halfWidth + .001, 0));
        }
        SpaceVector east = ContinentalRegion.ALPINE.toBody(new SpaceVector(8192, 0, 0));
        SpaceVector west = ContinentalRegion.ALPINE.toBody(new SpaceVector(-8192, 0, 0));
        double firstLongitude = GeographicPosition.fromBody(east, ContinentalTerrain.RADIUS_METERS).longitudeRadians();
        double secondLongitude = GeographicPosition.fromBody(west, ContinentalTerrain.RADIUS_METERS).longitudeRadians();
        assertTrue(firstLongitude * secondLongitude < 0, "Pinned alpine patch should exercise the longitude seam");
    }

    @Test
    void sharedBodyPositionsDoNotAcquireDifferentAltitudeWhenExpressedInAnotherWindow() {
        SpaceVector coastHost = new SpaceVector(512.5, 75.25, -312.5);
        SpaceVector body = ContinentalRegion.COAST.toBody(coastHost);
        SpaceVector alpineHost = ContinentalRegion.ALPINE.toLocal(body);
        assertTrue(body.distance(ContinentalRegion.ALPINE.toBody(alpineHost)) < 1e-8);
        assertEquals(75.25 - 7168, alpineHost.y(), 2e-9);
        assertFalse(ContinentalRegion.ALPINE.patch().contains(alpineHost.x(), alpineHost.z()),
                "Coordinate conversion must not imply that unrelated saved chunks occupy the same region");
    }

    @Test
    void storageWindowAndGlobalSeaRemainDistinctAtHighAndLowAltitude() {
        int min = ContinentalRegion.MIN_Y;
        int max = min + ContinentalRegion.HEIGHT;
        assertEquals(-2032, min);
        assertEquals(2032, max);
        assertEquals(0, Math.floorMod(min, 16));
        assertEquals(0, ContinentalRegion.HEIGHT % 16);
        for (ContinentalRegion region : ContinentalRegion.values()) {
            assertEquals(0, region.seaY() + region.altitudeOriginMeters());
            SpaceVector sea = region.toBody(new SpaceVector(0, region.seaY(), 0));
            assertEquals(ContinentalTerrain.RADIUS_METERS, sea.length(), 2e-9);
            SpaceVector bottom = region.toBody(new SpaceVector(0, min, 0));
            SpaceVector top = region.toBody(new SpaceVector(0, max, 0));
            assertEquals(ContinentalRegion.HEIGHT, top.distance(bottom), 2e-9);
        }
        assertTrue(ContinentalRegion.ALPINE.seaY() < min);
        assertTrue(ContinentalRegion.ABYSS.seaY() > max);
        assertTrue(ContinentalRegion.COAST.seaY() >= min && ContinentalRegion.COAST.seaY() < max);
    }

    @Test
    void firstAirSamplesBlockCentersAndDoesNotClampPhysicalGeographyToStorage() {
        ContinentalTerrain defaultField = new ContinentalTerrain(ContinentalTerrain.VERSION, ContinentalTerrain.SEED);
        ContinentalTerrain otherField = new ContinentalTerrain(ContinentalTerrain.VERSION, 0);
        boolean observedOutsideWindow = false;
        for (ContinentalRegion region : ContinentalRegion.values()) {
            for (int[] column : new int[][]{{0, 0}, {-7123, 5124}, {8100, -7140}}) {
                double physical = defaultField.sample(region.patch().normal(column[0] + .5, column[1] + .5)).heightMeters();
                assertEquals((int) Math.floor(physical) - region.altitudeOriginMeters(),
                        region.firstAir(defaultField, column[0], column[1]));
                int unbounded = region.firstAir(otherField, column[0], column[1]);
                observedOutsideWindow |= unbounded < ContinentalRegion.MIN_Y
                        || unbounded >= ContinentalRegion.MIN_Y + ContinentalRegion.HEIGHT;
            }
        }
        assertTrue(observedOutsideWindow, "Alternate pure fields must not be silently compressed into a host window");
    }

    @Test
    void missingFieldsAndUnrepresentablePositionsRejectExplicitly() {
        for (ContinentalRegion region : ContinentalRegion.values()) {
            assertThrows(IllegalArgumentException.class, () -> region.toBody(null));
            assertThrows(IllegalArgumentException.class, () -> region.toLocal(null));
            assertThrows(IllegalArgumentException.class, () -> region.toLocal(SpaceVector.ZERO));
            assertThrows(IllegalArgumentException.class, () -> region.firstAir(null, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> region.toBody(new SpaceVector(0,
                    -ContinentalTerrain.RADIUS_METERS - region.altitudeOriginMeters(), 0)));
        }
    }
}
