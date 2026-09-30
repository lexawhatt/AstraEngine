package dev.lexawhatt.astraengine.rocket;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Finite local-meter axis-aligned bounds; an empty blueprint has a zero-size box at its origin. */
public record RocketBounds(SpaceVector min, SpaceVector max) {
    public RocketBounds {
        if (min == null || max == null || min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("Rocket bounds require ordered non-null endpoints");
        }
    }

    /** Full XYZ extents in meters. */
    public SpaceVector size() {
        return max.subtract(min);
    }

    /** Local center in meters. */
    public SpaceVector center() {
        return min.add(max).multiply(0.5);
    }

    /** Smallest bounds enclosing both boxes. */
    public RocketBounds union(RocketBounds other) {
        if (other == null) {
            throw new IllegalArgumentException("Other rocket bounds must not be null");
        }
        return new RocketBounds(new SpaceVector(Math.min(min.x(), other.min.x()), Math.min(min.y(), other.min.y()),
                Math.min(min.z(), other.min.z())), new SpaceVector(Math.max(max.x(), other.max.x()),
                Math.max(max.y(), other.max.y()), Math.max(max.z(), other.max.z())));
    }
}
