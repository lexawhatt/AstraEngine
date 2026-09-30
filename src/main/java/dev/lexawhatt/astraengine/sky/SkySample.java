package dev.lexawhatt.astraengine.sky;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Pure ephemeris snapshot. Sun direction is a unit vector in local Minecraft axes: +X east, +Y up, +Z south.
 * Season phase is solar ecliptic longitude / 2 pi, with 0 spring, .25 summer, .5 autumn and .75 winter
 * in the northern hemisphere. Sidereal angle is the local hour angle of equatorial right ascension zero.
 * Daylight hours use the geometric solar center at altitude zero; refraction and displayed disk size are excluded.
 */
public record SkySample(SpaceVector sunDirection, double orbitalDistanceAu, double seasonPhase,
        double siderealAngleRadians, double declinationRadians, double daylightHours, double solarAltitudeRadians) {
    /** Validates an immutable snapshot; it remains safe to retain after a world or renderer is disposed. */
    public SkySample {
        if (sunDirection == null || Math.abs(sunDirection.length() - 1) > 1.0e-9
                || !Double.isFinite(orbitalDistanceAu) || orbitalDistanceAu <= 0
                || !Double.isFinite(seasonPhase) || seasonPhase < 0 || seasonPhase >= 1
                || !Double.isFinite(siderealAngleRadians)
                || !Double.isFinite(declinationRadians) || Math.abs(declinationRadians) > Math.PI / 2
                || !Double.isFinite(daylightHours) || daylightHours < 0 || daylightHours > 24
                || !Double.isFinite(solarAltitudeRadians) || Math.abs(solarAltitudeRadians) > Math.PI / 2) {
            throw new IllegalArgumentException("Invalid planetary sky sample");
        }
    }
}
