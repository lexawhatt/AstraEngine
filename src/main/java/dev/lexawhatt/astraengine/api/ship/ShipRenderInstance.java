package dev.lexawhatt.astraengine.api.ship;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;

/**
 * Immutable current-frame visual placement. Origin is in world blocks; a unit quaternion rotates
 * the complete local XYZ visual into the world, including pitch and roll. Accepted quaternion
 * roundoff is normalized at construction.
 * Selection is a primitive list index, or -1 for no highlight. Consumers own motion/interpolation
 * and submit a fresh pose when needed; the engine neither stores nor simulates this placement.
 */
public record ShipRenderInstance(ShipVisual visual, SpaceVector worldOrigin, FlightOrientation orientation,
        int selectedPartIndex) {
    public ShipRenderInstance {
        if (visual == null || worldOrigin == null || orientation == null || selectedPartIndex < -1
                || selectedPartIndex >= visual.parts().size()) {
            throw new IllegalArgumentException("Ship instance requires a visual, finite pose and valid selection");
        }
        orientation = orientation.normalized();
    }

    /** Convenience upright placement; positive visual yaw rotates local +X toward world -Z. */
    public ShipRenderInstance(ShipVisual visual, SpaceVector worldOrigin, double yawDegrees, int selectedPartIndex) {
        this(visual, worldOrigin, FlightOrientation.fromAngles(-yawDegrees, 0, 0), selectedPartIndex);
    }

    /** Rotates a non-null local-block vector into world axes, without translating it. */
    public SpaceVector rotateLocal(SpaceVector point) {
        if (point == null) { throw new IllegalArgumentException("Local ship point must not be null"); }
        return orientation.left().multiply(point.x()).add(orientation.up().multiply(point.y()))
                .add(orientation.forward().multiply(point.z()));
    }
}
