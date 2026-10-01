package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.nio.FloatBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiverAtlasTest {
    private static final ContinentalTerrain TERRAIN = new ContinentalTerrain(3, ContinentalTerrain.SEED);
    private static final RiverAtlas ATLAS = TERRAIN.rivers().orElseThrow();

    @Test
    void everyLandCellDrainsToOceanWithoutCyclesOrUphillWater() {
        byte[] state = new byte[ATLAS.cellCount()];
        for (int start = 0; start < state.length; start++) {
            int node = start;
            while (node >= 0 && state[node] == 0) {
                state[node] = 1;
                int next = ATLAS.downstream(node);
                if (next >= 0) {
                    assertTrue(ATLAS.waterMeters(next) <= ATLAS.waterMeters(node));
                    assertTrue(ATLAS.drainageArea(next) >= ATLAS.drainageArea(node));
                } else {
                    assertEquals(0, ATLAS.waterMeters(node));
                }
                node = next;
            }
            assertTrue(node < 0 || state[node] == 2, "Drainage cycle");
            node = start;
            while (node >= 0 && state[node] == 1) { state[node] = 2; node = ATLAS.downstream(node); }
        }
    }

    @Test
    void sampledChannelsConfluencesAndMouthsShareContinuousDownhillWater() {
        int count = 0, mouths = 0, elevated = 0;
        for (int cell = 0; cell < ATLAS.cellCount(); cell += 53) {
            if (!ATLAS.river(cell)) { continue; }
            int parent = ATLAS.downstream(cell);
            for (int step = 0; step <= 32; step++) {
                double t = step / 32.0;
                var sample = TERRAIN.sample(ATLAS.point(cell, t));
                assertTrue(sample.water(), "Dry centerline at " + cell + "/" + step);
                assertEquals(ATLAS.waterMeters(cell) * (1 - t) + ATLAS.waterMeters(parent) * t,
                        sample.waterMeters(), 1e-6);
            }
            if (ATLAS.downstream(parent) < 0) { mouths++; }
            if (ATLAS.waterMeters(cell) > 100) { elevated++; }
            count++;
        }
        assertTrue(count > 500 && mouths > 10 && elevated > 100);
    }

    @Test
    void aDeepExistingDepressionStillFillsToTheRoutedWaterLevel() {
        int cell = 0;
        while (!ATLAS.river(cell) || ATLAS.waterMeters(cell) < 100) { cell++; }
        var direction = ATLAS.point(cell, .5);
        double water = (ATLAS.waterMeters(cell) + ATLAS.waterMeters(ATLAS.downstream(cell))) * .5;
        var base = new ContinentalTerrain.Sample(water - 30, 15, .5, .5, 0);
        var filled = ATLAS.shape(direction, base);
        assertEquals(base.heightMeters(), filled.heightMeters());
        assertEquals(water, filled.waterMeters(), 1e-6);
        assertTrue(filled.river());
    }

    @Test
    void faceSeamsPolesAndLongitudeRemainContinuous() {
        for (SpaceVector direction : new SpaceVector[] {new SpaceVector(1, 1, .2), new SpaceVector(-1, .3, 1),
                new SpaceVector(.4, -1, -1), new SpaceVector(0, 1, 0), new SpaceVector(-1, 0, 0)}) {
            double reference = TERRAIN.sample(direction).heightMeters();
            assertEquals(reference, TERRAIN.sample(direction.add(new SpaceVector(1e-10, 0, 0))).heightMeters(), .01);
            assertEquals(reference, TERRAIN.sample(direction.add(new SpaceVector(0, 0, -1e-10))).heightMeters(), .01);
        }
    }

    @Test
    void adaptiveBanksKeepSharedEdgesAndBoundedGeometry() {
        var address = new GeographicPosition(.13065445567859293, .06186738614489537, 300);
        var chart = EarthChart.owner(address, 3).orElseThrow();
        var point = chart.resolve(address).orElseThrow();
        var mesh = ContinentalLandscape.bake(chart, point.x(), point.z(), () -> false);
        assertTrue(mesh.vertexCount() > 1 + ContinentalLandscape.RINGS * ContinentalLandscape.SECTORS);
        assertTrue(mesh.vertexCount() <= 60_000);
        var edges = new java.util.HashMap<Long, Integer>();
        for (int i = 0; i < mesh.indexCount(); i += 3) {
            for (int side = 0; side < 3; side++) {
                int a = mesh.vertexIndex(i + side), b = mesh.vertexIndex(i + (side + 1) % 3);
                edges.merge((long) Math.min(a, b) << 32 | Math.max(a, b), 1, Integer::sum);
            }
        }
        for (var edge : edges.entrySet()) {
            if (edge.getValue() == 1) {
                assertTrue(mesh.position((int) (edge.getKey() >>> 32)).length() > 1_500_000);
                assertTrue(mesh.position(edge.getKey().intValue()).length() > 1_500_000);
            } else { assertEquals(2, edge.getValue()); }
        }
        for (int i = 0; i < mesh.vertexCount(); i++) { assertEquals(1, mesh.normal(i).length(), 1e-6); }
    }

    @Test
    void orbitalSamplesExposeRaisedWaterAndPreserveLegacyMaps() {
        int cell = 0;
        while (!ATLAS.river(cell) || ATLAS.waterMeters(cell) < 100
                || TERRAIN.sample(ATLAS.point(cell, .5)).temperature() < 1) { cell++; }
        var direction = ATLAS.point(cell, .5);
        var grid = SurfaceHeightTile.Grid.at(direction, ContinentalTerrain.RADIUS_METERS, 3, 1);
        var map = ContinentalMap.tile(TERRAIN, grid, () -> false);
        FloatBuffer buffer = FloatBuffer.allocate(36); map.writeTo(buffer);
        var sample = TERRAIN.sample(direction);
        assertEquals(sample.waterMeters(), buffer.get(16), .001);
        assertEquals(-1, buffer.get(19));
        assertEquals(EarthClimate.OCEAN, EarthSurfacePalette.material(sample));
        assertNotEquals(TERRAIN, new ContinentalTerrain(2, ContinentalTerrain.SEED));
        assertEquals(2, EarthChart.all(2).getFirst().terrainVersion());
        assertEquals(3, EarthChart.all(3).getFirst().terrainVersion());
    }
}
