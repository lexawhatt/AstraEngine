package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Immutable instantaneous transform; all positions are double meters, independent of side, world or GPU lifetime. */
public record BodyFixedFrame(SpaceVector centerMeters, FlightOrientation bodyToSystem, double radiusMeters) {
    public BodyFixedFrame {
        if (centerMeters == null || bodyToSystem == null) {
            throw new IllegalArgumentException("Body frame position and orientation must not be null");
        }
        GeographicPosition.requireRadius(radiusMeters);
        bodyToSystem = bodyToSystem.normalized();
    }

    /** Resolves all parent orbits, then uses Rx(axialTilt) * Ry(-spin), inverse of the orbital material transform. */
    public static BodyFixedFrame of(CosmosSystem system, CelestialBody body, double orbitalSeconds, double spinRadians) {
        if (system == null || body == null || !Double.isFinite(spinRadians)) {
            throw new IllegalArgumentException("Body frame requires a system, member body and finite spin radians");
        }
        FlightOrientation rotation = FlightOrientation.fromAngles(0, Math.toDegrees(body.axialTiltRadians()), 0)
                .rotateLocal(Math.toDegrees(Math.IEEEremainder(spinRadians, Math.PI * 2)), 0, 0);
        return new BodyFixedFrame(system.positionAt(body, orbitalSeconds), rotation, body.radiusMeters());
    }

    /** Body-centered meters to system-local meters, including the full resolved parent translation. */
    public SpaceVector toSystemPoint(SpaceVector bodyMeters) { return centerMeters.add(toSystemDirection(bodyMeters)); }
    /** System-local meters to body-centered meters; subtraction happens before rotation or float conversion. */
    public SpaceVector toBodyPoint(SpaceVector systemMeters) {
        if (systemMeters == null) { throw new IllegalArgumentException("System position must not be null"); }
        return toBodyDirection(systemMeters.subtract(centerMeters));
    }
    /** Rotates a direction or relative velocity without translation; no orbital/spin transport velocity is added. */
    public SpaceVector toSystemDirection(SpaceVector bodyDirection) { return rotate(bodyToSystem, bodyDirection); }
    /** Inverse direction transform, preserving the input length and units. */
    public SpaceVector toBodyDirection(SpaceVector systemDirection) { return rotate(inverse(bodyToSystem), systemDirection); }
    /** Transforms a full orientation, preserving roll and pole crossings. */
    public FlightOrientation toSystemOrientation(FlightOrientation bodyOrientation) {
        return compose(bodyToSystem, bodyOrientation);
    }
    /** Inverse of toSystemOrientation. */
    public FlightOrientation toBodyOrientation(FlightOrientation systemOrientation) {
        return compose(inverse(bodyToSystem), systemOrientation);
    }

    static SpaceVector rotate(FlightOrientation rotation, SpaceVector vector) {
        if (vector == null) { throw new IllegalArgumentException("Direction must not be null"); }
        return rotation.left().multiply(vector.x()).add(rotation.up().multiply(vector.y()))
                .add(rotation.forward().multiply(vector.z()));
    }

    static FlightOrientation compose(FlightOrientation first, FlightOrientation second) {
        if (second == null) { throw new IllegalArgumentException("Orientation must not be null"); }
        return FlightOrientation.normalized(first.w() * second.x() + first.x() * second.w()
                        + first.y() * second.z() - first.z() * second.y(),
                first.w() * second.y() - first.x() * second.z() + first.y() * second.w() + first.z() * second.x(),
                first.w() * second.z() + first.x() * second.y() - first.y() * second.x() + first.z() * second.w(),
                first.w() * second.w() - first.x() * second.x() - first.y() * second.y() - first.z() * second.z());
    }

    static FlightOrientation inverse(FlightOrientation value) {
        return new FlightOrientation(-value.x(), -value.y(), -value.z(), value.w());
    }

    static FlightOrientation fromAxes(SpaceVector x, SpaceVector y, SpaceVector z) {
        double trace = x.x() + y.y() + z.z();
        if (trace > 0) {
            double scale = Math.sqrt(trace + 1) * 2;
            return FlightOrientation.normalized((y.z() - z.y()) / scale, (z.x() - x.z()) / scale,
                    (x.y() - y.x()) / scale, scale / 4);
        }
        if (x.x() > y.y() && x.x() > z.z()) {
            double scale = Math.sqrt(1 + x.x() - y.y() - z.z()) * 2;
            return FlightOrientation.normalized(scale / 4, (y.x() + x.y()) / scale,
                    (z.x() + x.z()) / scale, (y.z() - z.y()) / scale);
        }
        if (y.y() > z.z()) {
            double scale = Math.sqrt(1 + y.y() - x.x() - z.z()) * 2;
            return FlightOrientation.normalized((y.x() + x.y()) / scale, scale / 4,
                    (z.y() + y.z()) / scale, (z.x() - x.z()) / scale);
        }
        double scale = Math.sqrt(1 + z.z() - x.x() - y.y()) * 2;
        return FlightOrientation.normalized((z.x() + x.z()) / scale, (z.y() + y.z()) / scale,
                scale / 4, (x.y() - y.x()) / scale);
    }
}
