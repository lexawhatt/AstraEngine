package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Bounded analytic arrival/departure route in body-fixed meters, with no mutable clock or world references.
 * The owner must reapply the current body frame each tick, authorize the endpoint and check other-body collisions.
 * A nominal sphere is never crossed as a shortcut; negative terrain elevations permit only the final radial shell.
 * Endpoint distances are limited to the existing local automatic-navigation envelope, not global manual flight.
 */
public record SurfaceApproach(SpaceVector startBodyMeters, SpaceVector endBodyMeters,
        double radiusMeters, boolean ascending) {
    public static final int ALIGN_TICKS = 240;
    public static final int DESCENT_TICKS = 240;
    public static final int DURATION_TICKS = ALIGN_TICKS + DESCENT_TICKS;
    private static final double RADIAL_SCALE_METERS = 32;

    public SurfaceApproach {
        GeographicPosition.requireRadius(radiusMeters);
        if (startBodyMeters == null || endBodyMeters == null || !Double.isFinite(startBodyMeters.length())
                || !Double.isFinite(endBodyMeters.length())
                || startBodyMeters.length() > FlightDynamics.LOCAL_RADIUS
                || endBodyMeters.length() > FlightDynamics.LOCAL_RADIUS
                || startBodyMeters.length() < Math.max(1, radiusMeters + SurfaceGeography.MIN_HEIGHT_METERS)
                || endBodyMeters.length() < Math.max(1, radiusMeters + SurfaceGeography.MIN_HEIGHT_METERS)) {
            throw new IllegalArgumentException("Surface approach endpoints must lie above terrain within the local navigation envelope");
        }
    }

    /** Duration in occupied simulation ticks; render fractions can sample the same immutable route. */
    public int durationTicks() { return DURATION_TICKS; }

    /** Samples [0,480] ticks, preserving both endpoints exactly; elapsed/offline time is the owner's responsibility. */
    public SpaceVector positionAt(double elapsedTicks) {
        if (!Double.isFinite(elapsedTicks) || elapsedTicks < 0 || elapsedTicks > DURATION_TICKS) {
            throw new IllegalArgumentException("Surface approach time must be within its finite route");
        }
        if (elapsedTicks == 0) { return startBodyMeters; }
        if (elapsedTicks == DURATION_TICKS) { return endBodyMeters; }
        return ascending ? descending(endBodyMeters, startBodyMeters, DURATION_TICKS - elapsedTicks)
                : descending(startBodyMeters, endBodyMeters, elapsedTicks);
    }

    /** Unit travel direction; a stationary degenerate route uses a stable tangent instead of an undefined vector. */
    public SpaceVector travelDirectionAt(double elapsedTicks) {
        positionAt(elapsedTicks);
        SpaceVector difference = positionAt(Math.min(DURATION_TICKS, elapsedTicks + 0.1))
                .subtract(positionAt(Math.max(0, elapsedTicks - 0.1)));
        return difference.length() > 1e-8 ? difference.normalized() : perpendicular(endBodyMeters.normalized());
    }

    /**
     * Body-fixed guided camera with an upright local horizon and an altitude-dependent downward pitch.
     * The endpoint's projected heading is parallel-transported along the route, avoiding geographic-pole and
     * vanishing-projection flips. The first/last 60 ticks smoothly acquire/release guidance; both supplied
     * endpoint quaternions are returned exactly. This pure sample owns no camera state or simulation clock.
     */
    public FlightOrientation orientationAt(double elapsedTicks, FlightOrientation startBody, FlightOrientation endBody) {
        if (startBody == null || endBody == null) {
            throw new IllegalArgumentException("Surface camera guidance requires both endpoint orientations");
        }
        positionAt(elapsedTicks);
        if (elapsedTicks == 0) { return startBody; }
        if (elapsedTicks == DURATION_TICKS) { return endBody; }
        // Fixed handoff anchors prevent shortest-arc selection from switching as a moving target crosses 180 degrees.
        if (elapsedTicks < 60) {
            return startBody.interpolate(guidedOrientationAt(60, endBody), smooth(elapsedTicks / 60));
        }
        if (elapsedTicks > DURATION_TICKS - 60) {
            return guidedOrientationAt(DURATION_TICKS - 60, endBody).interpolate(endBody,
                    smooth((elapsedTicks - (DURATION_TICKS - 60)) / 60));
        }
        return guidedOrientationAt(elapsedTicks, endBody);
    }

    private FlightOrientation guidedOrientationAt(double elapsedTicks, FlightOrientation endBody) {
        SpaceVector position = positionAt(elapsedTicks);
        SpaceVector normal = GeographicPosition.fromBody(position, radiusMeters).normal();
        SpaceVector endNormal = endBodyMeters.normalized();
        SpaceVector heading = endBody.forward().subtract(endNormal.multiply(endBody.forward().dot(endNormal)));
        if (heading.length() < 1e-8) {
            heading = endBody.up().subtract(endNormal.multiply(endBody.up().dot(endNormal)));
        }
        heading = heading.normalized();
        SpaceVector startNormal = startBodyMeters.normalized();
        double dot = Math.clamp(startNormal.dot(endNormal), -1, 1);
        SpaceVector tangent = endNormal.subtract(startNormal.multiply(dot));
        tangent = tangent.length() < 1e-12 ? perpendicular(startNormal) : tangent.normalized();
        SpaceVector axis = cross(startNormal, tangent).normalized();
        double alignment = ascending ? smooth(Math.clamp((elapsedTicks - DESCENT_TICKS) / ALIGN_TICKS, 0, 1))
                : smooth(Math.clamp(elapsedTicks / ALIGN_TICKS, 0, 1));
        double rotation = Math.acos(dot) * (alignment - 1);
        heading = rotateAround(heading, axis, rotation);
        heading = heading.subtract(normal.multiply(heading.dot(normal))).normalized();

        double horizonDip = Math.acos(Math.clamp(radiusMeters / position.length(), 0, 1));
        double pitch = Math.min(Math.toRadians(85), Math.toRadians(15) + horizonDip);
        SpaceVector forward = heading.multiply(Math.cos(pitch)).subtract(normal.multiply(Math.sin(pitch)));
        SpaceVector up = normal.multiply(Math.cos(pitch)).add(heading.multiply(Math.sin(pitch)));
        return BodyFixedFrame.fromAxes(cross(up, forward), up, forward);
    }

    private SpaceVector descending(SpaceVector from, SpaceVector to, double elapsedTicks) {
        double initialRadius = from.length(), finalRadius = to.length();
        double clearanceRadius = Math.max(radiusMeters + 10000, Math.max(initialRadius, finalRadius));
        if (elapsedTicks <= ALIGN_TICKS) {
            double blend = smooth(elapsedTicks / ALIGN_TICKS);
            SpaceVector normal = greatCircle(from.normalized(), to.normalized(), blend);
            return normal.multiply(initialRadius + (clearanceRadius - initialRadius) * blend);
        }
        double blend = smooth((elapsedTicks - ALIGN_TICKS) / DESCENT_TICKS);
        double clearance = clearanceRadius - finalRadius;
        // Astronomical linear interpolation still moves kilometers in its final tick. Logarithmic clearance
        // allocates the last seconds to human-scale altitude while retaining zero endpoint derivatives.
        double remaining = RADIAL_SCALE_METERS
                * Math.expm1(Math.log1p(clearance / RADIAL_SCALE_METERS) * (1 - blend));
        return to.normalized().multiply(finalRadius + Math.clamp(remaining, 0, clearance));
    }

    private static SpaceVector greatCircle(SpaceVector from, SpaceVector to, double blend) {
        if (blend == 0) { return from; }
        if (blend == 1) { return to; }
        double dot = Math.clamp(from.dot(to), -1, 1);
        SpaceVector tangent = to.subtract(from.multiply(dot));
        if (tangent.length() < 1e-12) {
            if (dot > 0) { return from; }
            tangent = perpendicular(from);
        } else {
            tangent = tangent.normalized();
        }
        double angle = Math.acos(dot) * blend;
        return from.multiply(Math.cos(angle)).add(tangent.multiply(Math.sin(angle))).normalized();
    }

    private static SpaceVector perpendicular(SpaceVector normal) {
        SpaceVector axis = Math.abs(normal.x()) < 0.8 ? new SpaceVector(1, 0, 0) : new SpaceVector(0, 1, 0);
        return axis.subtract(normal.multiply(axis.dot(normal))).normalized();
    }

    private static SpaceVector cross(SpaceVector first, SpaceVector second) {
        return new SpaceVector(first.y() * second.z() - first.z() * second.y(),
                first.z() * second.x() - first.x() * second.z(), first.x() * second.y() - first.y() * second.x());
    }

    private static SpaceVector rotateAround(SpaceVector vector, SpaceVector axis, double angle) {
        double cosine = Math.cos(angle), sine = Math.sin(angle);
        return vector.multiply(cosine).add(cross(axis, vector).multiply(sine))
                .add(axis.multiply(axis.dot(vector) * (1 - cosine)));
    }

    private static double smooth(double value) { return value * value * (3 - 2 * value); }
}
