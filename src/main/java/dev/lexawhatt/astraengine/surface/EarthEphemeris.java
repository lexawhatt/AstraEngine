package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import dev.lexawhatt.astraengine.sky.SkyEphemeris;

/**
 * Pure mapping of the existing host calendar to canonical Sol orbits and Earth orientation. No time is
 * advanced and no descriptor is mutated. Default 365 game days span one canonical Earth revolution;
 * orbital seconds are therefore distinct from real elapsed animation/evolution seconds. Thread-independent.
 */
public final class EarthEphemeris {
    private static final double TAU = Math.PI * 2;
    private static final double SOLAR_PERIHELION = SkyEphemeris.SOLAR_PERIHELION_LONGITUDE;
    private static final CosmosSystem SOL = CosmosGenerator.sol();
    private static final CelestialBody EARTH = SOL.bodies().stream().filter(body -> body.id().equals("earth"))
            .findFirst().orElseThrow();

    private EarthEphemeris() {}

    /**
     * Resolves signed host dayTime and [0,1] partial tick into an immutable physical frame. Uses the configured
     * year/season/tilt policy; the canonical Earth ellipse is unchanged even for a custom sky eccentricity.
     * Complete orbit counts are retained, so other Sol bodies do not reset when Earth's year wraps.
     * Orbital epoch precision is that of double seconds; calendar phase/rotation is reduced before conversion.
     */
    public static Sample sample(PlanetarySkyProfile profile, long dayTime, double partialTick) {
        if (profile == null) { throw new IllegalArgumentException("Earth ephemeris requires a sky profile"); }
        var sky = SkyEphemeris.sample(profile.withLatitudeDegrees(0), dayTime, partialTick);
        long yearTicks = (long) profile.yearDays() * SkyEphemeris.TICKS_PER_DAY;
        double withinYear = (Math.floorMod(dayTime, yearTicks) + partialTick) / yearTicks
                + profile.seasonOffsetDays() / profile.yearDays();
        double profileMean = meanAnomaly(-SOLAR_PERIHELION, profile.eccentricity()) + TAU * withinYear;
        double canonicalMean = meanAnomaly(sky.seasonPhase() * TAU - SOLAR_PERIHELION, EARTH.eccentricity());
        // Unwrap the canonical phase next to the game's phase; customized sky eccentricity changes the
        // calendar-to-orbit rate, not the descriptor ellipse or an independent simulation accumulator.
        double correction = Math.IEEEremainder(canonicalMean - profileMean, TAU);
        double revolutions = 1.0 + Math.floorDiv(dayTime, yearTicks)
                + (profileMean + correction - EARTH.phaseRadians()) / TAU;
        double orbitalSeconds = revolutions * EARTH.orbitalPeriodSeconds();
        // The catalog's +XZ orbital motion has north -Y. Calibrate that plane against the sky's solar
        // perihelion and eastward right ascension, then apply the host calendar's sidereal rotation.
        FlightOrientation orientation = BodyFixedFrame.compose(yRotation(SOLAR_PERIHELION - Math.PI),
                BodyFixedFrame.compose(xRotation(Math.PI - Math.toRadians(profile.axialTiltDegrees())),
                        yRotation(sky.siderealAngleRadians())));
        return new Sample(orbitalSeconds,
                new BodyFixedFrame(SOL.positionAt(EARTH, orbitalSeconds), orientation, EARTH.radiusMeters()));
    }

    private static double meanAnomaly(double trueAnomaly, double eccentricity) {
        double eccentricAnomaly = 2 * Math.atan2(Math.sqrt(1 - eccentricity) * Math.sin(trueAnomaly / 2),
                Math.sqrt(1 + eccentricity) * Math.cos(trueAnomaly / 2));
        double value = (eccentricAnomaly - eccentricity * Math.sin(eccentricAnomaly)) % TAU;
        return value < 0 ? value + TAU : value;
    }

    private static FlightOrientation xRotation(double angle) {
        return new FlightOrientation(Math.sin(angle / 2), 0, 0, Math.cos(angle / 2));
    }

    private static FlightOrientation yRotation(double angle) {
        return new FlightOrientation(0, Math.sin(angle / 2), 0, Math.cos(angle / 2));
    }

    /** Canonical orbital sampling time and the matching body-fixed Earth transform; owns no mutable state. */
    public record Sample(double orbitalSeconds, BodyFixedFrame frame) {
        public Sample {
            if (!Double.isFinite(orbitalSeconds) || frame == null) {
                throw new IllegalArgumentException("Earth ephemeris sample requires finite time and a physical frame");
            }
        }
    }
}
