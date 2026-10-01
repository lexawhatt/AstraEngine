package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

import java.util.OptionalDouble;

/**
 * Smooth-sphere horizon geometry in physical meters, without terrain, atmospheric refraction or world mutation.
 * Stateless calculations are safe on any thread. A tangent-space ray uses east/up/south axes at the observer;
 * it has no camera yaw, pitch or roll applied. Saved body radii and host positions are never changed.
 */
public final class PlanetaryHorizon {
    private static final double UNIT_LENGTH_TOLERANCE = 1e-12;
    private static final double TANGENT_ULPS = 8;

    private PlanetaryHorizon() { }

    /**
     * Straight-line distance in meters from an eye at radial altitude to the tangent on a smooth sphere.
     * Radius must be in (0, 1e12] meters and altitude finite and nonnegative. Zero altitude returns zero.
     * Uses h * (2R + h) without subtracting two almost equal squared radii, retaining low-altitude precision.
     */
    public static double horizonDistance(double radiusMeters, double altitudeMeters) {
        requireObserver(radiusMeters, altitudeMeters);
        return Math.hypot(altitudeMeters, Math.sqrt(altitudeMeters) * Math.sqrt(2 * radiusMeters));
    }

    /**
     * Horizon depression below the observer's local horizontal, in radians in [0, pi/2].
     * Uses the same radius and altitude bounds as {@link #horizonDistance}; no refraction correction is added.
     */
    public static double horizonDip(double radiusMeters, double altitudeMeters) {
        return Math.atan2(horizonDistance(radiusMeters, altitudeMeters), radiusMeters);
    }

    /**
     * Nearest forward intersection distance in meters with a smooth reference sphere, or empty for sky.
     * The observer is at radial altitude and the ray is a tangent-space unit direction with +Y radial up.
     * Radius and altitude obey {@link #horizonDistance}; ray length must be within 1e-12 of one and is normalized
     * internally. At zero altitude, inward and exactly tangent rays hit at zero; outward rays ignore the origin
     * contact and miss. Negative altitude is rejected rather than turning the sphere into an underground mask.
     *
     * <p>The quadratic uses a stable product-of-roots calculation for the near hit. Within eight ULPs of the
     * squared normalized discriminant terms, the ray is classified as tangent. This relative roundoff tolerance
     * does not impose a fixed angular band and therefore preserves meter-scale horizons on large bodies.
     */
    public static OptionalDouble firstIntersection(double radiusMeters, double altitudeMeters, SpaceVector unitRay) {
        double observerRadius = requireObserver(radiusMeters, altitudeMeters);
        if (unitRay == null || Math.abs(unitRay.length() - 1) > UNIT_LENGTH_TOLERANCE) {
            throw new IllegalArgumentException("Horizon ray must be a unit tangent-space direction");
        }
        SpaceVector ray = unitRay.normalized();
        if (ray.y() > 0) {
            return OptionalDouble.empty();
        }
        if (altitudeMeters == 0) {
            return OptionalDouble.of(0);
        }

        double first;
        double second;
        if (altitudeMeters <= radiusMeters) {
            // The vertical form retains h/R when (R + h) rounds to R near the surface.
            first = -ray.y();
            second = horizonDistance(radiusMeters, altitudeMeters) / observerRadius;
        } else {
            // The horizontal form retains a small distant planetary disc when the tangent's sine rounds to 1.
            first = radiusMeters / observerRadius;
            second = Math.hypot(ray.x(), ray.z());
        }
        double discriminant = (first - second) * (first + second);
        double tolerance = TANGENT_ULPS * Math.ulp(Math.max(first * first, second * second));
        if (discriminant < -tolerance) {
            return OptionalDouble.empty();
        }
        double root = Math.abs(discriminant) <= tolerance ? 0 : Math.sqrt(discriminant);
        double farRoot = -ray.y() + root;
        if (farRoot == 0) {
            return OptionalDouble.empty();
        }
        double nearDistance = altitudeMeters * (1 + radiusMeters / observerRadius) / farRoot;
        if (!Double.isFinite(nearDistance)) {
            throw new IllegalArgumentException("Horizon intersection is outside the finite meter range");
        }
        return OptionalDouble.of(nearDistance);
    }

    /**
     * Projects a host-patch point into camera-relative tangent meters: X east, Y radial up and Z south.
     * Both local inputs are positions in the supplied patch's block-sized meters, including its seaY offset.
     * The exact existing gnomonic/radial mapping is applied before body-fixed double subtraction and rotation.
     * No float conversion, camera movement, visual radius multiplier or terrain sampling occurs.
     *
     * <p>Points may be outside the playable patch, but must remain finite and outside the body's center.
     * This geometric operation does not authorize world access or make host hitboxes spherical. Camera yaw,
     * pitch, roll and projection matrices belong to the caller. Null and nonrepresentable results are rejected.
     */
    public static SpaceVector project(SurfacePatch patch, SpaceVector cameraLocalMeters, SpaceVector pointLocalMeters) {
        if (patch == null || cameraLocalMeters == null || pointLocalMeters == null) {
            throw new IllegalArgumentException("Horizon projection requires a patch, camera and point");
        }
        SpaceVector cameraBody = patch.toBody(cameraLocalMeters);
        SpaceVector pointBody = patch.toBody(pointLocalMeters);
        if (!Double.isFinite(cameraBody.length()) || !Double.isFinite(pointBody.length())) {
            throw new IllegalArgumentException("Horizon projection requires finite body positions");
        }
        return patch.toLocalDirection(cameraLocalMeters.x(), cameraLocalMeters.z(), pointBody.subtract(cameraBody));
    }

    private static double requireObserver(double radiusMeters, double altitudeMeters) {
        GeographicPosition.requireRadius(radiusMeters);
        double observerRadius = radiusMeters + altitudeMeters;
        if (!Double.isFinite(altitudeMeters) || altitudeMeters < 0 || !Double.isFinite(observerRadius)) {
            throw new IllegalArgumentException("Horizon altitude must be finite and nonnegative");
        }
        return observerRadius;
    }
}
