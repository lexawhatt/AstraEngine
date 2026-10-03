package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Optional;

/**
 * Immutable projection of one persistent cube-face storage band. Host positions use gnomonic chart meters;
 * radial altitude is translated, never scaled. Implementations identify one terrain realization and grant
 * ownership only inside {@link #contains}. Extensions are numeric views, never duplicate writable storage.
 */
public interface CubeStorageChart extends GeographicReference {
    CubeFace face();
    int band();
    double radiusMeters();
    int altitudeOriginMeters();
    int minY();
    int height();

    /** No-load chart identity lookup. Absent means outside supported radial storage; null face is invalid. */
    Optional<CubeStorageChart> chart(CubeFace face, int band);

    /** Unit body-fixed direction, also valid in a chart's read-only visual extension. */
    default SpaceVector normal(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Chart coordinates must be finite");
        }
        return face().outward().multiply(radiusMeters()).add(face().u().multiply(x))
                .add(face().v().multiply(z)).normalized();
    }

    /** Orthonormal view/light frame. This is not the differential used to convert host movement. */
    default PlanetaryFrame tangentFrame(double x, double z, double altitudeMeters) {
        if (!Double.isFinite(altitudeMeters) || radiusMeters() + altitudeMeters <= 0) {
            throw new IllegalArgumentException("A chart frame must lie outside its body center");
        }
        SpaceVector up = normal(x, z);
        SpaceVector east = face().u().subtract(up.multiply(up.dot(face().u()))).normalized();
        return new PlanetaryFrame(up.multiply(radiusMeters() + altitudeMeters), east, up,
                PlanetaryFrame.cross(east, up));
    }

    /** Geographic address without granting ownership, including a bounded neighboring chart extension. */
    default GeographicPosition projectedGeographic(SpaceVector feet) {
        if (feet == null) { throw new IllegalArgumentException("Chart feet are required"); }
        var angular = GeographicPosition.fromBody(normal(feet.x(), feet.z()), 1);
        return new GeographicPosition(angular.latitudeRadians(), angular.longitudeRadians(),
                feet.y() + altitudeOriginMeters());
    }

    /** Canonical half-open band and face owner. This performs no world allocation or chunk reads. */
    default Optional<CubeStorageChart> ownerChart(GeographicPosition address) {
        if (address == null) { throw new IllegalArgumentException("A geographic address is required"); }
        double value = Math.floor((address.altitudeMeters() - minY()) / height());
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) { return Optional.empty(); }
        int candidate = (int) value;
        // Correct the rounding of a representable position immediately below a translated boundary.
        if (address.altitudeMeters() < (double) candidate * height() + minY()) { candidate--; }
        return chart(CubeFace.containing(address.normal()), candidate);
    }

    @Override default boolean contains(SpaceVector feet) {
        return feet != null && feet.y() >= minY() && feet.y() < minY() + height()
                && Math.abs(feet.x()) <= radiusMeters() && Math.abs(feet.z()) <= radiusMeters()
                && CubeFace.containing(normal(feet.x(), feet.z())) == face();
    }

    @Override default GeographicPosition geographic(SpaceVector feet) {
        if (!contains(feet)) { throw new IllegalArgumentException("Feet are outside their canonical chart"); }
        return projectedGeographic(feet);
    }

    @Override default Optional<SpaceVector> resolve(GeographicPosition address) {
        if (!ownerChart(address).map(this::equals).orElse(false)) { return Optional.empty(); }
        SpaceVector up = address.normal();
        double scale = radiusMeters() / up.dot(face().outward());
        return Optional.of(new SpaceVector(up.dot(face().u()) * scale,
                address.altitudeMeters() - altitudeOriginMeters(), up.dot(face().v()) * scale));
    }

    /** Inverse differential: body-relative physical meters/tick to local host chart meters/tick at these feet. */
    default SpaceVector localVelocity(SpaceVector feet, SpaceVector bodyVelocity) {
        if (feet == null || bodyVelocity == null) { throw new IllegalArgumentException("A position and velocity are required"); }
        var address = projectedGeographic(feet);
        SpaceVector body = address.toBody(radiusMeters());
        double denominator = body.dot(face().outward());
        double scale = radiusMeters() / (denominator * denominator);
        double radial = bodyVelocity.dot(face().outward());
        return new SpaceVector((bodyVelocity.dot(face().u()) * denominator - body.dot(face().u()) * radial) * scale,
                bodyVelocity.dot(address.normal()),
                (bodyVelocity.dot(face().v()) * denominator - body.dot(face().v()) * radial) * scale);
    }

    @Override default PlanetaryPose pose(SpaceVector feet, SpaceVector velocity, FlightOrientation orientation) {
        if (!contains(feet) || velocity == null || orientation == null) {
            throw new IllegalArgumentException("A chart pose requires canonical feet, velocity and orientation");
        }
        SpaceVector plane = face().outward().multiply(radiusMeters()).add(face().u().multiply(feet.x()))
                .add(face().v().multiply(feet.z()));
        SpaceVector up = plane.normalized();
        double altitude = feet.y() + altitudeOriginMeters();
        SpaceVector horizontal = face().u().multiply(velocity.x()).add(face().v().multiply(velocity.z()));
        SpaceVector physical = horizontal.subtract(up.multiply(up.dot(horizontal)))
                .multiply((radiusMeters() + altitude) / plane.length()).add(up.multiply(velocity.y()));
        var frame = tangentFrame(feet.x(), feet.z(), altitude);
        return new PlanetaryPose(frame.originMeters(), physical, frame.toBodyOrientation(orientation));
    }
}
