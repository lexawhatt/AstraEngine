package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanetaryTerrainTest {
    @Test
    void seededSamplesAreRepeatableIndependentOfOrderAndDifferentBetweenSeeds() {
        PlanetaryTerrain first = terrain(PlanetaryTerrain.SEED);
        PlanetaryTerrain same = terrain(PlanetaryTerrain.SEED);
        PlanetaryTerrain other = terrain(PlanetaryTerrain.SEED + 1);
        int different = 0;
        for (int i = 0; i < 128; i++) {
            SpaceVector direction = globeDirection(i, 128);
            PlanetaryTerrain.Sample expected = first.sample(direction);
            first.sample(globeDirection(127 - i, 128));
            assertEquals(expected, same.sample(direction));
            assertEquals(expected, first.sample(direction));
            if (Math.abs(expected.heightMeters() - other.sample(direction).heightMeters()) > 1) { different++; }
        }
        assertTrue(different > 120, "A different world seed should change the geographic field");
    }

    @Test
    void longitudeSeamAndBothPolesRemainFiniteAndContinuous() {
        PlanetaryTerrain terrain = terrain(PlanetaryTerrain.SEED);
        double epsilon = 1e-10;
        for (double latitude : new double[]{-1.4, -0.7, 0, 0.7, 1.4}) {
            PlanetaryTerrain.Sample west = terrain.sample(new GeographicPosition(latitude, -Math.PI + epsilon, 0).normal());
            PlanetaryTerrain.Sample east = terrain.sample(new GeographicPosition(latitude, Math.PI - epsilon, 0).normal());
            assertClose(west, east);
        }
        for (int sign : new int[]{-1, 1}) {
            PlanetaryTerrain.Sample pole = terrain.sample(new SpaceVector(0, sign, 0));
            for (double longitude : new double[]{-Math.PI, -2, -1, 0, 1, 2, Math.PI - epsilon}) {
                PlanetaryTerrain.Sample nearby = terrain.sample(new GeographicPosition(
                        sign * (Math.PI / 2 - epsilon), longitude, 0).normal());
                assertClose(pole, nearby);
            }
        }
    }

    @Test
    void globalHeightAndClimateStayWithinFiniteSupportedRangesForDifferentSeeds() {
        for (long seed : new long[]{PlanetaryTerrain.SEED, 0, -1, Long.MIN_VALUE}) {
            PlanetaryTerrain terrain = terrain(seed);
            for (int i = 0; i < 4096; i++) {
                PlanetaryTerrain.Sample sample = terrain.sample(globeDirection(i, 4096));
                assertSampleBounds(sample);
                assertTrue(sample.heightMeters() > PlanetaryTerrain.MIN_Y);
                assertTrue(sample.heightMeters() < PlanetaryTerrain.MIN_Y + PlanetaryTerrain.HEIGHT - 2);
            }
        }
    }

    @Test
    void pinnedPatchContainsOceanAndMoreThanOneKilometerOfRelief() {
        PlanetaryTerrain terrain = terrain(PlanetaryTerrain.SEED);
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        int oceans = 0, mountains = 0, columns = 0;
        for (int x = -32760; x <= 32760; x += 512) {
            for (int z = -32760; z <= 32760; z += 512) {
                PlanetaryTerrain.Sample sample = terrain.sample(PlanetaryTerrain.PATCH.normal(x + 0.5, z + 0.5));
                assertSampleBounds(sample);
                double height = sample.heightMeters();
                minimum = Math.min(minimum, height);
                maximum = Math.max(maximum, height);
                if (height < PlanetaryTerrain.SEA_Y) { oceans++; }
                if (height >= 1000) { mountains++; }
                columns++;
            }
        }
        assertTrue(maximum >= 1000, "Pinned patch lacks a kilometer-high mountain");
        assertTrue(minimum < -20, "Pinned patch lacks a meaningful ocean basin");
        assertTrue(maximum - minimum > 1000, "Pinned patch relief is below one kilometer");
        assertTrue(oceans > columns / 100 && oceans < columns / 2, "Pinned patch lacks mixed land and ocean");
        assertTrue(mountains > 0 && mountains < columns / 2, "High peaks should be present but bounded in coverage");
    }

    @Test
    void firstAirUsesBlockCentersAndLeavesOutsideColumnsAtTheVoidBoundary() {
        PlanetaryTerrain terrain = terrain(PlanetaryTerrain.SEED);
        for (int[] column : new int[][]{{0, 0}, {-14840, -3576}, {-27128, 5896}, {-32768, 0}, {32767, 0}}) {
            double height = terrain.sample(PlanetaryTerrain.PATCH.normal(column[0] + 0.5, column[1] + 0.5)).heightMeters();
            assertEquals((int) Math.floor(PlanetaryTerrain.SEA_Y + height), terrain.firstAir(column[0], column[1]));
        }
        assertTrue(terrain.firstAir(-14840, -3576) >= 1000);
        assertTrue(terrain.firstAir(-27128, 5896) < PlanetaryTerrain.SEA_Y);
        for (int[] outside : new int[][]{{32768, 0}, {-32769, 0}, {0, 32768}, {0, -32769},
                {Integer.MAX_VALUE, Integer.MIN_VALUE}}) {
            assertEquals(PlanetaryTerrain.MIN_Y, terrain.firstAir(outside[0], outside[1]));
        }
    }

    @Test
    void directionMagnitudeDoesNotChangeGeographyAndInvalidContractsAreRejected() {
        PlanetaryTerrain terrain = terrain(PlanetaryTerrain.SEED);
        SpaceVector direction = new SpaceVector(3, -4, 5);
        PlanetaryTerrain.Sample expected = terrain.sample(direction);
        assertClose(expected, terrain.sample(direction.multiply(1e-250)));
        assertClose(expected, terrain.sample(direction.multiply(1e250)));
        assertThrows(IllegalArgumentException.class, () -> terrain.sample(null));
        assertThrows(IllegalArgumentException.class, () -> terrain.sample(SpaceVector.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PlanetaryTerrain(PlanetaryTerrain.VERSION + 1, 0));
    }

    private static PlanetaryTerrain terrain(long seed) { return new PlanetaryTerrain(PlanetaryTerrain.VERSION, seed); }

    private static SpaceVector globeDirection(int index, int count) {
        double y = -1 + 2 * (index + 0.5) / count;
        double horizontal = Math.sqrt(1 - y * y);
        double angle = index * Math.PI * (3 - Math.sqrt(5));
        return new SpaceVector(horizontal * Math.cos(angle), y, horizontal * Math.sin(angle));
    }

    private static void assertClose(PlanetaryTerrain.Sample first, PlanetaryTerrain.Sample second) {
        assertSampleBounds(first);
        assertSampleBounds(second);
        assertEquals(first.heightMeters(), second.heightMeters(), 0.01);
        assertEquals(first.temperature(), second.temperature(), 0.0001);
        assertEquals(first.moisture(), second.moisture(), 0.0001);
    }

    private static void assertSampleBounds(PlanetaryTerrain.Sample sample) {
        assertTrue(Double.isFinite(sample.heightMeters()) && sample.heightMeters() >= PlanetaryTerrain.MIN_ELEVATION
                && sample.heightMeters() <= PlanetaryTerrain.MAX_ELEVATION);
        assertTrue(Double.isFinite(sample.temperature()));
        assertTrue(Double.isFinite(sample.moisture()) && sample.moisture() >= 0 && sample.moisture() <= 1);
    }
}
