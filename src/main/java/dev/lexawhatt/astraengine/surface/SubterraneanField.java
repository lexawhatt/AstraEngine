package dev.lexawhatt.astraengine.surface;

/**
 * Independently versioned cave density in body-fixed meters. Positive values are void; negative values
 * are rock. This immutable field owns no chunks or cache. Carvers supply the local roof/depth envelope.
 * Version zero preserves solid terrain; version one retains its old broad chambers. Version two follows
 * folded bedding and fractures with narrow passages, occasional junction rooms and connecting shafts.
 */
public record SubterraneanField(int version, long seed) {
    public static final int CURRENT_VERSION = 2;
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
        if (version == 2) { return beddedDensity(x, y, z); }
        double warp = (noise(x, y, z, 610, 0x51A1) - .5) * 90;
        double first = 2 * noise(x + warp, y - warp * .7, z, 185, 0x8237) - 1;
        double second = 2 * noise(x, y + warp, z - warp * .6, 205, 0xD891) - 1;
        double tunnels = (.12 - Math.sqrt(first * first + second * second)) * 85;
        double chambers = (noise(x, y, z, 240, 0x649F) - .67) * 150;
        double roughness = (noise(x, y, z, 23, 0xF411) - .5) * 2.5;
        return Math.max(tunnels, chambers) + roughness;
    }

    private double beddedDensity(double x, double y, double z) {
        double altitude = Math.sqrt(x * x + y * y + z * z) - ContinentalTerrain.RADIUS_METERS;
        double fold = (noise(x, y, z, 900, 0x1427) - .5) * 48
                + (noise(x, y, z, 150, 0x2339) - .5) * 12;
        double beddingDistance = Math.abs(Math.IEEEremainder(altitude - fold, 64));
        double warp = (noise(x, y, z, 170, 0x3521) - .5) * 26;
        double first = 2 * noise(x + warp, y - warp * .4, z, 82, 0x4657) - 1;
        double second = 2 * noise(x, y + warp, z - warp * .7, 93, 0x5779) - 1;
        double width = 3.4 + 1.6 * noise(x, y, z, 130, 0x6833);
        double height = 2.8 + 1.2 * noise(x, y, z, 110, 0x7943);
        double passage = Math.min(width - Math.min(Math.abs(first), Math.abs(second)) * 55,
                height - beddingDistance);
        double junction = Math.sqrt(first * first + second * second);
        double roomGate = noise(x, y, z, 230, 0x8A61);
        double roomStrength = ramp(.58, .78, roomGate);
        double room = Math.min(3 + 8 * roomStrength - junction * 65,
                height + 4 * roomStrength - beddingDistance);
        double shaft = 1.8 * ramp(.48, .68, roomGate) - junction * 80 - .35;
        double roughness = (noise(x, y, z, 7, 0x9B97) - .5) * .7;
        return Math.max(passage, Math.max(room, shaft)) + roughness;
    }

    /** Versioned physical depth budget; older definitions retain their exact 2.4-km envelope. */
    public int maxDepthMeters() { return version >= 2 ? 1200 : MAX_DEPTH_METERS; }

    /** Applies this saved version's roof and depth rules, without reading a world or carving a block. */
    public boolean carves(double density, double surfaceMeters, double altitudeMeters) {
        if (version < 2) { return isVoid(density, surfaceMeters, altitudeMeters); }
        if (!Double.isFinite(density) || !Double.isFinite(surfaceMeters) || !Double.isFinite(altitudeMeters)) {
            throw new IllegalArgumentException("Cave envelope requires finite density and elevations");
        }
        double depth = surfaceMeters - altitudeMeters;
        if (depth < 0 || depth > maxDepthMeters()) { return false; }
        double roof = surfaceMeters < 4 ? 64 : 8;
        if (depth < roof && !(surfaceMeters > 16 && density > 2.5)) { return false; }
        return density > Math.max(0, depth - (maxDepthMeters() - 40));
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

    private static double ramp(double low, double high, double value) {
        double t = Math.clamp((value - low) / (high - low), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
