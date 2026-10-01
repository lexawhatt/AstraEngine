package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.nio.FloatBuffer;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceHeightTileTest {
    private static final SurfaceGeography EARTH = new SurfaceGeography(1, 0x45415254L, SurfaceGeography.Kind.EARTH);

    @Test
    void gridInterpolationRetainsCanonicalReliefAtTheCoastPolesAndDateLine() {
        for (double[] location : new double[][] {{0, 0}, {Math.PI / 2, 0}, {-Math.PI / 2, 0}, {.65, Math.PI - 1e-9}}) {
            var normal = new GeographicPosition(location[0], location[1], 0).normal();
            var grid = SurfaceHeightTile.Grid.at(normal, 6_371_000, 65, 4);
            var tile = SurfaceHeightTile.bake(grid, EARTH, () -> false);
            Random random = new Random(183);
            for (int index = 0; index < 2000; index++) {
                double x = (random.nextDouble() * 2 - 1) * grid.halfWidthMeters();
                double z = (random.nextDouble() * 2 - 1) * grid.halfWidthMeters();
                var bands = tile.sample(x, z);
                float height = Math.clamp(bands.coast() + bands.hills() + bands.fine(), -48, 112);
                assertEquals(EARTH.sample(grid.direction(x, z)).heightMeters(), height, .08);
            }
            assertTrue(grid.contains(normal, .45));
            assertFalse(grid.contains(normal.multiply(-1), 1));
            assertFalse(grid.contains(grid.direction(grid.halfWidthMeters() * 1.01, 0), 1));
        }
    }

    @Test
    void verticesAndUploadLayoutUseTheSameSamplesIncludingBothEdges() {
        var grid = SurfaceHeightTile.Grid.at(new SpaceVector(1, .4, -.8), 6_371_000, 17, 4);
        var tile = SurfaceHeightTile.bake(grid, EARTH, () -> false);
        FloatBuffer data = FloatBuffer.allocate(17 * 17 * 4);
        tile.writeTo(data);
        assertEquals(data.capacity(), data.position());
        data.flip();
        for (int z = 0; z < 17; z++) {
            for (int x = 0; x < 17; x++) {
                var expected = EARTH.earthBands(grid.direction(x * 4 - 32, z * 4 - 32));
                assertEquals(expected, tile.sample(x * 4 - 32, z * 4 - 32));
                assertEquals(expected.coast(), data.get());
                assertEquals(expected.hills(), data.get());
                assertEquals(expected.fine(), data.get());
                assertEquals(0, data.get());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> tile.sample(32.01, 0));
        assertThrows(IllegalArgumentException.class, () -> tile.sample(Double.NaN, 0));
    }

    @Test
    void aRetiredRequestStopsBeforeAllocationOrAtTheNextRow() {
        var grid = SurfaceHeightTile.Grid.at(new SpaceVector(1, 0, 0), 6_371_000, 513, 4);
        assertThrows(CancellationException.class, () -> SurfaceHeightTile.bake(grid, EARTH, () -> true));
        AtomicInteger checks = new AtomicInteger();
        assertThrows(CancellationException.class,
                () -> SurfaceHeightTile.bake(grid, EARTH, () -> checks.incrementAndGet() == 3));
        assertEquals(3, checks.get());
    }

    @Test
    void bandsPreserveTheSavedVersionOneHeightArithmetic() {
        Random random = new Random(2397);
        for (int index = 0; index < 10000; index++) {
            var direction = new SpaceVector(random.nextDouble() * 2 - 1,
                    random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1);
            var bands = EARTH.earthBands(direction);
            assertEquals(EARTH.sample(direction).heightMeters(),
                    (double) Math.clamp(bands.coast() + bands.hills() + bands.fine(), -48, 112));
        }
        assertThrows(IllegalArgumentException.class, () -> EARTH.earthBands(SpaceVector.ZERO));
        var moon = new SurfaceGeography(1, 0, SurfaceGeography.Kind.MOON);
        assertThrows(IllegalArgumentException.class, () -> moon.earthBands(new SpaceVector(1, 0, 0)));
    }
}
