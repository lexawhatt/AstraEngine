package dev.lexawhatt.astraengine.client.sky;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Bounded visual skylight and haze, separate from the host's authoritative block-light values. */
public final class SkyIllumination {
    private SkyIllumination() { }

    /** Linear full-sky contribution before Minecraft's ambient/gamma transforms. */
    public static SpaceVector skyLight(double sunHeight, double rain, double thunder, double luminosity) {
        double day = smooth(-0.12, 0.18, sunHeight);
        double warm = Math.exp(-Math.pow((sunHeight - 0.025) / 0.15, 2));
        double weather = (1 - rain * 0.55) * (1 - thunder * 0.55);
        double strength = Math.clamp(luminosity, 0, 1);
        double direct = day * weather * strength;
        double night = (1 - day) * (0.1 + strength * 0.9);
        return new SpaceVector(0.012 * night + direct * (1.0 - warm * 0.08),
                0.021 * night + direct * (1.0 - warm * 0.30),
                0.046 * night + direct * (1.0 - warm * 0.43));
    }

    /** Approximate display-space horizon haze toward the camera, with directional twilight warmth. */
    public static SpaceVector fog(double sunHeight, double towardSun, double rain, double thunder,
                                  double luminosity, double flash, double pollution) {
        double day = smooth(-0.16, 0.14, sunHeight);
        double twilight = Math.exp(-Math.pow((sunHeight + 0.025) / 0.13, 2));
        double forward = Math.pow(Math.max(0, towardSun), 3);
        double weather = (1 - rain * 0.48) * (1 - thunder * 0.6);
        double light = Math.sqrt(Math.clamp(luminosity, 0, 1));
        double warm = twilight * (0.2 + forward * 0.8) * (1 - rain * 0.8) * light;
        double red = (0.012 * (1 - day) + 0.53 * day) * (0.08 + light * 0.92) * weather;
        double green = (0.023 * (1 - day) + 0.68 * day) * (0.08 + light * 0.92) * weather;
        double blue = (0.047 * (1 - day) + 0.84 * day) * (0.08 + light * 0.92) * weather;
        red = red * (1 - warm * 0.38) + warm * 0.49;
        green = green * (1 - warm * 0.55) + warm * 0.11;
        blue *= 1 - warm * 0.64;
        double glow = pollution * (1 - day);
        return new SpaceVector(Math.clamp(red + glow * 0.16 + flash * 0.12, 0, 0.98),
                Math.clamp(green + glow * 0.10 + flash * 0.14, 0, 0.98),
                Math.clamp(blue + glow * 0.07 + flash * 0.18, 0, 0.98));
    }

    private static double smooth(double min, double max, double value) {
        double t = Math.clamp((value - min) / (max - min), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
