package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Immutable versioned spherical geography shared with the orbital material. This is authored game terrain,
 * not measured topography. Integer hashing and explicit float operations have a direct GLSL 150 mirror.
 * GPU rounding may differ slightly; saved voxels use this Java sampler as their authority.
 */
public record SurfaceGeography(int version, long seed, Kind kind) {
    public static final int VERSION = 1;
    public static final double MIN_HEIGHT_METERS = -48;
    public static final double MAX_HEIGHT_METERS = 112;

    public enum Kind { MOON, EARTH }
    public enum Material { REGOLITH, ROCK, GRASS, SAND, ICE, OCEAN_FLOOR }

    /** Elevation relative to nominal sea radius; ocean describes a water-covered column, not a voxel. */
    public record Sample(double heightMeters, Material material, boolean ocean) {
        public Sample {
            if (!Double.isFinite(heightMeters) || heightMeters < MIN_HEIGHT_METERS
                    || heightMeters > MAX_HEIGHT_METERS || material == null) {
                throw new IllegalArgumentException("Invalid surface geography sample");
            }
        }
    }

    public SurfaceGeography {
        if (version != VERSION || kind == null) {
            throw new IllegalArgumentException("Unsupported surface geography version or kind");
        }
    }

    /** Exact 32-bit seed for the GLSL uint hash; upload as an integer, never a rounded large float. */
    public int shaderSeed() { return (int) (seed ^ seed >>> 32); }

    /**
     * Version-one Earth height bands in meters, before the final [-48,112] clamp. Keeping the three
     * scales separate lets a presentation cache apply the same footprint filtering as the analytic shader.
     * These values are immutable and do not own or replace saved terrain.
     */
    public record EarthBands(float coast, float hills, float fine) {}

    /** Samples the canonical Earth bands. Rejects null, zero directions and lunar geography. Worker-safe. */
    public EarthBands earthBands(SpaceVector direction) {
        if (kind != Kind.EARTH || direction == null) {
            throw new IllegalArgumentException("Earth bands require an Earth geography and direction");
        }
        SpaceVector unit = direction.normalized();
        return earthBands((float) unit.x(), (float) unit.y(), (float) unit.z(), shaderSeed());
    }

    private static EarthBands earthBands(float x, float y, float z, int key) {
        float coast = (noise(x * 3.1f, y * 3.1f, z * 3.1f, key) - noise(3.1f, 0, 0, key)) * 320;
        float hills = (noise(x * 8192, y * 8192, z * 8192, key ^ 0x71E1) - 0.5f) * 24;
        float fine = (noise(x * 32768, y * 32768, z * 32768, key ^ 0xB135) - 0.5f) * 6;
        return new EarthBands(coast, hills, fine);
    }

    /** Samples a finite nonzero body-fixed direction. No longitude seam, mutable random sequence or world access. */
    public Sample sample(SpaceVector direction) {
        if (direction == null) { throw new IllegalArgumentException("Surface direction must not be null"); }
        SpaceVector unit = direction.normalized();
        float x = (float) unit.x(), y = (float) unit.y(), z = (float) unit.z();
        int key = shaderSeed();
        float height;
        Material material;
        if (kind == Kind.MOON) {
            height = 18 + (noise(x * 8, y * 8, z * 8, key) - 0.5f) * 40
                    + (noise(x * 4096, y * 4096, z * 4096, key ^ 0x71E1) - 0.5f) * 8
                    + craters(x * 1600, y * 1600, z * 1600, key ^ 0xC4A7);
            material = height > 32 ? Material.ROCK : Material.REGOLITH;
        } else {
            // Pin this fixed anchor to a broad coast; local meter-scale hills still follow the same spherical field.
            EarthBands bands = earthBands(x, y, z, key);
            height = bands.coast() + bands.hills() + bands.fine();
            material = height < 0 ? Material.OCEAN_FLOOR : Math.abs(y) > 0.82f ? Material.ICE
                    : height < 3 ? Material.SAND : height > 68 ? Material.ROCK : Material.GRASS;
        }
        height = Math.clamp(height, (float) MIN_HEIGHT_METERS, (float) MAX_HEIGHT_METERS);
        return new Sample(height, material, kind == Kind.EARTH && height < 0);
    }

    private static float craters(float x, float y, float z, int seed) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        float result = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = bx + dx, cy = by + dy, cz = bz + dz;
                    float px = x - (cx + 0.15f + 0.7f * hash(cx, cy, cz, seed));
                    float py = y - (cy + 0.15f + 0.7f * hash(cx, cy, cz, seed ^ 0x51A3));
                    float pz = z - (cz + 0.15f + 0.7f * hash(cx, cy, cz, seed ^ 0xA73D));
                    float radius = 0.35f + 0.25f * hash(cx, cy, cz, seed ^ 0x3C17);
                    float q = (float) Math.sqrt(px * px + py * py + pz * pz) / radius;
                    float bowl = Math.max(0, 1 - q * q);
                    float rim = Math.max(0, 1 - Math.abs(q - 1) / 0.22f);
                    result += -30 * bowl * bowl + 10 * rim * rim;
                }
            }
        }
        return result;
    }

    private static float noise(float x, float y, float z, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        float fx = smooth(x - ix), fy = smooth(y - iy), fz = smooth(z - iz);
        float lower = mix(mix(hash(ix, iy, iz, seed), hash(ix + 1, iy, iz, seed), fx),
                mix(hash(ix, iy + 1, iz, seed), hash(ix + 1, iy + 1, iz, seed), fx), fy);
        float upper = mix(mix(hash(ix, iy, iz + 1, seed), hash(ix + 1, iy, iz + 1, seed), fx),
                mix(hash(ix, iy + 1, iz + 1, seed), hash(ix + 1, iy + 1, iz + 1, seed), fx), fy);
        return mix(lower, upper, fz);
    }

    private static float smooth(float value) { return value * value * (3 - 2 * value); }
    private static float mix(float first, float second, float amount) { return first + (second - first) * amount; }

    private static float hash(int x, int y, int z, int seed) {
        int value = x * 0x8DA6B343 ^ y * 0xD8163841 ^ z * 0xCB1AB31F ^ seed;
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        value *= 0x846CA68B;
        value ^= value >>> 16;
        return (value & 0xFFFFFF) * (1.0f / 16777216.0f);
    }
}
