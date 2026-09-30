package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Planet-fixed latitude/longitude in radians and radial altitude in meters; longitude increases eastward. */
public record GeographicPosition(double latitudeRadians, double longitudeRadians, double altitudeMeters) {
    public GeographicPosition {
        if (!Double.isFinite(latitudeRadians) || Math.abs(latitudeRadians) > Math.PI / 2
                || !Double.isFinite(longitudeRadians) || longitudeRadians < -Math.PI || longitudeRadians >= Math.PI
                || !Double.isFinite(altitudeMeters)) {
            throw new IllegalArgumentException("Invalid geographic latitude, longitude or altitude");
        }
    }

    /** Unit normal in body-fixed coordinates: +Y north, +X zero longitude, -Z east at zero longitude. */
    public SpaceVector normal() {
        double cosine = Math.cos(latitudeRadians);
        return new SpaceVector(cosine * Math.cos(longitudeRadians), Math.sin(latitudeRadians),
                -cosine * Math.sin(longitudeRadians));
    }

    /** Converts to body-centered meters; the resulting radial distance must be strictly positive. */
    public SpaceVector toBody(double radiusMeters) {
        requireRadius(radiusMeters);
        double distance = radiusMeters + altitudeMeters;
        if (!Double.isFinite(distance) || distance <= 0) {
            throw new IllegalArgumentException("Geographic altitude must lie outside the body's center");
        }
        return normal().multiply(distance);
    }

    /** Inverts body-centered meters. The exact poles use longitude zero; the center has no geographic position. */
    public static GeographicPosition fromBody(SpaceVector bodyMeters, double radiusMeters) {
        requireRadius(radiusMeters);
        if (bodyMeters == null || bodyMeters.length() == 0 || !Double.isFinite(bodyMeters.length())) {
            throw new IllegalArgumentException("A geographic position requires a finite nonzero body vector");
        }
        double horizontal = Math.hypot(bodyMeters.x(), bodyMeters.z());
        double longitude = horizontal == 0 ? 0 : Math.atan2(-bodyMeters.z(), bodyMeters.x());
        if (longitude >= Math.PI) { longitude -= Math.PI * 2; }
        return new GeographicPosition(Math.atan2(bodyMeters.y(), horizontal), longitude,
                bodyMeters.length() - radiusMeters);
    }

    static void requireRadius(double radiusMeters) {
        if (!Double.isFinite(radiusMeters) || radiusMeters <= 0 || radiusMeters > 1e12) {
            throw new IllegalArgumentException("Surface radius must be finite and in (0, 1e12] meters");
        }
    }
}
