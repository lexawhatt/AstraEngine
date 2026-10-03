package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.nio.FloatBuffer;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SolidPlanetMapTest {
    @Test void globeAndLocalTilesPreserveCanonicalElevationAndHostMaterial() {
        var system = CosmosGenerator.sol();
        for (String id : new String[] {"moon", "europa", "mars"}) {
            var profile = SolidPlanetProfile.create(system, system.bodies().stream()
                    .filter(body -> body.id().equals(id)).findFirst().orElseThrow()).orElseThrow();
            var terrain = new SolidPlanetTerrain(profile);
            var globe = SolidPlanetMap.globe(terrain, 33, SolidPlanetPalette.DEFAULT, () -> false);
            var pixels = pixels(globe);
            for (int row = 0; row < globe.height(); row++) {
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(pixels.get(row * globe.width() * 4 + channel),
                            pixels.get((row * globe.width() + globe.width() - 1) * 4 + channel));
                }
            }
            for (int row : new int[] {0, globe.height() - 1}) {
                for (int x = 1; x < globe.width(); x++) {
                    for (int channel = 0; channel < 4; channel++) {
                        assertEquals(pixels.get(row * globe.width() * 4 + channel),
                                pixels.get((row * globe.width() + x) * 4 + channel));
                    }
                }
            }
            var grid = SurfaceHeightTile.Grid.at(new SpaceVector(1, .3, -.5), profile.radiusMeters(), 17, 8);
            var tile = SolidPlanetMap.tile(terrain, grid, SolidPlanetPalette.DEFAULT, () -> false);
            var local = pixels(tile);
            for (int z = 0; z < 17; z++) {
                for (int x = 0; x < 17; x++) {
                    var sample = terrain.sample(grid.direction(x * 8 - 64, z * 8 - 64));
                    var color = SolidPlanetPalette.DEFAULT.color(sample);
                    int offset = (z * 17 + x) * 4;
                    assertEquals((float) sample.heightMeters(), local.get(offset));
                    assertEquals((float) color.x(), local.get(offset + 1));
                    assertEquals((float) color.y(), local.get(offset + 2));
                    assertEquals((float) color.z(), local.get(offset + 3));
                }
            }
            assertThrows(CancellationException.class,
                    () -> SolidPlanetMap.globe(terrain, 33, SolidPlanetPalette.DEFAULT, () -> true));
        }
    }

    private static FloatBuffer pixels(SolidPlanetMap map) {
        var buffer = FloatBuffer.allocate(map.width() * map.height() * 4);
        map.writeTo(buffer);
        return buffer.flip();
    }
}
