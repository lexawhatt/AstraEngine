package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Bounded host-chart inspection displacement. Distances are meters per server tick, not selected speed. */
public final class InspectionFlightStep {
    public static final double TERRAIN_SPEED = 2560;
    public static final double AIR_SPEED = 51_200;
    private static final double SEAM_MARGIN = 32;

    private InspectionFlightStep() {}

    /** Limits horizontal chunk demand while preserving the requested heading. */
    public static SpaceVector corridor(SpaceVector movement) {
        double horizontal = Math.hypot(movement.x(), movement.z());
        return horizontal > 128 ? movement.multiply(128 / horizontal) : movement;
    }

    /**
     * Approaches only seams in the movement direction. Close crossings retain the existing8m/tick
     * observation envelope; moving away from or parallel to another seam does not throttle flight.
     */
    public static SpaceVector seam(CubeStorageChart chart, SpaceVector feet, SpaceVector movement) {
        double length = movement.length();
        if (length == 0) { return movement; }
        double fraction = Math.min(fraction(feet.x(), movement.x(), -chart.radiusMeters(), chart.radiusMeters()),
                Math.min(fraction(feet.z(), movement.z(), -chart.radiusMeters(), chart.radiusMeters()),
                        fraction(feet.y(), movement.y(), chart.minY(), chart.minY() + chart.height())));
        double distance = fraction * length;
        if (distance < SEAM_MARGIN + length) {
            return movement.multiply(Math.min(length, Math.max(8, distance - SEAM_MARGIN)) / length);
        }
        return movement;
    }

    private static double fraction(double position, double movement, double minimum, double maximum) {
        if (movement > 0) { return (maximum - position) / movement; }
        if (movement < 0) { return (minimum - position) / movement; }
        return Double.POSITIVE_INFINITY;
    }
}
