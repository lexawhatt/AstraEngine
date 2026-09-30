package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Version-one body-fixed cube faces. IDs and axes are persistent geography, never enum ordinals.
 * Local column and row increase along u and v; u cross outward equals v. Faces are gnomonic,
 * not equal-area, and polar faces have ordinary nonsingular local coordinates.
 */
public enum CubeFace {
    POSITIVE_X("px", new SpaceVector(1, 0, 0), new SpaceVector(0, 0, -1), new SpaceVector(0, -1, 0)),
    NEGATIVE_X("nx", new SpaceVector(-1, 0, 0), new SpaceVector(0, 0, 1), new SpaceVector(0, -1, 0)),
    POSITIVE_Y("py", new SpaceVector(0, 1, 0), new SpaceVector(1, 0, 0), new SpaceVector(0, 0, 1)),
    NEGATIVE_Y("ny", new SpaceVector(0, -1, 0), new SpaceVector(1, 0, 0), new SpaceVector(0, 0, -1)),
    POSITIVE_Z("pz", new SpaceVector(0, 0, 1), new SpaceVector(1, 0, 0), new SpaceVector(0, -1, 0)),
    NEGATIVE_Z("nz", new SpaceVector(0, 0, -1), new SpaceVector(-1, 0, 0), new SpaceVector(0, -1, 0));

    private final String id;
    private final SpaceVector outward;
    private final SpaceVector u;
    private final SpaceVector v;

    CubeFace(String id, SpaceVector outward, SpaceVector u, SpaceVector v) {
        this.id = id;
        this.outward = outward;
        this.u = u;
        this.v = v;
    }

    /** Stable lowercase serialized face identity. */
    public String id() { return id; }
    /** Body-fixed outward unit axis. */
    public SpaceVector outward() { return outward; }
    /** Body-fixed unit axis of increasing face column. */
    public SpaceVector u() { return u; }
    /** Body-fixed unit axis of increasing face row. */
    public SpaceVector v() { return v; }

    /** Resolves an exact persistent ID; null, aliases and unknown IDs throw. */
    public static CubeFace fromId(String id) {
        for (CubeFace face : values()) {
            if (face.id.equals(id)) { return face; }
        }
        throw new IllegalArgumentException("Unknown cube face: " + id);
    }

    /**
     * Selects the largest absolute component of a finite nonzero direction, with exact ties owned
     * by X before Y before Z. No tolerance band changes ownership near edges or cube corners.
     */
    public static CubeFace containing(SpaceVector direction) {
        if (direction == null) {
            throw new IllegalArgumentException("Cube face requires a nonzero direction");
        }
        double x = Math.abs(direction.x()), y = Math.abs(direction.y()), z = Math.abs(direction.z());
        if (x == 0 && y == 0 && z == 0) {
            throw new IllegalArgumentException("Cube face requires a nonzero direction");
        }
        if (x >= y && x >= z) { return direction.x() >= 0 ? POSITIVE_X : NEGATIVE_X; }
        if (y >= z) { return direction.y() >= 0 ? POSITIVE_Y : NEGATIVE_Y; }
        return direction.z() >= 0 ? POSITIVE_Z : NEGATIVE_Z;
    }
}
