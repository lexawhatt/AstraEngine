package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Versioned closed cube-sphere geography, independent of Minecraft dimensions and chunk storage.
 * Six gnomonic faces are divided into 2^level cells per axis. Cells are neither equal-area nor a fixed
 * number of meters wide. A separate permanent save definition qualifies their addresses by geography ID
 * and pins this version, level and radius; constructing this value allocates no per-tile state.
 * Immutable and worker-safe. This model does not implement walking, gravity or world migration.
 */
public record PlanetaryTopology(int version, int level, double radiusMeters) {
    public static final int VERSION = 1;
    public static final int MAX_LEVEL = 24;

    public PlanetaryTopology {
        requireGrid(version, level);
        GeographicPosition.requireRadius(radiusMeters);
    }

    /** Number of columns/rows on each face; not a voxel or dimension size. */
    public int cellsPerFace() { return 1 << level; }

    /** Total distinct logical tiles, calculated without int overflow or materializing them. */
    public long tileCount() { return 6L * cellsPerFace() * cellsPerFace(); }

    /**
     * Resolves a finite nonzero body-fixed direction. Exact face ties belong to X, then Y, then Z;
     * internal grid-line ties belong to the greater column/row, while outer +1 lies in the last cell.
     * This rule supplies one canonical owner even where the closed sampling squares share boundaries.
     * Ownership applies to the represented double direction: normalizing an ideal symbolic edge may round
     * it to either side. No epsilon snapping erases the distinction between adjacent representable directions.
     */
    public PlanetaryTile locate(SpaceVector direction) {
        CubeFace face = CubeFace.containing(direction);
        // Each basis vector has exactly one nonzero component, so these products cannot overflow.
        double denominator = direction.dot(face.outward());
        return tile(face, index(direction.dot(face.u()), denominator), index(direction.dot(face.v()), denominator));
    }

    /**
     * Samples a closed tile square at dimensionless fractions u/v in [0,1], returning a body-fixed unit normal.
     * Both incident tiles sample the same seam; use locate for canonical ownership. No half-block offset is added.
     */
    public SpaceVector direction(PlanetaryTile tile, double u, double v) {
        requireTile(tile);
        requireFraction(u);
        requireFraction(v);
        return tile.face().outward().add(tile.face().u().multiply(faceCoordinate(tile.column(), u)))
                .add(tile.face().v().multiply(faceCoordinate(tile.row(), v))).normalized();
    }

    /**
     * Local tangent frame at a closed tile sample, with reference altitude zero at radiusMeters.
     * The frame is nonsingular at both poles. On a face seam, each side has its own rotated X/Z axes.
     * Transform directions through body-fixed space when changing frames rather than copying local components.
     */
    public PlanetaryFrame frame(PlanetaryTile tile, double u, double v) {
        SpaceVector up = direction(tile, u, v);
        SpaceVector x = tile.face().u().subtract(up.multiply(up.dot(tile.face().u()))).normalized();
        return new PlanetaryFrame(up.multiply(radiusMeters), x, up, PlanetaryFrame.cross(x, up).normalized());
    }

    /**
     * Resolves one of four neighbors, including rotated/reversed cube edges and polar faces.
     * Each edge has one reciprocal neighbor. Cube corners have three incident faces, not a unique diagonal tile.
     * The returned fraction mapping applies on the edge only; it is not an off-edge affine position transfer.
     */
    public TileNeighbor neighbor(PlanetaryTile source, TileEdge edge) {
        requireTile(source);
        if (edge == null) { throw new IllegalArgumentException("Tile edge is required"); }
        int column = source.column(), row = source.row(), last = cellsPerFace() - 1;
        switch (edge) {
            case LEFT -> { if (column > 0) { return internal(source, column - 1, row, edge); } }
            case RIGHT -> { if (column < last) { return internal(source, column + 1, row, edge); } }
            case TOP -> { if (row > 0) { return internal(source, column, row - 1, edge); } }
            case BOTTOM -> { if (row < last) { return internal(source, column, row + 1, edge); } }
        }
        SpaceVector outward = switch (edge) {
            case LEFT -> source.face().u().multiply(-1);
            case RIGHT -> source.face().u();
            case TOP -> source.face().v().multiply(-1);
            case BOTTOM -> source.face().v();
        };
        CubeFace target = CubeFace.containing(outward);
        double targetU = source.face().outward().dot(target.u());
        double targetV = source.face().outward().dot(target.v());
        TileEdge entry = targetU == -1 ? TileEdge.LEFT : targetU == 1 ? TileEdge.RIGHT
                : targetV == -1 ? TileEdge.TOP : TileEdge.BOTTOM;
        SpaceVector sourceAlong = vertical(edge) ? source.face().v() : source.face().u();
        SpaceVector targetAlong = vertical(entry) ? target.v() : target.u();
        boolean reversed = sourceAlong.dot(targetAlong) < 0;
        int sourceIndex = vertical(edge) ? row : column;
        int targetIndex = reversed ? last - sourceIndex : sourceIndex;
        int targetColumn = vertical(entry) ? (entry == TileEdge.LEFT ? 0 : last) : targetIndex;
        int targetRow = vertical(entry) ? targetIndex : (entry == TileEdge.TOP ? 0 : last);
        return new TileNeighbor(tile(target, targetColumn, targetRow), entry, reversed);
    }

    private TileNeighbor internal(PlanetaryTile source, int column, int row, TileEdge edge) {
        return new TileNeighbor(tile(source.face(), column, row), edge.opposite(), false);
    }

    private PlanetaryTile tile(CubeFace face, int column, int row) {
        return new PlanetaryTile(version, level, face, column, row);
    }

    private int index(double numerator, double denominator) {
        int candidate = Math.max(0, Math.min(cellsPerFace() - 1,
                (int) Math.floor((numerator / denominator + 1) * 0.5 * cellsPerFace())));
        // Quotient/addition can round across an exact dyadic grid line. Correct only that candidate,
        // comparing the represented ratio to each boundary without dividing or introducing a tolerance.
        if (candidate > 0 && compareBoundary(numerator, denominator, faceCoordinate(candidate, 0)) < 0) {
            return candidate - 1;
        }
        if (candidate < cellsPerFace() - 1
                && compareBoundary(numerator, denominator, faceCoordinate(candidate + 1, 0)) >= 0) {
            return candidate + 1;
        }
        return candidate;
    }

    private static double compareBoundary(double numerator, double denominator, double boundary) {
        if (boundary == 0) { return numerator; }
        // Power-of-two scaling makes even subnormal denominators safe for the fused product/difference.
        int exponent = Math.getExponent(denominator);
        return Math.fma(-Math.scalb(denominator, -exponent), boundary, Math.scalb(numerator, -exponent));
    }

    private double faceCoordinate(int cell, double fraction) {
        return 2.0 * (cell + fraction) / cellsPerFace() - 1;
    }

    private void requireTile(PlanetaryTile tile) {
        if (tile == null || tile.version() != version || tile.level() != level) {
            throw new IllegalArgumentException("Tile does not belong to this topology version and level");
        }
    }

    private static boolean vertical(TileEdge edge) { return edge == TileEdge.LEFT || edge == TileEdge.RIGHT; }

    static void requireGrid(int version, int level) {
        if (version != VERSION || level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Unsupported planetary topology version or level");
        }
    }

    static void requireFraction(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("Tile fraction must be finite and in [0,1]");
        }
    }
}
