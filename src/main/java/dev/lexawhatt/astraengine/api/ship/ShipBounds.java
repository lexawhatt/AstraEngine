package dev.lexawhatt.astraengine.api.ship;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Immutable, ordered local-block bounds used for visual framing and conservative picking only. */
public record ShipBounds(SpaceVector min, SpaceVector max) {
    public ShipBounds {
        if (min == null || max == null || min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("Ship visual bounds require ordered non-null endpoints");
        }
    }

    /** Full XYZ extents in local blocks. */
    public SpaceVector size() { return max.subtract(min); }

    /** Local center in blocks. */
    public SpaceVector center() { return min.multiply(0.5).add(max.multiply(0.5)); }

    /** Smallest box enclosing this box and the non-null other box. */
    public ShipBounds union(ShipBounds other) {
        if (other == null) { throw new IllegalArgumentException("Other ship bounds must not be null"); }
        return new ShipBounds(new SpaceVector(Math.min(min.x(), other.min.x()), Math.min(min.y(), other.min.y()),
                Math.min(min.z(), other.min.z())), new SpaceVector(Math.max(max.x(), other.max.x()),
                Math.max(max.y(), other.max.y()), Math.max(max.z(), other.max.z())));
    }
}
