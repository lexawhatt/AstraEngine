package dev.lexawhatt.astraengine.surface.orbit;

import java.util.List;

/**
 * Immutable observed surface summary. Level 0 covers a 16 m chunk; level 4 covers a 256 m page.
 * Sixteen cells retain average visible altitude, map color, exposed emission and observed area fraction.
 * A zero coverage cell is absent, never an invented black surface. Values are derived, not canonical blocks.
 */
public record OrbitalPatch(OrbitalSurface surface, int x, int z, int level, long revision, List<Cell> cells) {
    public static final int CELL_COUNT = 16;
    public OrbitalPatch {
        if (surface == null || level != 0 && level != 4 || Math.abs((long) x) > 2_000_000
                || Math.abs((long) z) > 2_000_000 || revision < 1 || cells == null
                || cells.size() != CELL_COUNT || cells.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("Invalid observed orbital patch");
        }
        cells = List.copyOf(cells);
    }

    /** Side length in chart meters; chunk/page positions use their own respective grids. */
    public int sizeMeters() { return 16 << level; }

    /** True if the canonical chart has any visible occupied column in this patch. */
    public boolean visible() { return cells.stream().anyMatch(cell -> cell.coverage() > 0); }

    /** Material/emission comparison independent of transport revision. */
    public boolean sameContent(OrbitalPatch other) {
        return other != null && surface.equals(other.surface) && x == other.x && z == other.z
                && level == other.level && cells.equals(other.cells);
    }

    /** Emission is mean normalized block emission over exposed columns; it never includes skylight. */
    public record Cell(float altitudeMeters, int rgb, float emission, float coverage) {
        public static final Cell EMPTY = new Cell(0, 0, 0, 0);
        public Cell {
            if (!Float.isFinite(altitudeMeters) || Math.abs(altitudeMeters) > 1_000_000 || rgb < 0 || rgb > 0xffffff
                    || !Float.isFinite(emission) || emission < 0 || emission > 1
                    || !Float.isFinite(coverage) || coverage < 0 || coverage > 1) {
                throw new IllegalArgumentException("Invalid observed orbital cell");
            }
        }
    }
}
