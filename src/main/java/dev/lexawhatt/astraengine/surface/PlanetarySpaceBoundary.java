package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Optional;

/** Physical reference boundary shared by surface storage and free inspection, independent of atmosphere density. */
public final class PlanetarySpaceBoundary {
    public static final double ALTITUDE_METERS = 100_000;
    public static final double INSIDE_OFFSET_METERS = .01;
    private PlanetarySpaceBoundary() { }

    /**
     * First inward intersection of an eye-position segment with the100-km feet-altitude shell. The supplied
     * instantaneous body frame must match both segment endpoints' orbital reference. Tangencies and outward
     * motion are not entries. Finite double coordinates are required; no heightfield or host chunk is sampled.
     */
    public static Optional<Hit> entry(BodyFixedFrame frame, SpaceVector start, SpaceVector end, double eyeHeightMeters) {
        if (frame == null || start == null || end == null || !Double.isFinite(eyeHeightMeters)
                || eyeHeightMeters < 0 || eyeHeightMeters > 16) {
            throw new IllegalArgumentException("A physical boundary segment and bounded eye height are required");
        }
        var origin = start.subtract(frame.centerMeters());
        var motion = end.subtract(start);
        double length = motion.length();
        if (length == 0) { return Optional.empty(); }
        var direction = motion.multiply(1 / length);
        double along = -origin.dot(direction);
        if (along <= 0) { return Optional.empty(); }
        double radius = frame.radiusMeters() + ALTITUDE_METERS + eyeHeightMeters;
        var perpendicular = origin.add(direction.multiply(along));
        double distanceSquared = perpendicular.dot(perpendicular);
        if (distanceSquared >= radius * radius) { return Optional.empty(); }
        double entry = along - Math.sqrt(radius * radius - distanceSquared);
        if (entry < -.02 || entry > length) { return Optional.empty(); }
        entry = Math.max(0, entry);
        var body = frame.toBodyDirection(origin.add(direction.multiply(entry)));
        var address = GeographicPosition.fromBody(body, frame.radiusMeters());
        return Optional.of(new Hit(entry / length,
                new GeographicPosition(address.latitudeRadians(), address.longitudeRadians(), ALTITUDE_METERS - INSIDE_OFFSET_METERS)));
    }

    /** Numeric request candidate only; the server must prepare real blocks and validate its live session before transfer. */
    public record Hit(double fraction, GeographicPosition feet) {
        public Hit {
            if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1 || feet == null) {
                throw new IllegalArgumentException("Invalid inward shell intersection");
            }
        }
    }
}
