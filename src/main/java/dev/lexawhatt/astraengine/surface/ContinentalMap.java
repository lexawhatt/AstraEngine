package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.nio.FloatBuffer;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Immutable presentation samples of the canonical continental field. RGBA stores height in meters,
 * temperature in Celsius, moisture and mountain mask. Worker-safe, with no world, GPU or clock ownership.
 * These bounded maps do not generate chunks and are never authoritative collision or landing data.
 */
public final class ContinentalMap {
    private final int width;
    private final int height;
    private final float[] values;

    private ContinentalMap(int width, int height, float[] values) {
        this.width = width;
        this.height = height;
        this.values = values;
    }

    /**
     * Bakes an equirectangular globe with both longitude endpoints and poles included. Height must be odd
     * in [3,1025]; width is twice height minus one. Duplicate meridian/pole values are bit-identical.
     * Null arguments fail; cancellation throws before allocation or at the next row.
     */
    public static ContinentalMap globe(ContinentalTerrain terrain, int height, BooleanSupplier cancelled) {
        require(terrain, cancelled);
        if (height < 3 || height > 1025 || (height & 1) == 0) {
            throw new IllegalArgumentException("Continental globe height must be odd and in [3,1025]");
        }
        checkCancelled(cancelled);
        int width = height * 2 - 1;
        float[] values = new float[width * height * 4];
        for (int row = 0; row < height; row++) {
            checkCancelled(cancelled);
            boolean pole = row == 0 || row == height - 1;
            double latitude = -Math.PI / 2 + Math.PI * row / (height - 1);
            for (int column = 0; column < width - 1; column++) {
                if (pole && column > 0) {
                    System.arraycopy(values, row * width * 4, values, (row * width + column) * 4, 4);
                } else {
                    double longitude = -Math.PI + Math.PI * 2 * column / (width - 1);
                    SpaceVector direction = pole ? new SpaceVector(0, row == 0 ? -1 : 1, 0)
                            : new GeographicPosition(latitude, longitude, 0).normal();
                    put(values, (row * width + column) * 4, terrain.sample(direction));
                }
            }
            System.arraycopy(values, row * width * 4, values, (row * width + width - 1) * 4, 4);
        }
        return new ContinentalMap(width, height, values);
    }

    /**
     * Bakes a local gnomonic tile using the exact same sampler as the globe and saved chunks.
     * A non-null Earth-radius grid is required. Cancellation is checked before allocation and once per row.
     */
    public static ContinentalMap tile(ContinentalTerrain terrain, SurfaceHeightTile.Grid grid,
            BooleanSupplier cancelled) {
        require(terrain, cancelled);
        if (grid == null || grid.radiusMeters() != ContinentalTerrain.RADIUS_METERS) {
            throw new IllegalArgumentException("Continental tile requires the canonical Earth radius");
        }
        checkCancelled(cancelled);
        int size = grid.resolution();
        float[] values = new float[size * size * 4];
        double half = grid.halfWidthMeters();
        for (int row = 0; row < size; row++) {
            checkCancelled(cancelled);
            for (int column = 0; column < size; column++) {
                put(values, (row * size + column) * 4, terrain.sample(grid.direction(
                        column * grid.spacingMeters() - half, row * grid.spacingMeters() - half)));
            }
        }
        return new ContinentalMap(size, size, values);
    }

    /** Number of texels in a row. */
    public int width() { return width; }

    /** Number of texel rows. */
    public int height() { return height; }

    /** Copies RGBA texels into caller-owned storage; never exposes the backing array. */
    public void writeTo(FloatBuffer destination) {
        if (destination == null) { throw new IllegalArgumentException("Continental upload destination is required"); }
        destination.put(values);
    }

    private static void put(float[] values, int offset, ContinentalTerrain.Sample sample) {
        values[offset] = (float) sample.heightMeters();
        values[offset + 1] = (float) sample.temperature();
        values[offset + 2] = (float) sample.moisture();
        values[offset + 3] = (float) sample.mountainMask();
    }

    private static void require(ContinentalTerrain terrain, BooleanSupplier cancelled) {
        if (terrain == null || cancelled == null) {
            throw new IllegalArgumentException("Continental map requires terrain and a cancellation token");
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) { throw new CancellationException("Continental map request was retired"); }
    }
}
