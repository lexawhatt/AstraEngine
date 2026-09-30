package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Versioned spherical height/climate field for the opt-in highlands prototype.
 * Immutable, worker-safe and independent of Minecraft worlds. This is procedural
 * authored relief, not measured Earth topography or a tectonic/erosion simulation.
 * It does not replace the persisted version-one Moon/Earth landing geography.
 */
public record PlanetaryTerrain(int version, long seed) {
    public static final String DIMENSION_ID = "astraengine:terrain_highlands";
    public static final int VERSION = 1;
    public static final long SEED = 0x415354524131L;
    public static final int MIN_Y = -256;
    public static final int HEIGHT = 2048;
    public static final int SEA_Y = 0;
    public static final double MIN_ELEVATION = -192;
    public static final double MAX_ELEVATION = 1536;
    public static final SurfacePatch PATCH = new SurfacePatch(6_371_000, Math.PI / 4, 0, 32768, SEA_Y);
    public static final PlanetaryTopology TOPOLOGY = new PlanetaryTopology(1, 12, PATCH.radiusMeters());

    /** Rejects unknown algorithms; arbitrary seeds are valid pure fields, while world codecs pin their saved seed. */
    public PlanetaryTerrain {
        if (version != VERSION) { throw new IllegalArgumentException("Unsupported planetary terrain version: " + version); }
    }

    /** Elevation in reference meters, approximate temperature in Celsius, and moisture in [0,1]. */
    public record Sample(double heightMeters, double temperature, double moisture) {}

    /**
     * Samples a finite nonzero body-fixed direction. Double coordinates and 3D noise
     * avoid an equirectangular seam and pole singularities. No random sequence is consumed.
     */
    public Sample sample(SpaceVector direction) {
        if (direction == null) { throw new IllegalArgumentException("Terrain direction is required"); }
        SpaceVector n = direction.normalized();
        double x = n.x() * PATCH.radiusMeters();
        double y = n.y() * PATCH.radiusMeters();
        double z = n.z() * PATCH.radiusMeters();
        double continental = noise(x / 27000, y / 27000, z / 27000, seed);
        double warp = (noise(x / 11000, y / 11000, z / 11000, seed ^ 0x71E1) - 0.5) * 1800;
        double ridge = 1 - Math.abs(2 * noise((x + warp) / 4600, (y - warp) / 4600,
                (z + warp) / 4600, seed ^ 0xB135) - 1);
        ridge = ridge * ridge * ridge * ridge;
        double massif = 0.2 + 0.8 * noise(x / 17000, y / 17000, z / 17000, seed ^ 0x51A3);
        double detail = noise(x / 330, y / 330, z / 330, seed ^ 0xC4A7);
        double height = -180 + 240 * continental + 1360 * ridge * massif + 96 * detail;
        double temperature = 35 - 42 * Math.abs(n.y()) - Math.max(0, height) * 0.0065;
        double moisture = noise(x / 19000, y / 19000, z / 19000, seed ^ 0xA73D);
        return new Sample(height, temperature, moisture);
    }

    /** First air above solid terrain, sampling block centers; outside this prototype patch returns its bottom. */
    public int firstAir(int blockX, int blockZ) {
        double x = blockX + 0.5, z = blockZ + 0.5;
        return PATCH.contains(x, z) ? (int) Math.floor(SEA_Y + sample(PATCH.normal(x, z)).heightMeters()) : MIN_Y;
    }

    private static double noise(double x, double y, double z, long seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = smooth(x - ix), fy = smooth(y - iy), fz = smooth(z - iz);
        double lo = mix(mix(hash(ix, iy, iz, seed), hash(ix + 1, iy, iz, seed), fx),
                mix(hash(ix, iy + 1, iz, seed), hash(ix + 1, iy + 1, iz, seed), fx), fy);
        double hi = mix(mix(hash(ix, iy, iz + 1, seed), hash(ix + 1, iy, iz + 1, seed), fx),
                mix(hash(ix, iy + 1, iz + 1, seed), hash(ix + 1, iy + 1, iz + 1, seed), fx), fy);
        return mix(lo, hi, fz);
    }

    private static double smooth(double value) { return value * value * value * (value * (value * 6 - 15) + 10); }
    private static double mix(double a, double b, double t) { return a + (b - a) * t; }

    private static double hash(int x, int y, int z, long seed) {
        long h = seed ^ (long) x * 0x9E3779B97F4A7C15L ^ (long) y * 0xBF58476D1CE4E5B9L
                ^ (long) z * 0x94D049BB133111EBL;
        h = (h ^ h >>> 30) * 0xBF58476D1CE4E5B9L;
        h = (h ^ h >>> 27) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }
}
