package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Immutable orthonormal tangent frame in body-fixed meters. X follows increasing face column, Y is radial up,
 * and Z completes x cross up = z. X/Z are not globally east/south at polar faces. The point transform is a
 * local affine tangent-plane transform, not a spherical position mapping or a global constant block metric.
 * No world references, simulation clock or mutable state are held; all methods are worker-safe.
 */
public record PlanetaryFrame(SpaceVector originMeters, SpaceVector xAxis, SpaceVector upAxis, SpaceVector zAxis) {
    public PlanetaryFrame {
        if (originMeters == null || xAxis == null || upAxis == null || zAxis == null
                || !Double.isFinite(originMeters.length()) || originMeters.length() == 0
                || Math.abs(xAxis.length() - 1) > 1e-12 || Math.abs(upAxis.length() - 1) > 1e-12
                || Math.abs(zAxis.length() - 1) > 1e-12 || Math.abs(xAxis.dot(upAxis)) > 1e-12
                || cross(xAxis, upAxis).distance(zAxis) > 1e-12
                || originMeters.normalized().distance(upAxis) > 1e-12) {
            throw new IllegalArgumentException("Planetary frame requires a radial, right-handed orthonormal basis");
        }
    }

    /** Translates local tangent meters into body-centered meters; rejects null and nonfinite results. */
    public SpaceVector toBodyPoint(SpaceVector localMeters) { return originMeters.add(toBodyDirection(localMeters)); }

    /** Inverse affine point transform; subtraction uses doubles before projection. */
    public SpaceVector toLocalPoint(SpaceVector bodyMeters) {
        if (bodyMeters == null) { throw new IllegalArgumentException("Body position is required"); }
        return toLocalDirection(bodyMeters.subtract(originMeters));
    }

    /** Rotates any finite tangent vector, retaining its units and length. */
    public SpaceVector toBodyDirection(SpaceVector localDirection) {
        if (localDirection == null) { throw new IllegalArgumentException("Local direction is required"); }
        return xAxis.multiply(localDirection.x()).add(upAxis.multiply(localDirection.y()))
                .add(zAxis.multiply(localDirection.z()));
    }

    /** Inverse vector transform, retaining units and length. */
    public SpaceVector toLocalDirection(SpaceVector bodyDirection) {
        if (bodyDirection == null) { throw new IllegalArgumentException("Body direction is required"); }
        return new SpaceVector(bodyDirection.dot(xAxis), bodyDirection.dot(upAxis), bodyDirection.dot(zAxis));
    }

    /** Unit quaternion carrying a complete local pose orientation into the body-fixed frame. */
    public FlightOrientation orientation() { return BodyFixedFrame.fromAxes(xAxis, upAxis, zAxis); }

    /** Rotates a complete local orientation without a yaw/pitch conversion, preserving roll. */
    public FlightOrientation toBodyOrientation(FlightOrientation localOrientation) {
        return BodyFixedFrame.compose(orientation(), localOrientation);
    }

    /** Inverse complete orientation transform. */
    public FlightOrientation toLocalOrientation(FlightOrientation bodyOrientation) {
        return BodyFixedFrame.compose(BodyFixedFrame.inverse(orientation()), bodyOrientation);
    }

    static SpaceVector cross(SpaceVector first, SpaceVector second) {
        return new SpaceVector(first.y() * second.z() - first.z() * second.y(),
                first.z() * second.x() - first.x() * second.z(), first.x() * second.y() - first.y() * second.x());
    }
}
