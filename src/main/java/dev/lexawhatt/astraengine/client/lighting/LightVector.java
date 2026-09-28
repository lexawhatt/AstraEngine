package dev.lexawhatt.astraengine.client.lighting;

/** Finite coordinates: world blocks for positions, unit vectors for directions, RGB coefficients for color. */
public record LightVector(double x, double y, double z) {
    public LightVector {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Light coordinates must be finite");
        }
    }

    /** Returns a unit direction; rejects zero or numerically unbounded vectors. */
    public LightVector normalized() {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (!Double.isFinite(length) || length < 1e-8) {
            throw new IllegalArgumentException("Light direction must have nonzero finite length");
        }
        return new LightVector(x / length, y / length, z / length);
    }

    /** Squared distance in the same coordinate space. */
    public double distanceSquared(LightVector other) {
        double dx = x - other.x, dy = y - other.y, dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
