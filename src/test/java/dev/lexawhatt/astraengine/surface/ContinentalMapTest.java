package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.nio.FloatBuffer;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContinentalMapTest {
    private static final ContinentalTerrain TERRAIN = new ContinentalTerrain(1, ContinentalTerrain.SEED);

    @Test
    void globeTexelsAreCanonicalAndDuplicatePolesAndMeridianExactly() {
        var map = ContinentalMap.globe(TERRAIN, 65, () -> false);
        assertEquals(129, map.width());
        assertEquals(65, map.height());
        var data = FloatBuffer.allocate(map.width() * map.height() * 4);
        map.writeTo(data);
        assertEquals(data.capacity(), data.position());
        for (int row = 0; row < map.height(); row++) {
            for (int column = 0; column < map.width(); column++) {
                int offset = (row * map.width() + column) * 4;
                if (row == 0 || row == map.height() - 1 || column == map.width() - 1) {
                    for (int channel = 0; channel < 4; channel++) {
                        assertEquals(data.get(row * map.width() * 4 + channel), data.get(offset + channel));
                    }
                }
                var direction = row == 0 ? new SpaceVector(0, -1, 0)
                        : row == map.height() - 1 ? new SpaceVector(0, 1, 0)
                        : new GeographicPosition(-Math.PI / 2 + Math.PI * row / (map.height() - 1),
                                -Math.PI + Math.PI * 2 * (column == map.width() - 1 ? 0 : column) / (map.width() - 1), 0).normal();
                var sample = TERRAIN.sample(direction);
                assertEquals(sample.heightMeters(), data.get(offset), .001);
                assertEquals(sample.temperature(), data.get(offset + 1), .00001);
                assertEquals(sample.moisture(), data.get(offset + 2), .000001);
                assertEquals(sample.mountainMask(), data.get(offset + 3), .000001);
            }
        }
    }

    @Test
    void localMapsRetainMeterReliefAtPolesAndMountainSlopes() {
        Random random = new Random(137);
        for (var direction : new SpaceVector[] {new SpaceVector(1, 0, 0), new SpaceVector(0, 1, 0),
                new SpaceVector(0, -1, 0), new SpaceVector(-1, .4, -.3)}) {
            var grid = SurfaceHeightTile.Grid.at(direction, ContinentalTerrain.RADIUS_METERS, 65, 4);
            var map = ContinentalMap.tile(TERRAIN, grid, () -> false);
            var data = FloatBuffer.allocate(65 * 65 * 4);
            map.writeTo(data);
            for (int index = 0; index < 1000; index++) {
                double x = random.nextDouble() * 64, z = random.nextDouble() * 64;
                int ix = (int) x, iz = (int) z;
                double fx = x - ix, fz = z - iz;
                int offset = (iz * 65 + ix) * 4;
                double low = data.get(offset) * (1 - fx) + data.get(offset + 4) * fx;
                double high = data.get(offset + 65 * 4) * (1 - fx) + data.get(offset + 66 * 4) * fx;
                double height = low * (1 - fz) + high * fz;
                assertEquals(TERRAIN.sample(grid.direction(x * 4 - 128, z * 4 - 128)).heightMeters(), height, .2);
            }
        }
    }

    @Test
    void invalidAndRetiredRequestsNeverYieldPartialMaps() {
        assertThrows(IllegalArgumentException.class, () -> ContinentalMap.globe(TERRAIN, 1026, () -> false));
        assertThrows(IllegalArgumentException.class, () -> ContinentalMap.globe(TERRAIN, 64, () -> false));
        assertThrows(IllegalArgumentException.class, () -> ContinentalMap.globe(null, 65, () -> false));
        assertThrows(CancellationException.class, () -> ContinentalMap.globe(TERRAIN, 513, () -> true));
        AtomicInteger checks = new AtomicInteger();
        var grid = SurfaceHeightTile.Grid.at(new SpaceVector(1, 0, 0), ContinentalTerrain.RADIUS_METERS, 513, 4);
        assertThrows(CancellationException.class,
                () -> ContinentalMap.tile(TERRAIN, grid, () -> checks.incrementAndGet() == 3));
        assertEquals(3, checks.get());
    }
}
