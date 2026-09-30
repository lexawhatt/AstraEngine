package dev.lexawhatt.astraengine.surface;

/**
 * Immutable versioned tile address within one geography definition. A save owner MUST qualify this address
 * with its permanent geography ID; the same address on two planets does not identify the same terrain.
 * Constructing an address creates no chunk, dimension, file or registry allocation. Worker-safe.
 */
public record PlanetaryTile(int version, int level, CubeFace face, int column, int row) {
    public PlanetaryTile {
        PlanetaryTopology.requireGrid(version, level);
        if (face == null || column < 0 || row < 0 || column >= (1 << level) || row >= (1 << level)) {
            throw new IllegalArgumentException("Planetary tile is outside its versioned cube grid");
        }
    }

    /** Exact unqualified ASCII address: v1/level/face/column/row, using canonical unsigned decimal integers. */
    public String key() { return "v" + version + "/" + level + "/" + face.id() + "/" + column + "/" + row; }

    /** Parses only canonical addresses. Null, unknown versions, aliases, overflow and out-of-grid values throw. */
    public static PlanetaryTile parse(String key) {
        if (key == null || key.length() > 64) {
            throw new IllegalArgumentException("Planetary tile key is missing or too long");
        }
        String[] parts = key.split("/", -1);
        if (parts.length != 5 || !parts[0].equals("v1")) {
            throw new IllegalArgumentException("Invalid or unsupported planetary tile key: " + key);
        }
        try {
            PlanetaryTile tile = new PlanetaryTile(PlanetaryTopology.VERSION, Integer.parseInt(parts[1]),
                    CubeFace.fromId(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
            if (!tile.key().equals(key)) { throw new IllegalArgumentException("Noncanonical planetary tile key: " + key); }
            return tile;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid planetary tile key numbers: " + key, exception);
        }
    }
}
