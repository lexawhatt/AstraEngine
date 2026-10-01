package dev.lexawhatt.astraengine.client.sky;

/**
 * Immutable periodic scalar noise for cloud presentation. Pure numeric data, safe to share between threads;
 * contains no weather state or clock. Two adjacent Z slices occupy RG channels in padded two-dimensional tiles.
 */
public final class CloudNoiseField {
    public static final int PERIOD = 64;
    public static final int TILE_SIZE = PERIOD + 2;
    public static final int TILES_PER_ROW = 8;
    public static final int ATLAS_SIZE = TILE_SIZE * TILES_PER_ROW;
    private final byte[] atlas = new byte[ATLAS_SIZE * ATLAS_SIZE * 2];

    /** Bakes 557568 bytes of presentation data. Construct during client setup, not once per frame. */
    public CloudNoiseField() {
        byte[] cells = new byte[PERIOD * PERIOD * PERIOD];
        for (int z = 0; z < PERIOD; z++) {
            for (int y = 0; y < PERIOD; y++) {
                for (int x = 0; x < PERIOD; x++) {
                    cells[(z * PERIOD + y) * PERIOD + x] = (byte) Math.round(hash(x, y, z) * 255);
                }
            }
        }
        for (int z = 0; z < PERIOD; z++) {
            for (int y = -1; y <= PERIOD; y++) {
                for (int x = -1; x <= PERIOD; x++) {
                    int at = pixel(x, y, z);
                    int column = Math.floorMod(y, PERIOD) * PERIOD + Math.floorMod(x, PERIOD);
                    atlas[at] = cells[z * PERIOD * PERIOD + column];
                    atlas[at + 1] = cells[((z + 1) % PERIOD) * PERIOD * PERIOD + column];
                }
            }
        }
    }

    /** Caller-owned interleaved RG8 bytes, topological padding included. Mutations cannot change this field. */
    public byte[] copyAtlas() { return atlas.clone(); }

    /** Smooth trilinear reference at finite lattice coordinates; every axis repeats after 64 cells. */
    public double sample(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Cloud noise requires finite lattice coordinates");
        }
        x = wrap(x); y = wrap(y); z = wrap(z);
        int ix = (int) x, iy = (int) y, iz = (int) z;
        double fx = smooth(x - ix), fy = smooth(y - iy), fz = smooth(z - iz);
        double low = mix(mix(value(ix, iy, iz, 0), value(ix + 1, iy, iz, 0), fx),
                mix(value(ix, iy + 1, iz, 0), value(ix + 1, iy + 1, iz, 0), fx), fy);
        double high = mix(mix(value(ix, iy, iz, 1), value(ix + 1, iy, iz, 1), fx),
                mix(value(ix, iy + 1, iz, 1), value(ix + 1, iy + 1, iz, 1), fx), fy);
        return mix(low, high, fz);
    }

    private double value(int x, int y, int z, int channel) {
        return Byte.toUnsignedInt(atlas[pixel(x, y, z) + channel]) / 255.0;
    }

    private static int pixel(int x, int y, int z) {
        int column = z % TILES_PER_ROW * TILE_SIZE + x + 1;
        int row = z / TILES_PER_ROW * TILE_SIZE + y + 1;
        return (row * ATLAS_SIZE + column) * 2;
    }

    private static double wrap(double value) { return (value % PERIOD + PERIOD) % PERIOD; }
    private static double smooth(double value) { return value * value * (3 - 2 * value); }
    private static double mix(double a, double b, double fraction) { return a + (b - a) * fraction; }
    private static float fract(float value) { return value - (float) Math.floor(value); }

    private static float hash(int ix, int iy, int iz) {
        float x = fract(ix * .1031f), y = fract(iy * .1030f), z = fract(iz * .0973f);
        float offset = x * (y + 33.33f) + y * (x + 33.33f) + z * (z + 33.33f);
        x += offset; y += offset; z += offset;
        return fract((x + y) * z);
    }
}
