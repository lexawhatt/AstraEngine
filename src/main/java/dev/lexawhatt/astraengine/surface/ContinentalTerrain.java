package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Optional;

/**
 * Versioned Earth-scale spherical continental height and climate field. Immutable and worker-safe; it owns no
 * worlds, chunks, random sequence or simulation clock. Heights are physical meters above the shared sea radius,
 * independent of Minecraft's storage height. This is authored procedural relief, not measured Earth geography
 * or a tectonic or erosion simulation. Version three includes regional routed drainage; version four uses
 * confluence-continuous curves with narrower valley walls and riparian moisture.
 * Existing {@link PlanetaryTerrain} definitions are unchanged.
 */
public final class ContinentalTerrain {
    public static final int VERSION = 1;
    public static final int CURRENT_VERSION = 4;
    public static final long SEED = 0x4153545241434F4EL;
    public static final double RADIUS_METERS = 6_371_000;
    public static final double MIN_ELEVATION = -7000;
    public static final double MAX_ELEVATION = 10000;

    private final int version;
    private final long seed;
    private final RiverAtlas rivers;

    /** Unknown versions fail. Versions three and later prepare drainage; construct on a startup/worker thread. */
    public ContinentalTerrain(int version, long seed) {
        requireVersion(version);
        this.version = version;
        this.seed = seed;
        rivers = version < 3 ? null : version == 3
                ? seed == SEED ? CanonicalDrainage.ATLAS : RiverAtlas.prepare(seed)
                : seed == SEED ? CanonicalCurvedDrainage.ATLAS : RiverAtlas.prepareCurved(seed);
    }

    /** Validate a persisted algorithm without allocating or preparing geography. */
    public static void requireVersion(int version) {
        if (version < VERSION || version > CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported continental terrain version: " + version);
        }
    }

    /** Called during parallel mod setup, before world loading or client rendering can request this geography. */
    public static void prepareCanonical() { CanonicalDrainage.ATLAS.cellCount(); CanonicalCurvedDrainage.ATLAS.cellCount(); }

    private static final class CanonicalDrainage {
        private static final RiverAtlas ATLAS = RiverAtlas.prepare(SEED);
    }

    private static final class CanonicalCurvedDrainage {
        private static final RiverAtlas ATLAS = RiverAtlas.prepareCurved(SEED);
    }

    /** Persisted algorithm, never implicitly migrated. */
    public int version() { return version; }
    /** Immutable procedural seed. */
    public long seed() { return seed; }
    /** Regional drainage for versions three and later; older definitions have no river atlas. */
    public Optional<RiverAtlas> rivers() { return Optional.ofNullable(rivers); }

    @Override public boolean equals(Object value) {
        return value instanceof ContinentalTerrain other && version == other.version && seed == other.seed;
    }
    @Override public int hashCode() { return 31 * version + Long.hashCode(seed); }
    @Override public String toString() { return "ContinentalTerrain[version=" + version + ", seed=" + seed + "]"; }

    /**
     * Immutable physical surface observation. Height is meters above sea level, temperature is approximate
     * Celsius, moisture and mountainMask are in [0,1]. Continentality is a signed land/ocean field in [-1,1];
     * its zero contour defines the coastline before small-scale relief. waterMeters is the greater of the bed
     * and physical water level; equality means dry land. No biome or block palette is implied.
     */
    public record Sample(double heightMeters, double temperature, double moisture,
            double continentality, double mountainMask, double waterMeters) {
        /** Legacy samples retain the global sea plane with no inland water. */
        public Sample(double heightMeters, double temperature, double moisture, double continentality, double mountainMask) {
            this(heightMeters, temperature, moisture, continentality, mountainMask, Math.max(0, heightMeters));
        }

        /** True when this column contains liquid, including ocean water below the zero sea datum. */
        public boolean water() { return waterMeters > heightMeters; }
        /** Above-sea-level channel water; ocean mouths remain ocean. */
        public boolean river() { return water() && waterMeters > 0; }
        public Sample {
            if (!Double.isFinite(heightMeters) || heightMeters < MIN_ELEVATION || heightMeters > MAX_ELEVATION
                    || !Double.isFinite(waterMeters) || waterMeters < Math.max(0, heightMeters) || waterMeters > MAX_ELEVATION
                    || !Double.isFinite(temperature) || !unitInterval(moisture) || !unitInterval(mountainMask)
                    || !Double.isFinite(continentality) || Math.abs(continentality) > 1) {
                throw new IllegalArgumentException("Invalid continental height or climate observation");
            }
        }

        private static boolean unitInterval(double value) {
            return Double.isFinite(value) && value >= 0 && value <= 1;
        }
    }

    /**
     * Samples a finite nonzero body-fixed direction, normalized internally so distance from the center does not
     * change geography. Three-dimensional coordinates avoid longitude seams and pole singularities. Null and
     * zero directions are rejected. No input is interpreted as a host block position or altitude window.
     */
    public Sample sample(SpaceVector direction) {
        if (direction == null) {
            throw new IllegalArgumentException("Continental terrain direction is required");
        }
        SpaceVector normal = direction.normalized();
        if (version >= 3) { return rivers.shape(normal, ContinentalTerrainV3.base(normal, seed)); }
        if (version == 2) { return ContinentalTerrainV2.sample(normal, seed); }
        double x = normal.x() * RADIUS_METERS;
        double y = normal.y() * RADIUS_METERS;
        double z = normal.z() * RADIUS_METERS;
        double wx = x + (at(x, y, z, 1_100_000, 0x12D3) - .5) * 600_000;
        double wy = y + (at(x, y, z, 1_100_000, 0xA9E1) - .5) * 600_000;
        double wz = z + (at(x, y, z, 1_100_000, 0x6B45) - .5) * 600_000;
        double continents = .75 * at(wx, wy, wz, 2_500_000, 0x734A)
                + .20 * at(wx, wy, wz, 900_000, 0x215F)
                + .05 * at(wx, wy, wz, 220_000, 0xF471);
        double continentality = Math.clamp((continents - .54) * 2, -1, 1);
        double inland = ramp(0, .065, continentality);

        // A broad contour band yields coherent ranges, with both regional ridges and local craggy relief.
        double beltDistance = Math.abs(at(wx, wy, wz, 1_400_000, 0xB137) - .5);
        double mountainMask = (1 - ramp(.035, .155, beltDistance)) * inland;
        double height;
        if (continentality < 0) {
            double offshore = -continentality;
            double shelf = -180 * ramp(0, .045, offshore);
            double slope = -4100 * ramp(.035, .18, offshore);
            double abyss = -1400 * ramp(.18, .55, offshore);
            double floorRelief = (at(x, y, z, 32_000, 0x524D) - .5) * 180 * ramp(.025, .12, offshore);
            height = shelf + slope + abyss + floorRelief;
        } else {
            double plains = 80 * ramp(0, .02, continentality) + 950 * continentality
                    + (at(x, y, z, 42_000, 0x927A) - .5) * 120 * inland;
            double regionalRidge = ridge(at(x, y, z, 40_000, 0x469B));
            double localRidge = ridge(at(x, y, z, 2200, 0xD123));
            double relief = .15 + .85 * (.60 * regionalRidge * regionalRidge * regionalRidge
                    + .40 * localRidge * localRidge);
            double strength = .70 + .30 * at(wx, wy, wz, 750_000, 0x41AC);
            double fine = (at(x, y, z, 300, 0x12CA) - .5) * 100 * inland;
            height = plains + mountainMask * (1000 + 7800 * strength * relief) + fine;
        }

        double latitude = Math.abs(normal.y());
        double temperature = 31 - 48 * Math.pow(latitude, 1.15) - Math.max(0, height) * .0065;
        double equatorialRain = 1 - ramp(.10, .42, latitude);
        double subtropicalDryness = Math.exp(-Math.pow((latitude - .48) / .15, 2));
        double weather = at(x, y, z, 380_000, 0x83AE);
        double moisture = Math.clamp(.28 + .40 * weather + .24 * equatorialRain
                - .24 * subtropicalDryness - .12 * mountainMask, 0, 1);
        return new Sample(height, temperature, moisture, continentality, mountainMask);
    }

    private double at(double x, double y, double z, double scaleMeters, long salt) {
        return noise(x / scaleMeters, y / scaleMeters, z / scaleMeters, seed ^ salt);
    }

    private static double ridge(double value) { return 1 - Math.abs(2 * value - 1); }

    private static double ramp(double lower, double upper, double value) {
        return smooth(Math.clamp((value - lower) / (upper - lower), 0, 1));
    }

    static double noise(double x, double y, double z, long seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = smooth(x - ix), fy = smooth(y - iy), fz = smooth(z - iz);
        double low = mix(mix(hash(ix, iy, iz, seed), hash(ix + 1, iy, iz, seed), fx),
                mix(hash(ix, iy + 1, iz, seed), hash(ix + 1, iy + 1, iz, seed), fx), fy);
        double high = mix(mix(hash(ix, iy, iz + 1, seed), hash(ix + 1, iy, iz + 1, seed), fx),
                mix(hash(ix, iy + 1, iz + 1, seed), hash(ix + 1, iy + 1, iz + 1, seed), fx), fy);
        return mix(low, high, fz);
    }

    private static double smooth(double value) {
        // Polynomial roundoff can exceed one by a few ULPs near the endpoint. This bounds interpolation
        // weights, not physical terrain height, and prevents a negative mountain mask at a smooth edge.
        return Math.clamp(value * value * value * (value * (value * 6 - 15) + 10), 0, 1);
    }
    private static double mix(double first, double second, double fraction) { return first + (second - first) * fraction; }

    private static double hash(int x, int y, int z, long seed) {
        long value = seed ^ (long) x * 0x9E3779B97F4A7C15L ^ (long) y * 0xBF58476D1CE4E5B9L
                ^ (long) z * 0x94D049BB133111EBL;
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }
}
