package dev.lexawhatt.astraengine.cosmos;

/** Immutable finite double vector. Callers must keep meters and light-years in separate coordinate spaces. */
public record SpaceVector(double x, double y, double z) {
    public static final SpaceVector ZERO = new SpaceVector(0, 0, 0);

    public SpaceVector {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Space coordinates must be finite");
        }
    }

    /** Returns a sum in the same units; rejects null or a non-finite result. */
    public SpaceVector add(SpaceVector other) {
        requireVector(other);
        return new SpaceVector(x + other.x, y + other.y, z + other.z);
    }

    /** Subtracts in double precision before coordinates are narrowed for presentation. */
    public SpaceVector subtract(SpaceVector other) {
        requireVector(other);
        return new SpaceVector(x - other.x, y - other.y, z - other.z);
    }

    /** Multiplies by a finite scalar; rejects a non-finite result. */
    public SpaceVector multiply(double scalar) {
        if (!Double.isFinite(scalar)) {
            throw new IllegalArgumentException("Space vector scalar must be finite");
        }
        return new SpaceVector(x * scalar, y * scalar, z * scalar);
    }

    /** Euclidean length in the vector's units; overflow is possible only beyond the double range. */
    public double length() {
        return Math.hypot(Math.hypot(x, y), z);
    }

    /** Euclidean distance in the vectors' common units. */
    public double distance(SpaceVector other) {
        return subtract(other).length();
    }

    /** Returns a unit direction, rejecting zero. Scaling avoids overflow and subnormal underflow. */
    public SpaceVector normalized() {
        double scale = Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z));
        if (scale == 0) {
            throw new IllegalArgumentException("A zero space vector has no direction");
        }
        double sx = x / scale;
        double sy = y / scale;
        double sz = z / scale;
        double magnitude = Math.sqrt(sx * sx + sy * sy + sz * sz);
        return new SpaceVector(sx / magnitude, sy / magnitude, sz / magnitude);
    }

    /** Scalar product; callers are responsible for using compatible units. */
    public double dot(SpaceVector other) {
        requireVector(other);
        return x * other.x + y * other.y + z * other.z;
    }

    private static void requireVector(SpaceVector vector) {
        if (vector == null) {
            throw new IllegalArgumentException("Space vector must not be null");
        }
    }
}
