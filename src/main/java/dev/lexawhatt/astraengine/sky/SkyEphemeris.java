package dev.lexawhatt.astraengine.sky;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Deterministic Kepler orbit and uniform axial rotation. This is a reusable game ephemeris, not a civil-date
 * observatory prediction. Minecraft owns time; sampling cannot advance it. Day zero starts at the spring
 * equinox, with the mean solar noon at tick 6000. An Earth-like perihelion orientation is held fixed.
 */
public final class SkyEphemeris {
    public static final int TICKS_PER_DAY = 24_000;
    private static final double TAU = Math.PI * 2;
    // Earth's heliocentric perihelion longitude + pi, viewed as the Sun's longitude from Earth.
    private static final double SOLAR_PERIHELION_LONGITUDE = Math.toRadians(282.94);

    private SkyEphemeris() {
    }

    /**
     * Evaluates any signed host dayTime without converting a huge absolute tick count to double.
     * Partial tick must be finite and in [0, 1]; callers pass zero while host time is frozen.
     * Negative times wrap periodically. Both profiles and results are immutable and safe on either side.
     */
    public static SkySample sample(PlanetarySkyProfile profile, long dayTime, double partialTick) {
        requireProfile(profile);
        if (!Double.isFinite(partialTick) || partialTick < 0 || partialTick > 1) {
            throw new IllegalArgumentException("Sky partial tick must be in [0, 1]");
        }
        double phase = meanPhase(profile, dayTime, partialTick);
        double initialAnomaly = meanAnomaly(-SOLAR_PERIHELION_LONGITUDE, profile.eccentricity());
        double eccentricAnomaly = solveKepler(initialAnomaly + TAU * phase, profile.eccentricity());
        double x = Math.cos(eccentricAnomaly) - profile.eccentricity();
        double y = Math.sqrt(1 - profile.eccentricity() * profile.eccentricity()) * Math.sin(eccentricAnomaly);
        double longitude = wrap(Math.atan2(y, x) + SOLAR_PERIHELION_LONGITUDE, TAU);
        if (longitude > TAU - 1.0e-12) { longitude = 0; }
        double tilt = Math.toRadians(profile.axialTiltDegrees());
        double declination = Math.asin(clamp(Math.sin(tilt) * Math.sin(longitude)));
        double rightAscension = Math.atan2(Math.cos(tilt) * Math.sin(longitude), Math.cos(longitude));
        double dayFraction = wrap(Math.floorMod(dayTime, TICKS_PER_DAY) + partialTick, TICKS_PER_DAY) / TICKS_PER_DAY;
        double siderealAngle = wrap(TAU * (dayFraction - 0.25 + phase), TAU);
        double hourAngle = siderealAngle - rightAscension;
        double latitude = Math.toRadians(profile.latitudeDegrees());
        double sinLatitude = Math.sin(latitude);
        double cosLatitude = Math.cos(latitude);
        double sinDeclination = Math.sin(declination);
        double cosDeclination = Math.cos(declination);
        SpaceVector direction = new SpaceVector(-cosDeclination * Math.sin(hourAngle),
                sinLatitude * sinDeclination + cosLatitude * cosDeclination * Math.cos(hourAngle),
                sinLatitude * cosDeclination * Math.cos(hourAngle) - cosLatitude * sinDeclination).normalized();
        return new SkySample(direction, Math.hypot(x, y), longitude / TAU, siderealAngle, declination,
                daylightHours(sinLatitude * sinDeclination, cosLatitude * cosDeclination),
                Math.asin(clamp(direction.y())));
    }

    /**
     * Returns an offset-adjusted profile whose solar longitude at this exact host tick equals phase * 2 pi.
     * Phase must be in [0, 1). It changes no host time, blocks, weather, extraction clocks or saved descriptors.
     */
    public static PlanetarySkyProfile withSeasonAtTime(PlanetarySkyProfile profile, long dayTime, double phase) {
        requireProfile(profile);
        if (!Double.isFinite(phase) || phase < 0 || phase >= 1) {
            throw new IllegalArgumentException("Season phase must be in [0, 1)");
        }
        double start = meanAnomaly(-SOLAR_PERIHELION_LONGITUDE, profile.eccentricity());
        double target = meanAnomaly(phase * TAU - SOLAR_PERIHELION_LONGITUDE, profile.eccentricity());
        double targetDays = wrap(target - start, TAU) / TAU * profile.yearDays();
        double currentDays = Math.floorMod(dayTime, yearTicks(profile)) / (double) TICKS_PER_DAY;
        return profile.withSeasonOffsetDays(wrap(targetDays - currentDays, profile.yearDays()));
    }

    private static double meanPhase(PlanetarySkyProfile profile, long dayTime, double partialTick) {
        double dayOfYear = (Math.floorMod(dayTime, yearTicks(profile)) + partialTick) / TICKS_PER_DAY;
        return wrap(dayOfYear + profile.seasonOffsetDays(), profile.yearDays()) / profile.yearDays();
    }

    private static long yearTicks(PlanetarySkyProfile profile) {
        return (long) profile.yearDays() * TICKS_PER_DAY;
    }

    private static double daylightHours(double center, double amplitude) {
        if (amplitude < 1.0e-12) {
            return Math.abs(center) < 1.0e-12 ? 12 : center > 0 ? 24 : 0;
        }
        return 24 / Math.PI * Math.acos(clamp(-center / amplitude));
    }

    private static double meanAnomaly(double trueAnomaly, double eccentricity) {
        double eccentricAnomaly = 2 * Math.atan2(Math.sqrt(1 - eccentricity) * Math.sin(trueAnomaly / 2),
                Math.sqrt(1 + eccentricity) * Math.cos(trueAnomaly / 2));
        return wrap(eccentricAnomaly - eccentricity * Math.sin(eccentricAnomaly), TAU);
    }

    private static double solveKepler(double meanAnomaly, double eccentricity) {
        double target = wrap(meanAnomaly, TAU);
        double lower = 0;
        double upper = TAU;
        double estimate = eccentricity < 0.8 ? target : Math.PI;
        // Safeguarded Newton normally needs three steps for Earth; the bracket handles extreme profiles.
        for (int iteration = 0; iteration < 48; iteration++) {
            double residual = estimate - eccentricity * Math.sin(estimate) - target;
            if (Math.abs(residual) < 1.0e-14) { return estimate; }
            if (residual < 0) { lower = estimate; }
            else { upper = estimate; }
            double next = estimate - residual / (1 - eccentricity * Math.cos(estimate));
            estimate = next > lower && next < upper ? next : (lower + upper) * 0.5;
        }
        return estimate;
    }

    private static double wrap(double value, double period) {
        double result = value % period;
        result = result < 0 ? result + period : result;
        return result >= period ? 0 : result;
    }

    private static double clamp(double value) { return Math.max(-1, Math.min(1, value)); }

    private static void requireProfile(PlanetarySkyProfile profile) {
        if (profile == null) { throw new IllegalArgumentException("A planetary sky profile is required"); }
    }
}
