package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.nio.FloatBuffer;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Immutable samples of a saved solid planet: RGBA stores physical bed elevation followed by display RGB.
 * Globe, local relief and host blocks all use SolidPlanetTerrain. This is bounded presentation
 * data, never collision authority or a full planet chunk pregenerator.
 */
public final class SolidPlanetMap {
    private final int width;
    private final int height;
    private final float[] values;

    private SolidPlanetMap(int width, int height, float[] values) {
        this.width = width;
        this.height = height;
        this.values = values;
    }

    /**
     * Bakes an equirectangular globe with both longitude endpoints and poles included. Height must be odd
     * in [3,1025]; width is twice height minus one. Duplicate meridian/pole values are bit-identical.
     * Null arguments fail; cancellation throws before allocation or at the next row.
     */
    public static SolidPlanetMap globe(SolidPlanetTerrain terrain, int height, SolidPlanetPalette palette, BooleanSupplier cancelled) {
        require(terrain, palette, cancelled);
        if (height < 3 || height > 1025 || (height & 1) == 0) {
            throw new IllegalArgumentException("Planetary globe height must be odd and in [3,1025]");
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
                    put(values, (row * width + column) * 4, terrain.sample(direction), palette);
                }
            }
            System.arraycopy(values, row * width * 4, values, (row * width + width - 1) * 4, 4);
        }
        return new SolidPlanetMap(width, height, values);
    }

    /**
     * Bakes a local gnomonic tile using the exact same sampler as the globe and saved chunks.
     * A non-null profile-radius grid is required. Cancellation is checked before allocation and once per row.
     */
    public static SolidPlanetMap tile(SolidPlanetTerrain terrain, SurfaceHeightTile.Grid grid,
            SolidPlanetPalette palette, BooleanSupplier cancelled) {
        require(terrain, palette, cancelled);
        if (grid == null || grid.radiusMeters() != terrain.profile().radiusMeters()) {
            throw new IllegalArgumentException("Planetary tile requires the saved planet radius");
        }
        checkCancelled(cancelled);
        int size = grid.resolution();
        float[] values = new float[size * size * 4];
        double half = grid.halfWidthMeters();
        for (int row = 0; row < size; row++) {
            checkCancelled(cancelled);
            for (int column = 0; column < size; column++) {
                put(values, (row * size + column) * 4, terrain.sample(grid.direction(
                        column * grid.spacingMeters() - half, row * grid.spacingMeters() - half)), palette);
            }
        }
        return new SolidPlanetMap(size, size, values);
    }

    /** Number of texels in a row. */
    public int width() { return width; }

    /** Number of texel rows. */
    public int height() { return height; }

    /** Copies RGBA texels into caller-owned storage; never exposes the backing array. */
    public void writeTo(FloatBuffer destination) {
        if (destination == null) { throw new IllegalArgumentException("Planetary upload destination is required"); }
        destination.put(values);
    }

    private static void put(float[] values, int offset, SolidPlanetTerrain.Sample sample, SolidPlanetPalette palette) {
        var color = palette.color(sample);
        values[offset] = (float) sample.heightMeters();
        values[offset + 1] = (float) color.x();
        values[offset + 2] = (float) color.y();
        values[offset + 3] = (float) color.z();
    }

    private static void require(SolidPlanetTerrain terrain, SolidPlanetPalette palette, BooleanSupplier cancelled) {
        if (terrain == null || palette == null || cancelled == null) {
            throw new IllegalArgumentException("Planetary map requires terrain and a cancellation token");
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) { throw new CancellationException("Planetary map request was retired"); }
    }
}
