package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Pinned second-generation relief: rotated gradient octaves and warped mountain drainage-shaped ridges. */
final class ContinentalTerrainV2 {
    private ContinentalTerrainV2() {}

    static ContinentalTerrain.Sample sample(SpaceVector normal, long seed) {
        seed += 11; // Version-specific domain; the canonical origin remains habitable lowland.
        double radius = ContinentalTerrain.RADIUS_METERS;
        double x = normal.x() * radius, y = normal.y() * radius, z = normal.z() * radius;
        double wx = x + (at(x, y, z, 850000, seed + 17) - .5) * 750000;
        double wy = y + (at(x, y, z, 850000, seed + 47) - .5) * 750000;
        double wz = z + (at(x, y, z, 850000, seed + 89) - .5) * 750000;
        double continentality = Math.clamp((fractal(wx, wy, wz, 2600000, seed, 4, .38) - .525) * 3, -1, 1);
        double inland = ramp(0, .085, continentality);
        double belt = Math.abs(at(wx, wy, wz, 950000, seed + 239) - .52);
        double mountain = (1 - ramp(.022, .095, belt)) * inland
                * ramp(.37, .63, at(wx, wy, wz, 1500000, seed + 811));
        double height;
        if (continentality < 0) {
            double offshore = -continentality;
            height = -200 * ramp(0, .045, offshore) - 4400 * ramp(.035, .21, offshore)
                    - 1300 * ramp(.21, .65, offshore)
                    + (fractal(x, y, z, 38000, seed + 599, 3, .45) - .5) * 250 * ramp(.02, .12, offshore);
        } else {
            double plains = 90 * ramp(0, .025, continentality) + 1200 * continentality
                    + (fractal(x, y, z, 22000, seed + 101, 4, .46) - .5) * 190 * inland;
            // Warp the ridge coordinates locally. No world-axis lattice repeats through successive octaves.
            double rx = x + (at(x, y, z, 47000, seed + 379) - .5) * 52000;
            double ry = y + (at(x, y, z, 47000, seed + 419) - .5) * 52000;
            double rz = z + (at(x, y, z, 47000, seed + 457) - .5) * 52000;
            double spine = ridge(at(rx, ry, rz, 85000, seed + 613));
            double shoulders = ridge(at(rx, ry, rz, 17000, seed + 677));
            double crags = ridge(fractal(rx, ry, rz, 2800, seed + 719, 3, .45));
            double strength = .8 + .2 * at(wx, wy, wz, 300000, seed + 787);
            double relief = 1050 + 6000 * spine * spine + 950 * shoulders * shoulders + 350 * crags;
            double fine = (fractal(x, y, z, 450, seed + 997, 3, .5) - .5) * 70 * inland;
            height = plains + mountain * strength * relief + fine;
        }
        double latitude = Math.abs(normal.y());
        double temperature = 31 - 48 * Math.pow(latitude, 1.15) - Math.max(0, height) * .0065;
        double rain = 1 - ramp(.10, .42, latitude);
        double dry = Math.exp(-Math.pow((latitude - .48) / .15, 2));
        double moisture = Math.clamp(.28 + .40 * fractal(x, y, z, 380000, seed + 1237, 3, .5)
                + .24 * rain - .24 * dry - .12 * mountain, 0, 1);
        return new ContinentalTerrain.Sample(height, temperature, moisture, continentality, mountain);
    }

    private static double fractal(double x, double y, double z, double scale, long seed, int octaves, double decay) {
        double value = 0, weight = 1, total = 0;
        for (int octave = 0; octave < octaves; octave++) {
            value += at(x, y, z, scale, seed + octave * 0x632BE59BD9B4E019L) * weight;
            total += weight;
            weight *= decay;
            scale *= .47;
            // An orthonormal rotation avoids stacked octaves sharing axis-aligned gradients.
            double nextX = .36 * x - .48 * y + .8 * z;
            double nextY = .8 * x + .6 * y;
            z = -.48 * x + .64 * y + .6 * z;
            x = nextX; y = nextY;
        }
        return value / total;
    }

    private static double at(double x, double y, double z, double scale, long seed) {
        x /= scale; y /= scale; z /= scale;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        double sx = smooth(fx), sy = smooth(fy), sz = smooth(fz);
        double low = mix(mix(gradient(ix, iy, iz, fx, fy, fz, seed),
                        gradient(ix + 1, iy, iz, fx - 1, fy, fz, seed), sx),
                mix(gradient(ix, iy + 1, iz, fx, fy - 1, fz, seed),
                        gradient(ix + 1, iy + 1, iz, fx - 1, fy - 1, fz, seed), sx), sy);
        double high = mix(mix(gradient(ix, iy, iz + 1, fx, fy, fz - 1, seed),
                        gradient(ix + 1, iy, iz + 1, fx - 1, fy, fz - 1, seed), sx),
                mix(gradient(ix, iy + 1, iz + 1, fx, fy - 1, fz - 1, seed),
                        gradient(ix + 1, iy + 1, iz + 1, fx - 1, fy - 1, fz - 1, seed), sx), sy);
        return Math.clamp(.5 + .5 * mix(low, high, sz), 0, 1);
    }

    private static double gradient(int x, int y, int z, double dx, double dy, double dz, long seed) {
        long hash = seed ^ (long) x * 0x9E3779B97F4A7C15L ^ (long) y * 0xBF58476D1CE4E5B9L
                ^ (long) z * 0x94D049BB133111EBL;
        hash = (hash ^ hash >>> 30) * 0xBF58476D1CE4E5B9L;
        hash = (hash ^ hash >>> 27) * 0x94D049BB133111EBL;
        int index = (int) (hash ^ hash >>> 31) & 15;
        double first = index < 8 ? dx : dy;
        double second = index < 4 ? dy : index == 12 || index == 14 ? dx : dz;
        return ((index & 1) == 0 ? first : -first) + ((index & 2) == 0 ? second : -second);
    }

    private static double ridge(double value) { return Math.max(0, 1 - Math.abs(value * 2 - 1) * 1.65); }
    private static double ramp(double low, double high, double value) {
        return smooth(Math.clamp((value - low) / (high - low), 0, 1));
    }
    private static double smooth(double value) {
        return Math.clamp(value * value * value * (value * (value * 6 - 15) + 10), 0, 1);
    }
    private static double mix(double first, double second, double weight) { return first + (second - first) * weight; }
}
