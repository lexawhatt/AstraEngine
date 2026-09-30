package dev.lexawhatt.astraengine.surface;

/** Edges in a cube face's column/row coordinates; names do not denote geographic compass bearings. */
public enum TileEdge {
    LEFT, RIGHT, TOP, BOTTOM;

    /** Opposite edge in the same face's coordinate frame. */
    public TileEdge opposite() {
        return switch (this) {
            case LEFT -> RIGHT;
            case RIGHT -> LEFT;
            case TOP -> BOTTOM;
            case BOTTOM -> TOP;
        };
    }
}
