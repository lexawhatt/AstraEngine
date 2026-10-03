package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Stateless display approximation of thin Martian air and suspended dust, shared by surface lighting and fog.
 * This is not a climate/pressure simulation. Inputs are finite local solar elevation sine, view/sun cosine,
 * profile-relative density and stellar radiance; no world, clock, resource or mutable cache is owned here.
 */
public final class MarsAtmosphere {
    private MarsAtmosphere() { }

    /** Warm dust ambient light complements direct sunlight; loss of the stellar source removes both linearly. */
    public static SpaceVector light(double sunHeight, double density, double irradiance) {
        require(sunHeight, density, irradiance);
        double day = smooth(-.07, .12, sunHeight);
        double direct = Math.clamp(sunHeight * 4 + .015, 0, 1);
        double dust = Math.clamp(density / .13, 0, 1);
        return new SpaceVector(direct * (1 - dust * .10) + day * dust * .10,
                direct * (1 - dust * .25) + day * dust * .075,
                direct * (1 - dust * .38) + day * dust * .045).multiply(irradiance);
    }

    /** Blue twilight is localized around the Sun; the daytime and opposite horizon retain warm dust colors. */
    public static SpaceVector fog(double sunHeight, double viewSunCosine, double density, double irradiance) {
        require(sunHeight, density, irradiance);
        if (!Double.isFinite(viewSunCosine)) { throw new IllegalArgumentException("Mars fog needs a finite view cosine"); }
        double day = smooth(-.10, .08, sunHeight);
        double dust = Math.clamp(density / .13, 0, 1);
        double twilight = (1 - smooth(.015, .13, Math.abs(sunHeight))) * smooth(.92, .998, viewSunCosine);
        var warm = new SpaceVector(.57, .34, .20);
        var blue = new SpaceVector(.19, .32, .47);
        return warm.multiply(1 - twilight).add(blue.multiply(twilight)).multiply(day * dust * irradiance);
    }

    private static double smooth(double low, double high, double value) {
        double t = Math.clamp((value - low) / (high - low), 0, 1);
        return t * t * (3 - 2 * t);
    }
    private static void require(double sunHeight, double density, double irradiance) {
        if (!Double.isFinite(sunHeight) || !Double.isFinite(density) || density < 0
                || !Double.isFinite(irradiance) || irradiance < 0) {
            throw new IllegalArgumentException("Mars light requires finite elevation and nonnegative density/radiance");
        }
    }
}
