package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.nio.FloatBuffer;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinentalTerrainV2Test {
    private final ContinentalTerrain terrain = new ContinentalTerrain(2, ContinentalTerrain.SEED);

    @Test
    void canonicalOriginAndReliefDistributionStayHabitableAndPlanetary() {
        assertEquals(584.6418021544503, terrain.sample(new SpaceVector(1, 0, 0)).heightMeters(), 1e-8);
        var origin = terrain.sample(new SpaceVector(1, 0, 0));
        assertTrue(origin.temperature() > 20 && origin.moisture() > .5);
        Random random = new Random(7311);
        int count = 30000, land = 0, mountains = 0;
        double low = 0, high = 0;
        for (int i = 0; i < count; i++) {
            double latitudeY = random.nextDouble() * 2 - 1;
            double longitude = random.nextDouble() * Math.PI * 2;
            double equatorial = Math.sqrt(1 - latitudeY * latitudeY);
            var normal = new SpaceVector(equatorial * Math.cos(longitude), latitudeY, equatorial * Math.sin(longitude));
            double height = terrain.sample(normal).heightMeters();
            if (height > 0) { land++; }
            if (height > 2400) { mountains++; }
            low = Math.min(low, height); high = Math.max(high, height);
            assertEquals(height, terrain.sample(normal.multiply(137)).heightMeters(), 1e-7);
        }
        assertTrue(land > count * .20 && land < count * .55);
        assertTrue(mountains > count * .005 && mountains < land * .3);
        assertTrue(low < -4500 && high > 7000);
    }

    @Test
    void geographicVersionsDoNotReinterpretOldAddressesOrMaps() {
        var address = new GeographicPosition(.8, -.35, 8000);
        var oldChart = EarthChart.owner(address).orElseThrow();
        var chart = EarthChart.owner(address, 2).orElseThrow();
        assertEquals(oldChart.dimensionId(), chart.dimensionId());
        assertEquals("astraengine:sol/earth/continental_v1", oldChart.geographyId());
        assertEquals("astraengine:sol/earth/continental_v2", chart.geographyId());
        assertEquals(oldChart.resolve(address), chart.resolve(address));
        assertEquals(36, EarthChart.all(2).size());
        var grid = SurfaceHeightTile.Grid.at(address.normal(), ContinentalTerrain.RADIUS_METERS, 65, 4);
        var data = FloatBuffer.allocate(65 * 65 * 4);
        ContinentalMap.tile(terrain, grid, () -> false).writeTo(data);
        for (int z = 0; z < 65; z++) {
            for (int x = 0; x < 65; x++) {
                double expected = terrain.sample(grid.direction(x * 4 - 128, z * 4 - 128)).heightMeters();
                assertEquals(expected, data.get((z * 65 + x) * 4), .001);
            }
        }
    }

    @Test
    void seamsAndPolesHaveNoGeographicDiscontinuity() {
        for (double latitude : new double[] {-.8, 0, .6, Math.PI / 2, -Math.PI / 2}) {
            var first = new GeographicPosition(latitude, -Math.PI + 1e-10, 0).normal();
            var second = new GeographicPosition(latitude, Math.PI - 1e-10, 0).normal();
            assertEquals(terrain.sample(first).heightMeters(), terrain.sample(second).heightMeters(), .002);
        }
        for (var face : CubeFace.values()) {
            var first = new EarthChart(face, 0, 2);
            var normal = first.normal(ContinentalTerrain.RADIUS_METERS, 0);
            var address = GeographicPosition.fromBody(normal.multiply(ContinentalTerrain.RADIUS_METERS), ContinentalTerrain.RADIUS_METERS);
            var owner = EarthChart.owner(address, 2).orElseThrow();
            var local = owner.resolve(address).orElseThrow();
            assertEquals(terrain.sample(normal).heightMeters(), terrain.sample(owner.normal(local.x(), local.z())).heightMeters(), 1e-7);
        }
    }
}
