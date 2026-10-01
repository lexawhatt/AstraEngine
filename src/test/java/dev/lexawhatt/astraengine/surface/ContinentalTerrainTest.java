package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinentalTerrainTest {
    @Test
    void versionOneRetainsPinnedPhysicalSamples() {
        ContinentalTerrain terrain = terrain(ContinentalTerrain.SEED);
        assertEquals(713.2982624938669, terrain.sample(new SpaceVector(1, 0, 0)).heightMeters(), 1e-8);
        assertEquals(-5669.689858287963, terrain.sample(new SpaceVector(0, 1, 0)).heightMeters(), 1e-8);
        assertEquals(-2116.8825269004406, terrain.sample(new SpaceVector(0, 0, 1)).heightMeters(), 1e-8);
        assertEquals(.632917938318619, terrain.sample(new SpaceVector(1, 0, 0)).moisture(), 1e-12);
    }

    @Test
    void samplesAreDeterministicAcrossInstancesOrderingAndSeeds() {
        ContinentalTerrain first = terrain(ContinentalTerrain.SEED);
        ContinentalTerrain same = terrain(ContinentalTerrain.SEED);
        ContinentalTerrain different = terrain(ContinentalTerrain.SEED + 1);
        int changed = 0;
        for (int i = 0; i < 128; i++) {
            SpaceVector direction = globeDirection(i, 128);
            ContinentalTerrain.Sample expected = first.sample(direction);
            first.sample(globeDirection(127 - i, 128));
            assertEquals(expected, first.sample(direction));
            assertEquals(expected, same.sample(direction));
            if (Math.abs(expected.heightMeters() - different.sample(direction).heightMeters()) > 1) { changed++; }
        }
        assertTrue(changed > 120, "Distinct seeds must change actual geography");
    }

    @Test
    void directionScaleAndSurfaceAltitudeDoNotChangeTheGeographicField() {
        ContinentalTerrain terrain = terrain(ContinentalTerrain.SEED);
        SpaceVector direction = new SpaceVector(3, -4, 5);
        ContinentalTerrain.Sample expected = terrain.sample(direction);
        assertClose(expected, terrain.sample(direction.multiply(1e-250)));
        assertClose(expected, terrain.sample(direction.multiply(1e250)));
        GeographicPosition position = new GeographicPosition(.3, -1.7, -6000);
        assertClose(terrain.sample(position.toBody(ContinentalTerrain.RADIUS_METERS)),
                terrain.sample(new GeographicPosition(.3, -1.7, 9000).toBody(ContinentalTerrain.RADIUS_METERS)));
    }

    @Test
    void longitudeSeamAndPolarLimitsAreContinuous() {
        ContinentalTerrain terrain = terrain(ContinentalTerrain.SEED);
        double epsilon = 1e-10;
        for (double latitude : new double[]{-1.4, -.7, 0, .7, 1.4}) {
            assertClose(terrain.sample(new GeographicPosition(latitude, -Math.PI + epsilon, 0).normal()),
                    terrain.sample(new GeographicPosition(latitude, Math.PI - epsilon, 0).normal()));
        }
        for (int sign : new int[]{-1, 1}) {
            ContinentalTerrain.Sample pole = terrain.sample(new SpaceVector(0, sign, 0));
            for (double longitude : new double[]{-Math.PI, -2, -1, 0, 1, 2, Math.PI - epsilon}) {
                assertClose(pole, terrain.sample(new GeographicPosition(sign * (Math.PI / 2 - epsilon),
                        longitude, 0).normal()));
            }
        }
    }

    @Test
    void multipleSeedsRetainFinitePhysicalRangesWithoutHostHeightCompression() {
        for (long seed : new long[]{ContinentalTerrain.SEED, 0, -1, Long.MIN_VALUE}) {
            ContinentalTerrain terrain = terrain(seed);
            double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
            int ocean = 0;
            for (int i = 0; i < 8192; i++) {
                ContinentalTerrain.Sample sample = terrain.sample(globeDirection(i, 8192));
                assertBounds(sample);
                minimum = Math.min(minimum, sample.heightMeters());
                maximum = Math.max(maximum, sample.heightMeters());
                if (sample.heightMeters() < 0) { ocean++; }
            }
            assertTrue(minimum < -5000, "A physical abyssal basin is missing");
            assertTrue(maximum > 7500, "Kilometer-scale mountains were lost or compressed into host height");
            assertTrue(ocean > 8192 * .45 && ocean < 8192 * .85, "Seed must retain both continents and oceans");
        }
    }

    @Test
    void defaultGlobeHasPredominantOceanShelvesLowlandsAndRareTallPeaks() {
        ContinentalTerrain terrain = terrain(ContinentalTerrain.SEED);
        int count = 65536, land = 0, shelf = 0, lowland = 0, tall = 0;
        double maximum = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            double height = terrain.sample(globeDirection(i, count)).heightMeters();
            if (height > 0) { land++; }
            if (height <= 0 && height >= -200) { shelf++; }
            if (height > 0 && height < 500) { lowland++; }
            if (height > 7000) { tall++; }
            maximum = Math.max(maximum, height);
        }
        assertTrue(land > count * .25 && land < count * .40, "Default planet should have Earth-like ocean coverage");
        assertTrue(shelf > count * .02 && shelf < count * .10, "Shelf must form a meaningful minority of the globe");
        assertTrue(lowland > land * .35, "Continents need substantial habitable lowlands, not only mountain spikes");
        assertTrue(tall > 20 && tall < count * .02, "Tall peaks should exist but occupy a small geographic fraction");
        assertTrue(maximum > 8500 && maximum < 10000, "Default global field should contain approximately 9 km peaks");
    }

    @Test
    void nearbyLandClassificationRemainsCoherentAcrossTensOfKilometers() {
        ContinentalTerrain terrain = terrain(ContinentalTerrain.SEED);
        int coherent = 0, observations = 2048;
        for (int i = 0; i < observations; i++) {
            SpaceVector direction = globeDirection(i, observations);
            GeographicPosition geographic = GeographicPosition.fromBody(direction.multiply(ContinentalTerrain.RADIUS_METERS),
                    ContinentalTerrain.RADIUS_METERS);
            SurfacePatch patch = new SurfacePatch(ContinentalTerrain.RADIUS_METERS, geographic.latitudeRadians(),
                    geographic.longitudeRadians(), 32768, 0);
            boolean land = terrain.sample(direction).heightMeters() > 0;
            if (land == (terrain.sample(patch.normal(30000, 0)).heightMeters() > 0)) { coherent++; }
        }
        assertTrue(coherent > observations * .95, "Continental geography should not turn into isolated local islands");
    }

    @Test
    void coastAlpineAndAbyssAnchorsRetainPhysicalReliefInSeparateStorageWindows() {
        ContinentalTerrain terrain = terrain(ContinentalTerrain.SEED);
        SurfacePatch coast = patch(-.08572201151496467, -2.925633766709961, 16384);
        SurfacePatch alpine = patch(-.5452579002925138, -3.1402788132428276, 8192);
        SurfacePatch abyss = patch(-.48544106217054683, -.01221846648034751, 16384);
        double[] coastRange = range(terrain, coast), alpineRange = range(terrain, alpine), abyssRange = range(terrain, abyss);
        assertTrue(coastRange[0] < -30 && coastRange[1] > 100, "Coast anchor needs actual sea and land nearby");
        assertTrue(alpineRange[0] > 7168 - 2032 && alpineRange[1] < 7168 + 2032);
        assertTrue(alpineRange[1] > 8500 && alpineRange[1] - alpineRange[0] > 2500);
        assertTrue(abyssRange[0] < -5500 && abyssRange[1] < -5000);
        assertTrue(abyssRange[0] > -5120 - 2032 && abyssRange[1] < -5120 + 2032);
        assertTrue(terrain.sample(alpine.normal(0, 0)).temperature() < -30);
        assertTrue(terrain.sample(coast.normal(0, 0)).temperature() > 25);
    }

    @Test
    void latitudeClimateHasColdPolesAndWetterTropicsWithoutHeatingDeepOcean() {
        ContinentalTerrain terrain = terrain(ContinentalTerrain.SEED);
        double equatorialMoisture = 0, subtropicalMoisture = 0;
        for (int i = 0; i < 512; i++) {
            double longitude = -Math.PI + 2 * Math.PI * (i + .5) / 512;
            ContinentalTerrain.Sample equator = terrain.sample(new GeographicPosition(0, longitude, 0).normal());
            ContinentalTerrain.Sample subtropics = terrain.sample(new GeographicPosition(Math.asin(.48), longitude, 0).normal());
            equatorialMoisture += equator.moisture();
            subtropicalMoisture += subtropics.moisture();
            assertTrue(equator.temperature() <= 31, "Deep ocean elevation must not apply a negative lapse rate");
        }
        assertTrue(equatorialMoisture / 512 > subtropicalMoisture / 512 + .20);
        assertTrue(terrain.sample(new SpaceVector(0, 1, 0)).temperature() <= -17);
        assertTrue(terrain.sample(new SpaceVector(0, -1, 0)).temperature() <= -17);
    }

    @Test
    void oneMeterStepsRemainContinuousAcrossLocalRelief() {
        ContinentalTerrain terrain = terrain(ContinentalTerrain.SEED);
        SurfacePatch alpine = patch(-.5452579002925138, -3.1402788132428276, 8192);
        for (int x = -8000; x <= 8000; x += 125) {
            for (int z = -8000; z <= 8000; z += 125) {
                double here = terrain.sample(alpine.normal(x, z)).heightMeters();
                double next = terrain.sample(alpine.normal(x + 1, z)).heightMeters();
                assertTrue(Math.abs(next - here) < 15, "Noise interpolation must not create discontinuous height walls");
            }
        }
    }

    @Test
    void mountainBoundaryRoundoffRetainsNormalizedWeights() {
        ContinentalTerrain terrain = terrain(ContinentalTerrain.SEED);
        // This actual map sample previously produced a quintic weight 1.000000000000001.
        double latitude = Math.PI * (.5 - (338 + .5) / 720);
        double longitude = Math.PI * (2 * (890 + .5) / 1440 - 1);
        ContinentalTerrain.Sample sample = terrain.sample(new GeographicPosition(latitude, longitude, 0).normal());
        assertBounds(sample);
        assertEquals(0, sample.mountainMask());
    }

    @Test
    void invalidVersionsDirectionsAndSampleValuesRejectExplicitly() {
        ContinentalTerrain terrain = terrain(ContinentalTerrain.SEED);
        assertThrows(IllegalArgumentException.class, () -> new ContinentalTerrain(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ContinentalTerrain(ContinentalTerrain.CURRENT_VERSION + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> terrain.sample(null));
        assertThrows(IllegalArgumentException.class, () -> terrain.sample(SpaceVector.ZERO));
        for (double height : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -7001, 10001}) {
            assertThrows(IllegalArgumentException.class, () -> new ContinentalTerrain.Sample(height, 15, .5, .2, .3));
        }
        assertThrows(IllegalArgumentException.class, () -> new ContinentalTerrain.Sample(0, Double.NaN, .5, .2, .3));
        assertThrows(IllegalArgumentException.class, () -> new ContinentalTerrain.Sample(0, 15, 1.1, .2, .3));
        assertThrows(IllegalArgumentException.class, () -> new ContinentalTerrain.Sample(0, 15, .5, -1.1, .3));
        assertThrows(IllegalArgumentException.class, () -> new ContinentalTerrain.Sample(0, 15, .5, .2, Double.NaN));
    }

    private static ContinentalTerrain terrain(long seed) { return new ContinentalTerrain(ContinentalTerrain.VERSION, seed); }

    private static SurfacePatch patch(double latitude, double longitude, int halfWidth) {
        return new SurfacePatch(ContinentalTerrain.RADIUS_METERS, latitude, longitude, halfWidth, 0);
    }

    private static SpaceVector globeDirection(int index, int count) {
        double y = -1 + 2 * (index + .5) / count;
        double angle = index * Math.PI * (3 - Math.sqrt(5));
        double horizontal = Math.sqrt(1 - y * y);
        return new SpaceVector(horizontal * Math.cos(angle), y, horizontal * Math.sin(angle));
    }

    private static double[] range(ContinentalTerrain terrain, SurfacePatch patch) {
        double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
        for (int x = -patch.halfWidth(); x <= patch.halfWidth(); x += 128) {
            for (int z = -patch.halfWidth(); z <= patch.halfWidth(); z += 128) {
                double height = terrain.sample(patch.normal(x, z)).heightMeters();
                minimum = Math.min(minimum, height);
                maximum = Math.max(maximum, height);
            }
        }
        return new double[]{minimum, maximum};
    }

    private static void assertClose(ContinentalTerrain.Sample first, ContinentalTerrain.Sample second) {
        assertEquals(first.heightMeters(), second.heightMeters(), .01);
        assertEquals(first.temperature(), second.temperature(), .0001);
        assertEquals(first.moisture(), second.moisture(), .0001);
        assertEquals(first.continentality(), second.continentality(), .00001);
        assertEquals(first.mountainMask(), second.mountainMask(), .00001);
    }

    private static void assertBounds(ContinentalTerrain.Sample sample) {
        assertTrue(Double.isFinite(sample.heightMeters()) && sample.heightMeters() >= ContinentalTerrain.MIN_ELEVATION
                && sample.heightMeters() <= ContinentalTerrain.MAX_ELEVATION);
        assertTrue(Double.isFinite(sample.temperature()));
        assertTrue(sample.moisture() >= 0 && sample.moisture() <= 1);
        assertTrue(sample.continentality() >= -1 && sample.continentality() <= 1);
        assertTrue(sample.mountainMask() >= 0 && sample.mountainMask() <= 1);
    }
}
