package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.List;

/** Immutable display-color palette shared by distant and orbital Earth; workers never retain host resources. */
public record EarthSurfacePalette(List<SpaceVector> colors) {
    /** Conservative display palette for pure/headless mesh consumers; clients capture active textures and biome tints. */
    public static final EarthSurfacePalette DEFAULT = new EarthSurfacePalette(List.of(
            new SpaceVector(.05, .15, .34), new SpaceVector(.08, .24, .49), new SpaceVector(.57, .71, .89),
            new SpaceVector(.78, .74, .56), new SpaceVector(.94, .96, .97), new SpaceVector(.46, .46, .46),
            new SpaceVector(.78, .74, .56), new SpaceVector(.40, .48, .22), new SpaceVector(.20, .40, .055),
            new SpaceVector(.20, .33, .21), new SpaceVector(.22, .39, .10), new SpaceVector(.36, .50, .19)));

    /** Requires one finite RGB color in [0,1] for each EarthClimate, in enum order; copies the list. */
    public EarthSurfacePalette {
        if (colors == null || colors.size() != EarthClimate.values().length) {
            throw new IllegalArgumentException("Earth appearance requires all climate colors");
        }
        for (SpaceVector color : colors) {
            if (color == null || color.x() < 0 || color.y() < 0 || color.z() < 0
                    || color.x() > 1 || color.y() > 1 || color.z() > 1) {
                throw new IllegalArgumentException("Earth appearance colors must be in [0,1]");
            }
        }
        colors = List.copyOf(colors);
    }

    /** Surface cover includes exposed freezing/snow, without changing the saved biome or generation version. */
    public SpaceVector color(ContinentalTerrain.Sample sample) { return colors.get(material(sample).ordinal()); }

    /** Water covers shallow negative-height beaches; frozen water never uses liquid ocean reflectance. */
    public static EarthClimate material(ContinentalTerrain.Sample sample) {
        if (sample == null) { throw new IllegalArgumentException("Earth appearance requires a terrain sample"); }
        if (sample.water()) {
            return sample.temperature() < 0 ? EarthClimate.FROZEN_OCEAN
                    : sample.heightMeters() < -600 ? EarthClimate.DEEP_OCEAN : EarthClimate.OCEAN;
        }
        // Weather evaluates the first air block, whose physical altitude is the floored height.
        double surfaceTemperature = sample.temperature()
                + (sample.heightMeters() - Math.floor(sample.heightMeters())) * .0065;
        return surfaceTemperature < 0 ? EarthClimate.SNOW : EarthClimate.at(sample);
    }

    /** Independent of color; ice is a solid surface and must not inherit liquid glint. */
    public static boolean liquid(ContinentalTerrain.Sample sample) {
        var material = material(sample);
        return material == EarthClimate.OCEAN || material == EarthClimate.DEEP_OCEAN;
    }
}
