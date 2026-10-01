package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.nio.FloatBuffer;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Immutable local height-band samples for presentation. A tile is generated from the canonical sampler,
 * never from a world or chunk. Grid points include both edges; texel centers therefore need a half-texel
 * offset when uploaded. One caller-owned request may generate tiles on a host worker. No GL is referenced.
 */
public final class SurfaceHeightTile {
    /** Immutable gnomonic grid mapping. Distances and spacing are meters, coordinates are body-fixed. */
    public record Grid(SpaceVector up, SpaceVector east, SpaceVector south, double radiusMeters,
            int resolution, double spacingMeters) {
        public Grid {
            if (up == null || east == null || south == null || !Double.isFinite(radiusMeters) || radiusMeters <= 0
                    || resolution < 3 || resolution > 1025 || (resolution & 1) == 0
                    || !Double.isFinite(spacingMeters) || spacingMeters <= 0
                    || spacingMeters * (resolution - 1) > radiusMeters * 0.1
                    || Math.abs(up.length() - 1) > 1e-9 || Math.abs(east.length() - 1) > 1e-9
                    || Math.abs(south.length() - 1) > 1e-9 || Math.abs(up.dot(east)) > 1e-9
                    || Math.abs(up.dot(south)) > 1e-9 || Math.abs(east.dot(south)) > 1e-9) {
                throw new IllegalArgumentException("Height grid requires a finite orthonormal basis and bounded odd resolution");
            }
        }

        /** Constructs a stable east/up/south basis at any nonzero direction, including either pole. */
        public static Grid at(SpaceVector direction, double radiusMeters, int resolution, double spacingMeters) {
            if (direction == null) { throw new IllegalArgumentException("Height grid direction is required"); }
            var geographic = GeographicPosition.fromBody(direction, radiusMeters);
            double latitude = geographic.latitudeRadians(), longitude = geographic.longitudeRadians();
            SpaceVector up = geographic.normal();
            SpaceVector east = new SpaceVector(-Math.sin(longitude), 0, -Math.cos(longitude));
            SpaceVector south = new SpaceVector(Math.sin(latitude) * Math.cos(longitude), -Math.cos(latitude),
                    -Math.sin(latitude) * Math.sin(longitude));
            return new Grid(up, east, south, radiusMeters, resolution, spacingMeters);
        }

        /** Half the interval span; includes the edge samples. */
        public double halfWidthMeters() { return spacingMeters * (resolution - 1) * 0.5; }

        /** Direction at a continuous grid-plane position, in meters; no mutable state or terrain access. */
        public SpaceVector direction(double xMeters, double zMeters) {
            return up.multiply(radiusMeters).add(east.multiply(xMeters)).add(south.multiply(zMeters)).normalized();
        }

        /** Whether a body direction projects into the central fraction (0,1] of this tile. */
        public boolean contains(SpaceVector direction, double fraction) {
            if (direction == null || !Double.isFinite(fraction) || fraction <= 0 || fraction > 1) { return false; }
            if (direction.equals(SpaceVector.ZERO)) { return false; }
            SpaceVector unit = direction.normalized();
            double forward = unit.dot(up);
            if (forward <= 0) { return false; }
            double extent = halfWidthMeters() * fraction;
            return Math.abs(radiusMeters * unit.dot(east) / forward) <= extent
                    && Math.abs(radiusMeters * unit.dot(south) / forward) <= extent;
        }
    }

    private final Grid grid;
    private final float[] bands;

    private SurfaceHeightTile(Grid grid, float[] bands) { this.grid = grid; this.bands = bands; }

    /**
     * Bakes RGBA float texels (coast, hills, fine, unused). Cancellation is checked before allocation and
     * once per row, and throws CancellationException. The supplier must be safe to read on the worker.
     * Arguments must be non-null and geography must be Earth. Never accesses Minecraft or saved blocks.
     */
    public static SurfaceHeightTile bake(Grid grid, SurfaceGeography geography, BooleanSupplier cancelled) {
        if (grid == null || geography == null || geography.kind() != SurfaceGeography.Kind.EARTH || cancelled == null) {
            throw new IllegalArgumentException("Height baking requires an Earth geography, grid and cancellation token");
        }
        checkCancelled(cancelled);
        int size = grid.resolution();
        float[] values = new float[size * size * 4];
        double half = grid.halfWidthMeters();
        for (int z = 0; z < size; z++) {
            checkCancelled(cancelled);
            for (int x = 0; x < size; x++) {
                var sample = geography.earthBands(grid.direction(x * grid.spacingMeters() - half,
                        z * grid.spacingMeters() - half));
                int index = (z * size + x) * 4;
                values[index] = sample.coast(); values[index + 1] = sample.hills(); values[index + 2] = sample.fine();
            }
        }
        return new SurfaceHeightTile(grid, values);
    }

    /** Immutable mapping, safe to retain after releasing the height data. */
    public Grid grid() { return grid; }

    /** Copies texels into caller-owned storage and advances its position; insufficient remaining space fails. */
    public void writeTo(FloatBuffer destination) {
        if (destination == null) { throw new IllegalArgumentException("Height upload destination is required"); }
        destination.put(bands);
    }

    /** Bilinear height-band reference for error verification. Rejects finite points beyond the grid. */
    public SurfaceGeography.EarthBands sample(double xMeters, double zMeters) {
        double half = grid.halfWidthMeters();
        if (!Double.isFinite(xMeters) || !Double.isFinite(zMeters)
                || Math.abs(xMeters) > half || Math.abs(zMeters) > half) {
            throw new IllegalArgumentException("Height sample is outside the tile");
        }
        double x = (xMeters + half) / grid.spacingMeters(), z = (zMeters + half) / grid.spacingMeters();
        int ix = Math.min((int) x, grid.resolution() - 2), iz = Math.min((int) z, grid.resolution() - 2);
        return new SurfaceGeography.EarthBands(interpolate(ix, iz, x - ix, z - iz, 0),
                interpolate(ix, iz, x - ix, z - iz, 1), interpolate(ix, iz, x - ix, z - iz, 2));
    }

    private float interpolate(int x, int z, double fx, double fz, int component) {
        int index = (z * grid.resolution() + x) * 4 + component;
        int next = index + grid.resolution() * 4;
        double first = bands[index] * (1 - fx) + bands[index + 4] * fx;
        double second = bands[next] * (1 - fx) + bands[next + 4] * fx;
        return (float) (first * (1 - fz) + second * fz);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) { throw new CancellationException("Height tile request was retired"); }
    }
}
