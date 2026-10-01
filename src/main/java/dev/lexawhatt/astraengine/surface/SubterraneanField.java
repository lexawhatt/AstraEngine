package dev.lexawhatt.astraengine.surface;

/**
 * Independently versioned cave density in body-fixed meters. Positive values are void; negative values
 * are rock. This immutable field owns no chunks or cache. Carvers supply the local roof/depth envelope.
 * Version zero preserves legacy solid terrain. Version one joins broad chambers with intersecting tunnels.
 */
public record SubterraneanField(int version, long seed) {
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_DEPTH_METERS = 2400;

    /** Unknown saved versions are rejected, never substituted with current terrain. */
    public SubterraneanField {
        if (version < 0 || version > CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported underground version: " + version);
        }
    }

    /** Finite body-fixed meters; the same physical point has the same density in every storage chart/band. */
    public double density(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Cave coordinates must be finite meters");
        }
        if (version == 0) { return -Double.MAX_VALUE; }
        double warp = (noise(x, y, z, 610, 0x51A1) - .5) * 90;
        double first = 2 * noise(x + warp, y - warp * .7, z, 185, 0x8237) - 1;
        double second = 2 * noise(x, y + warp, z - warp * .6, 205, 0xD891) - 1;
        double tunnels = (.12 - Math.sqrt(first * first + second * second)) * 85;
        double chambers = (noise(x, y, z, 240, 0x649F) - .67) * 150;
        double roughness = (noise(x, y, z, 23, 0xF411) - .5) * 2.5;
        return Math.max(tunnels, chambers) + roughness;
    }

    /**
     * Roofs protect seas and shallow soil; broad dry-land cavities can form sinkhole entrances.
     * The deep envelope closes over the last 80 meters instead of ending in a flat cut plane.
     * Surface and altitude are physical meters, with altitude at the sampled block center.
     */
    public static boolean isVoid(double density, double surfaceMeters, double altitudeMeters) {
        if (!Double.isFinite(density) || !Double.isFinite(surfaceMeters) || !Double.isFinite(altitudeMeters)) {
            throw new IllegalArgumentException("Cave envelope requires finite density and elevations");
        }
        double depth = surfaceMeters - altitudeMeters;
        if (depth < 0 || depth > MAX_DEPTH_METERS) { return false; }
        double roof = surfaceMeters < 4 ? 64 : 12;
        if (depth < roof && !(surfaceMeters > 16 && density > 18)) { return false; }
        return density > Math.max(0, depth - (MAX_DEPTH_METERS - 80));
    }

    private double noise(double x, double y, double z, double scale, long salt) {
        return ContinentalTerrain.noise(x / scale, y / scale, z / scale, seed ^ salt);
    }
}
