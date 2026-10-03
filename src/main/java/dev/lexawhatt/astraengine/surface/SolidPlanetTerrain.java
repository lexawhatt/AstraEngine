package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Version-one global field for solid bodies. All charts sample a single body-fixed direction, including poles
 * and face seams. Crater bowls/rims, folded ice and continental relief are procedural approximations, not
 * observational maps or a physical tectonic solver. Immutable and bounded-cost; no chunk generation is needed.
 */
public final class SolidPlanetTerrain {
    public enum Material { REGOLITH, ROCK, ICE, SNOW, SAND, GRASS, OCEAN_FLOOR }

    /** Physical elevations in meters above the profile radius. Water is absent when its top equals the bed. */
    public record Sample(double heightMeters, double topMeters, Material material) {
        public Sample {
            if (!Double.isFinite(heightMeters) || !Double.isFinite(topMeters) || topMeters < heightMeters || material == null) {
                throw new IllegalArgumentException("Invalid solid-planet terrain sample");
            }
        }
        public boolean water() { return topMeters > heightMeters; }
    }

    private final SolidPlanetProfile profile;
    private final int seed;
    private final double reliefScale;

    public SolidPlanetTerrain(SolidPlanetProfile profile) {
        if (profile == null) { throw new IllegalArgumentException("A solid-planet profile is required"); }
        this.profile = profile;
        seed = (int) (profile.seed() ^ profile.seed() >>> 32);
        reliefScale = Math.min(1.6, Math.max(.002, profile.radiusMeters() / 1_737_400));
    }

    public SolidPlanetProfile profile() { return profile; }

    /** Conservative analytic upper bound in physical meters, allowing genuinely empty air bands to skip noise. */
    public double maximumHeightMeters() {
        return switch (profile.kind()) {
            case ICE -> (700 + 2200 + 90 * .3) * reliefScale;
            case OCEAN -> (6500 + 5200 * 1.2 + 90) * reliefScale;
            // Each crater search visits27cells; only its radius<=.6 and rim<=.32 can raise the height.
            case ROCKY -> (2000 + 3800 + 27 * .6 * .32 * (1900 + 240) + 90) * reliefScale;
            default -> throw new IllegalStateException("Unsupported solid-planet material");
        };
    }

    /** Samples a finite nonzero direction in body-fixed coordinates; no longitude seam or visit-order state. */
    public Sample sample(SpaceVector direction) {
        if (direction == null) { throw new IllegalArgumentException("A terrain direction is required"); }
        var unit = direction.normalized();
        double x = unit.x(), y = unit.y(), z = unit.z();
        double continental = fbm(x * 4, y * 4, z * 4, seed, 4);
        double folded = 1 - Math.abs(fbm(x * 22, y * 22, z * 22, seed ^ 0x8117, 3));
        double mountain = Math.pow(Math.max(0, folded - .52) / .48, 3);
        double localScale = profile.radiusMeters() / 1800;
        double detail = fbm(x * localScale, y * localScale, z * localScale, seed ^ 0x39A7, 3) * 90;
        double height;
        Material material;
        switch (profile.kind()) {
            case ICE -> {
                double cracks = Math.abs(noise(x * 110 + continental * 3, y * 110, z * 110, seed ^ 0xAC73));
                height = (continental * 700 + mountain * 2200 - Math.exp(-cracks * 50) * 180
                        + detail * .3) * reliefScale;
                material = mountain > .33 ? Material.SNOW : Material.ICE;
            }
            case OCEAN -> {
                height = (continental * 6500 + mountain * 5200 * Math.max(0, continental + .2) + detail) * reliefScale;
                height = Math.max(-profile.radiusMeters() * .2, height);
                double temperature = 29 - Math.abs(y) * 44 - Math.max(0, height) * .0055;
                material = height < 0 ? Material.OCEAN_FLOOR : temperature < -3 ? Material.SNOW
                        : height < 8 ? Material.SAND : mountain > .6 ? Material.ROCK : Material.GRASS;
                return new Sample(height, Math.max(0, height), material);
            }
            case ROCKY -> {
                double crater = craters(x * 34, y * 34, z * 34, seed ^ 0xCA73) * 1900
                        + craters(x * 340, y * 340, z * 340, seed ^ 0xA917) * 240;
                height = (continental * 2000 + mountain * 3800 + crater + detail) * reliefScale;
                material = mountain > .3 ? Material.ROCK : Material.REGOLITH;
            }
            default -> throw new IllegalStateException("Unsupported solid-planet material");
        }
        // Tiny bodies retain terrain outside their center; storage uses the descriptor's real radius.
        height = Math.max(-profile.radiusMeters() * .2, height);
        return new Sample(height, height, material);
    }

    private static double craters(double x, double y, double z, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double result = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = ix + dx, cy = iy + dy, cz = iz + dz;
                    if (hash(cx, cy, cz, seed) < .35) { continue; }
                    double px = x - cx - hash(cx, cy, cz, seed ^ 0x539);
                    double py = y - cy - hash(cx, cy, cz, seed ^ 0x9A3);
                    double pz = z - cz - hash(cx, cy, cz, seed ^ 0xFA7);
                    double radius = .2 + .4 * hash(cx, cy, cz, seed ^ 0x371);
                    double q = Math.sqrt(px * px + py * py + pz * pz) / radius;
                    double bowl = Math.max(0, 1 - q * q);
                    double rim = Math.max(0, 1 - Math.abs(q - 1) / .16);
                    result += radius * (-bowl * bowl + rim * rim * .32);
                }
            }
        }
        return result;
    }

    private static double fbm(double x, double y, double z, int seed, int octaves) {
        double result = 0, amplitude = .5, sum = 0;
        for (int index = 0; index < octaves; index++) {
            result += noise(x, y, z, seed + index * 0x71E1) * amplitude;
            sum += amplitude; amplitude *= .5; x *= 2.03; y *= 2.03; z *= 2.03;
        }
        return result / sum;
    }

    private static double noise(double x, double y, double z, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = smooth(x - ix), fy = smooth(y - iy), fz = smooth(z - iz);
        double a = mix(mix(hash(ix, iy, iz, seed), hash(ix + 1, iy, iz, seed), fx),
                mix(hash(ix, iy + 1, iz, seed), hash(ix + 1, iy + 1, iz, seed), fx), fy);
        double b = mix(mix(hash(ix, iy, iz + 1, seed), hash(ix + 1, iy, iz + 1, seed), fx),
                mix(hash(ix, iy + 1, iz + 1, seed), hash(ix + 1, iy + 1, iz + 1, seed), fx), fy);
        return mix(a, b, fz) * 2 - 1;
    }

    private static double smooth(double value) { return value * value * value * (value * (value * 6 - 15) + 10); }
    private static double mix(double a, double b, double amount) { return a + (b - a) * amount; }
    private static double hash(int x, int y, int z, int seed) {
        int value = x * 0x8DA6B343 ^ y * 0xD8163841 ^ z * 0xCB1AB31F ^ seed;
        value ^= value >>> 16; value *= 0x7FEB352D; value ^= value >>> 15; value *= 0x846CA68B; value ^= value >>> 16;
        return (value & 0xFFFFFF) * 0x1.0p-24;
    }
}
