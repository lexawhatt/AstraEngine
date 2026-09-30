package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * One permanent bounded gnomonic patch. Local +X is east, +Y radial up and +Z south, in block-sized meters.
 * Coordinates convert analytically outside the playable bound too; callers must check contains before world access.
 * The approximation has a tangent metric at the anchor and does not implement whole-globe Minecraft gravity.
 */
public record SurfacePatch(double radiusMeters, double latitudeRadians, double longitudeRadians,
        int halfWidth, int seaY) {
    public SurfacePatch {
        GeographicPosition.requireRadius(radiusMeters);
        new GeographicPosition(latitudeRadians, longitudeRadians, 0);
        if (halfWidth < 1 || halfWidth > 1_000_000 || halfWidth > radiusMeters * 0.1
                || seaY < -2048 || seaY > 2048) {
            throw new IllegalArgumentException("Invalid bounded surface patch dimensions");
        }
    }

    /** Whether a finite local column lies inside the permanent square, including its geometric boundary. */
    public boolean contains(double x, double z) {
        return Double.isFinite(x) && Double.isFinite(z) && Math.abs(x) <= halfWidth && Math.abs(z) <= halfWidth;
    }

    /** Body-fixed unit normal at a local column; independent of local altitude. */
    public SpaceVector normal(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Surface column coordinates must be finite meters");
        }
        return anchorNormal().multiply(radiusMeters).add(east(latitudeRadians, longitudeRadians).multiply(x))
                .add(south(latitudeRadians, longitudeRadians).multiply(z)).normalized();
    }

    /** Maps local block-space meters to body-centered meters. Sea level is local y=seaY, with no half-block offset. */
    public SpaceVector toBody(SpaceVector localMeters) {
        if (localMeters == null || radiusMeters + localMeters.y() - seaY <= 0) {
            throw new IllegalArgumentException("Surface local position must lie outside the body's center");
        }
        return normal(localMeters.x(), localMeters.z()).multiply(radiusMeters + localMeters.y() - seaY);
    }

    /** Inverts a body-centered point on the anchor's open hemisphere; the returned column can be outside the patch. */
    public SpaceVector toLocal(SpaceVector bodyMeters) {
        if (bodyMeters == null) { throw new IllegalArgumentException("Body position must not be null"); }
        double forward = bodyMeters.dot(anchorNormal());
        if (forward <= 0) { throw new IllegalArgumentException("Body position is outside the patch hemisphere"); }
        return new SpaceVector(radiusMeters * bodyMeters.dot(east(latitudeRadians, longitudeRadians)) / forward,
                seaY + bodyMeters.length() - radiusMeters,
                radiusMeters * bodyMeters.dot(south(latitudeRadians, longitudeRadians)) / forward);
    }

    /** Rotates local east/up/south directions into body-fixed directions at a column, preserving camera roll. */
    public FlightOrientation orientationAt(double x, double z) {
        SpaceVector up = normal(x, z);
        GeographicPosition geographic = GeographicPosition.fromBody(up, radiusMeters);
        return BodyFixedFrame.fromAxes(east(geographic.latitudeRadians(), geographic.longitudeRadians()), up,
                south(geographic.latitudeRadians(), geographic.longitudeRadians()));
    }

    /** Rotates a local east/up/south direction at the given column without changing its magnitude. */
    public SpaceVector toBodyDirection(double x, double z, SpaceVector localDirection) {
        return BodyFixedFrame.rotate(orientationAt(x, z), localDirection);
    }

    /** Inverse tangent direction transform at the given column. */
    public SpaceVector toLocalDirection(double x, double z, SpaceVector bodyDirection) {
        return BodyFixedFrame.rotate(BodyFixedFrame.inverse(orientationAt(x, z)), bodyDirection);
    }

    /** Carries a complete local camera orientation into the body-fixed frame, preserving roll and pole crossings. */
    public FlightOrientation toBodyOrientation(double x, double z, FlightOrientation localOrientation) {
        return BodyFixedFrame.compose(orientationAt(x, z), localOrientation);
    }

    /** Inverse tangent orientation transform, with no host yaw/pitch round trip. */
    public FlightOrientation toLocalOrientation(double x, double z, FlightOrientation bodyOrientation) {
        return BodyFixedFrame.compose(BodyFixedFrame.inverse(orientationAt(x, z)), bodyOrientation);
    }

    private SpaceVector anchorNormal() { return new GeographicPosition(latitudeRadians, longitudeRadians, 0).normal(); }

    private static SpaceVector east(double latitude, double longitude) {
        return new SpaceVector(-Math.sin(longitude), 0, -Math.cos(longitude));
    }

    private static SpaceVector south(double latitude, double longitude) {
        return new SpaceVector(Math.sin(latitude) * Math.cos(longitude), -Math.cos(latitude),
                -Math.sin(latitude) * Math.sin(longitude));
    }
}
