package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Third-generation physiographic provinces, later cut by the shared regional drainage atlas. */
final class ContinentalTerrainV3 {
    private ContinentalTerrainV3() { }

    static ContinentalTerrain.Sample base(SpaceVector normal, long seed) {
        var source = ContinentalTerrainV2.sample(normal, seed);
        double x = normal.x() * ContinentalTerrain.RADIUS_METERS;
        double y = normal.y() * ContinentalTerrain.RADIUS_METERS;
        double z = normal.z() * ContinentalTerrain.RADIUS_METERS;
        double continentality = source.continentality(), mountain = source.mountainMask();
        double inland = ramp(0, .055, continentality);
        double plateau = province(x, y, z, seed);
        double oldUplands = ramp(.43, .69, noise(x, y, z, 240000, seed + 311));
        double basin = ramp(.53, .75, noise(x, y, z, 410000, seed + 397));
        double hill = fractal(x, y, z, 12500, seed + 433);
        double height = source.heightMeters();
        if (continentality >= 0) {
            // Distinct broad landforms, with low-relief plateau interiors and steeper dissected margins.
            double uplandRidges = 1 - Math.abs(2 * fractal(x, y, z, 7600, seed + 487) - 1);
            double uplands = oldUplands * (240 + 820 * uplandRidges * uplandRidges);
            double foothills = (Math.sqrt(mountain) - mountain) * 1800;
            double rolling = (hill - .5) * (180 + 520 * oldUplands) * (1 - plateau * .65);
            double depression = Math.min(480, Math.max(0, height) * .65) * basin;
            double relief = 2050 * plateau * (1 - mountain * .55) + uplands + foothills + rolling - depression;
            double ridge = 0, amplitude = 1, scale = 1700;
            for (int octave = 0; octave < 4; octave++) {
                double r = 1 - Math.abs(noise(x, y, z, scale, seed + 1729 + octave * 853) * 2 - 1);
                ridge += (r * r - .5) * amplitude;
                amplitude *= .46; scale *= .49;
            }
            height = Math.max(3 * inland, height + relief * inland + ridge * 420 * mountain);
        } else {
            double deep = ramp(.025, .13, -continentality);
            double ridgeBelt = 1 - ramp(.015, .07, Math.abs(noise(x, y, z, 750000, seed + 557) - .5));
            double trench = (1 - ramp(.012, .04, Math.abs(noise(x, y, z, 330000, seed + 601) - .5)))
                    * ramp(.025, .08, -continentality) * (1 - ramp(.18, .30, -continentality));
            height += deep * (1100 * ridgeBelt - 1900 * trench + (hill - .5) * 320);
        }
        // Coast detail fades away from the shelf and lowland shore, without a global block-axis lattice.
        height += (fractal(x, y, z, 17000, seed + 661) - .5) * 95
                * (1 - ramp(100, 450, Math.abs(height)));
        height = Math.clamp(height, ContinentalTerrain.MIN_ELEVATION, ContinentalTerrain.MAX_ELEVATION);

        double latitude = Math.abs(normal.y());
        double horizontal = Math.hypot(normal.x(), normal.z());
        double wind = (2 * ramp(.29, .41, latitude) * (1 - ramp(.72, .84, latitude)) - 1)
                * ramp(0, .12, horizontal);
        double eastX = horizontal < 1e-8 ? 0 : normal.z() / horizontal;
        double eastZ = horizontal < 1e-8 ? 0 : -normal.x() / horizontal;
        double upwind = province(x - eastX * wind * 190000, y, z - eastZ * wind * 190000, seed);
        // A regional upwind barrier approximation; this is not an atmospheric circulation solver.
        double rainShadow = Math.max(0, upwind - plateau) * .55;
        double windward = Math.max(0, plateau - upwind) * .22;
        double tropicalRain = 1 - ramp(.12, .38, latitude);
        double subtropicalDry = Math.exp(-Math.pow((latitude - .46) / .18, 2));
        double moisture = Math.clamp(.55 + .24 * tropicalRain - .34 * subtropicalDry
                + (noise(x, y, z, 620000, seed + 719) - .5) * .68
                - ramp(.07, .25, continentality) * .17 - rainShadow + windward, 0, 1);
        // Annual-mean zonal climate, moderated near coasts and varied by broad regional fields.
        // A linear sine-of-latitude falloff made subtropics cold and created straight ice boundaries.
        double maritime = (1 - ramp(0, .20, continentality)) * ramp(.3, .85, latitude) * 6;
        double temperature = 30 - 47 * Math.pow(latitude, 2.6) - Math.max(0, height) * .0065
                + maritime + (noise(x, y, z, 1600000, seed + 761) - .5) * 10;
        return new ContinentalTerrain.Sample(height, temperature, moisture, continentality, mountain);
    }

    private static double province(double x, double y, double z, long seed) {
        double warp = (noise(x, y, z, 280000, seed + 197) - .5) * 220000;
        return ramp(.49, .68, noise(x + warp, y - warp * .4, z + warp * .7, 510000, seed + 251));
    }

    private static double fractal(double x, double y, double z, double scale, long seed) {
        return (noise(x, y, z, scale, seed) + .45 * noise(x + 1723, y - 317, z + 991, scale * .31, seed + 71)
                + .19 * noise(x - 611, y + 1301, z - 331, scale * .097, seed + 137)) / 1.64;
    }

    private static double noise(double x, double y, double z, double scale, long seed) {
        return ContinentalTerrain.noise(x / scale, y / scale, z / scale, seed);
    }

    private static double ramp(double low, double high, double value) {
        double t = Math.clamp((value - low) / (high - low), 0, 1);
        return t * t * (3 - 2 * t);
    }

    static ContinentalTerrain.Sample climate(ContinentalTerrain.Sample base, double height, double water) {
        double temperature = base.temperature() + (Math.max(0, base.heightMeters()) - Math.max(0, height)) * .0065;
        return new ContinentalTerrain.Sample(height, temperature, base.moisture(), base.continentality(),
                base.mountainMask(), Math.max(height, water));
    }
}
