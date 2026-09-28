package dev.lexawhatt.astraengine.cosmos;

/**
 * Finite unit quaternion rotating camera-local +Z forward, +X left and +Y up into system-local space.
 * Constructor validation preserves encoded components exactly; math helpers explicitly normalize their results.
 * Host yaw/pitch/roll are derived views, never the canonical orientation or an input pole clamp.
 */
public record FlightOrientation(double x, double y, double z, double w) {
    public static final FlightOrientation IDENTITY = new FlightOrientation(0, 0, 0, 1);

    public FlightOrientation {
        double normSquared = x * x + y * y + z * z + w * w;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(w)
                || !Double.isFinite(normSquared) || Math.abs(normSquared - 1) > 1e-6) {
            throw new IllegalArgumentException("Flight orientation must be a finite unit quaternion");
        }
    }

    /** Normalizes finite nonzero math output; untrusted encoded orientations must use the strict constructor. */
    public static FlightOrientation normalized(double x, double y, double z, double w) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(w)) {
            throw new IllegalArgumentException("Quaternion components must be finite");
        }
        double scale = Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.max(Math.abs(z), Math.abs(w)));
        if (scale == 0) { throw new IllegalArgumentException("A zero quaternion has no orientation"); }
        x /= scale; y /= scale; z /= scale; w /= scale;
        double norm = Math.sqrt(x * x + y * y + z * z + w * w);
        return new FlightOrientation(x / norm, y / norm, z / norm, w / norm);
    }

    /** Explicitly normalizes accepted roundoff without changing the persisted representation implicitly. */
    public FlightOrientation normalized() { return normalized(x, y, z, w); }

    /** Builds Ry(-yaw) * Rx(pitch) * Rz(roll), in degrees, without limiting pitch or roll. */
    public static FlightOrientation fromAngles(double yaw, double pitch, double roll) {
        double halfYaw = halfAngle(-yaw), halfPitch = halfAngle(pitch), halfRoll = halfAngle(roll);
        double sy = Math.sin(halfYaw), cy = Math.cos(halfYaw);
        double sp = Math.sin(halfPitch), cp = Math.cos(halfPitch);
        double sr = Math.sin(halfRoll), cr = Math.cos(halfRoll);
        return normalized(cy * sp * cr + sy * cp * sr, sy * cp * cr - cy * sp * sr,
                cy * cp * sr - sy * sp * cr, cy * cp * cr + sy * sp * sr);
    }

    /** Unit forward direction in system-local coordinates. */
    public SpaceVector forward() { return new SpaceVector(2 * (x * z + w * y), 2 * (y * z - w * x), 1 - 2 * (x * x + y * y)); }
    /** Unit left direction in system-local coordinates. */
    public SpaceVector left() { return new SpaceVector(1 - 2 * (y * y + z * z), 2 * (x * y + w * z), 2 * (x * z - w * y)); }
    /** Unit up direction in system-local coordinates. */
    public SpaceVector up() { return new SpaceVector(2 * (x * y - w * z), 1 - 2 * (x * x + z * z), 2 * (y * z + w * x)); }

    /** Applies local yaw, pitch and roll in degrees; zero input preserves the exact encoded orientation. */
    public FlightOrientation rotateLocal(double yawDegrees, double pitchDegrees, double rollDegrees) {
        if (yawDegrees == 0 && pitchDegrees == 0 && rollDegrees == 0) { return this; }
        FlightOrientation other = fromAngles(yawDegrees, pitchDegrees, rollDegrees);
        return normalized(w * other.x + x * other.w + y * other.z - z * other.y,
                w * other.y - x * other.z + y * other.w + z * other.x,
                w * other.z + x * other.y - y * other.x + z * other.w,
                w * other.w - x * other.x - y * other.y - z * other.z);
    }

    /** Shortest-path spherical interpolation; alpha is finite in [0,1], including antipodal encodings. */
    public FlightOrientation interpolate(FlightOrientation target, double alpha) {
        if (target == null || !Double.isFinite(alpha) || alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("Invalid orientation interpolation");
        }
        if (alpha == 0) { return this; }
        if (alpha == 1) { return target; }
        FlightOrientation start = normalized(), end = target.normalized();
        double dot = start.x * end.x + start.y * end.y + start.z * end.z + start.w * end.w;
        double sign = dot < 0 ? -1 : 1;
        dot = Math.clamp(Math.abs(dot), 0, 1);
        double a, b;
        if (dot > 0.9995) { a = 1 - alpha; b = alpha * sign; }
        else {
            double angle = Math.acos(dot), denominator = Math.sin(angle);
            a = Math.sin((1 - alpha) * angle) / denominator;
            b = Math.sin(alpha * angle) / denominator * sign;
        }
        return normalized(start.x * a + end.x * b, start.y * a + end.y * b,
                start.z * a + end.z * b, start.w * a + end.w * b);
    }

    /** Equivalent host yaw in degrees; exact poles choose a stable equivalent using the left basis. */
    public float yaw() { return angles()[0]; }
    /** Equivalent host pitch in [-90,90]; unrestricted orientation remains in the quaternion. */
    public float pitch() { return angles()[1]; }
    /** Equivalent host roll in degrees, including the compensating half-turn after crossing a pole. */
    public float roll() { return angles()[2]; }

    private float[] angles() {
        SpaceVector forward = forward().normalized(), left = left().normalized();
        double horizontal = Math.hypot(forward.x(), forward.z());
        double yaw = horizontal > 1e-10 ? Math.atan2(-forward.x(), forward.z()) : Math.atan2(left.z(), left.x());
        double pitch = Math.asin(Math.clamp(-forward.y(), -1, 1));
        double cosine = Math.cos(yaw), sine = Math.sin(yaw);
        SpaceVector leftZero = new SpaceVector(cosine, 0, sine);
        SpaceVector upZero = new SpaceVector(forward.y() * sine,
                forward.z() * cosine - forward.x() * sine, -forward.y() * cosine);
        double roll = horizontal > 1e-10 ? Math.atan2(left.dot(upZero), left.dot(leftZero)) : 0;
        return new float[]{(float) Math.toDegrees(yaw), (float) Math.toDegrees(pitch), (float) Math.toDegrees(roll)};
    }
    private static double halfAngle(double degrees) {
        if (!Double.isFinite(degrees)) { throw new IllegalArgumentException("Flight rotation angles must be finite"); }
        return Math.toRadians(Math.IEEEremainder(degrees, 360)) * 0.5;
    }
}
