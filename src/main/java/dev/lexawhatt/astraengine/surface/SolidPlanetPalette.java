package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.List;

/** Immutable surface colors sampled from host materials, shared by landscape and orbital maps. */
public record SolidPlanetPalette(List<SpaceVector> colors, SpaceVector water) {
    public static final SolidPlanetPalette DEFAULT = new SolidPlanetPalette(List.of(
            new SpaceVector(.48, .46, .44), new SpaceVector(.46, .46, .46), new SpaceVector(.58, .71, .91),
            new SpaceVector(.94, .96, .97), new SpaceVector(.78, .74, .56), new SpaceVector(.36, .5, .19),
            new SpaceVector(.48, .46, .44), new SpaceVector(.66, .34, .17), new SpaceVector(.59, .36, .27)), new SpaceVector(.08, .24, .49));

    public SolidPlanetPalette {
        if (colors == null || colors.size() != SolidPlanetTerrain.Material.values().length || water == null) {
            throw new IllegalArgumentException("Planet palette requires every material and water color");
        }
        colors = List.copyOf(colors);
        for (var color : colors) { requireColor(color); }
        requireColor(water);
    }

    public SpaceVector color(SolidPlanetTerrain.Sample sample) {
        if (sample == null) { throw new IllegalArgumentException("A planetary surface sample is required"); }
        return sample.water() ? water.multiply(sample.heightMeters() < -600 ? .76 : 1) : colors.get(sample.material().ordinal());
    }

    private static void requireColor(SpaceVector color) {
        if (color == null || color.x() < 0 || color.x() > 1 || color.y() < 0 || color.y() > 1 || color.z() < 0 || color.z() > 1) {
            throw new IllegalArgumentException("Planet material color must be in [0,1]");
        }
    }
}
