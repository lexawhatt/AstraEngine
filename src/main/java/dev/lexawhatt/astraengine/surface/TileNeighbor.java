package dev.lexawhatt.astraengine.surface;

/**
 * Neighbor address and its entry edge. Edge fractions increase with row for LEFT/RIGHT and with column
 * for TOP/BOTTOM. Cube-face rotations can change the entry edge and reverse the fraction.
 * This is adjacency, not a player transfer or a loaded-world handle.
 */
public record TileNeighbor(PlanetaryTile tile, TileEdge entryEdge, boolean reversed) {
    public TileNeighbor {
        if (tile == null || entryEdge == null) {
            throw new IllegalArgumentException("Tile neighbor requires an address and entry edge");
        }
    }

    /** Maps a source edge fraction in [0,1] to this neighbor's entry-edge fraction; endpoints are allowed. */
    public double crossingFraction(double fraction) {
        PlanetaryTopology.requireFraction(fraction);
        return reversed ? 1 - fraction : fraction;
    }
}
