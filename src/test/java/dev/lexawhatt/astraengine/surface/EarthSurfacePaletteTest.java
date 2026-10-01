package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EarthSurfacePaletteTest {
    @Test
    void exposedIceAndSnowFollowPhysicalWeatherInsteadOfOceanBiomeThresholds() {
        var ice = sample(-20, -1, .5);
        assertEquals(EarthClimate.OCEAN, EarthClimate.at(ice));
        assertEquals(EarthClimate.FROZEN_OCEAN, EarthSurfacePalette.material(ice));
        assertFalse(EarthSurfacePalette.liquid(ice));
        assertEquals(EarthClimate.OCEAN, EarthSurfacePalette.material(sample(-1, 0, .5)));
        assertTrue(EarthSurfacePalette.liquid(sample(-1, 0, .5)));
        assertEquals(EarthClimate.SNOW, EarthSurfacePalette.material(sample(100, -1, .5)));
        // Actual snow weather checks the floored first-air block, not the continuous height field.
        assertEquals(EarthClimate.TAIGA, EarthSurfacePalette.material(sample(100.9, -.001, .5)));
        assertEquals(EarthClimate.SNOW, EarthSurfacePalette.material(sample(100.9, -.01, .5)));
    }

    @Test
    void forestCanopiesAndShallowWaterRetainTheirMaterialIdentity() {
        assertEquals(EarthClimate.FOREST, EarthSurfacePalette.material(sample(600, 14, .6)));
        assertEquals(EarthClimate.TAIGA, EarthSurfacePalette.material(sample(600, 5, .6)));
        assertEquals(EarthClimate.JUNGLE, EarthSurfacePalette.material(sample(600, 26, .7)));
        assertEquals(EarthClimate.PLAINS, EarthSurfacePalette.material(sample(600, 14, .4)));
        assertEquals(EarthClimate.ALPINE, EarthSurfacePalette.material(sample(2500, 1, .4)));
        assertEquals(EarthClimate.OCEAN, EarthSurfacePalette.material(sample(-.1, 20, .4)));
        assertEquals(EarthClimate.BEACH, EarthSurfacePalette.material(sample(0, 20, .4)));
        assertEquals(EarthClimate.DEEP_OCEAN, EarthSurfacePalette.material(sample(-601, 20, .4)));
    }

    @Test
    void paletteIsAnImmutableResourceIndependentSnapshot() {
        var source = new ArrayList<>(EarthSurfacePalette.DEFAULT.colors());
        var palette = new EarthSurfacePalette(source);
        var original = palette.colors().getFirst();
        source.set(0, SpaceVector.ZERO);
        assertEquals(original, palette.colors().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> palette.colors().clear());
        source.set(0, null);
        assertThrows(IllegalArgumentException.class, () -> new EarthSurfacePalette(source));
        source.set(0, new SpaceVector(1.01, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new EarthSurfacePalette(source));
        assertThrows(IllegalArgumentException.class, () -> EarthSurfacePalette.material(null));
    }

    private static ContinentalTerrain.Sample sample(double height, double temperature, double moisture) {
        return new ContinentalTerrain.Sample(height, temperature, moisture, .1, 0);
    }
}
