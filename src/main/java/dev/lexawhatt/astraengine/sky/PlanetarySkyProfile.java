package dev.lexawhatt.astraengine.sky;

/**
 * Immutable visual sky configuration. A solar day is 24000 host ticks; latitude and axial tilt use degrees.
 * The Earth-oriented Kepler orbit has a one-AU semimajor axis. Offset is an elapsed mean-orbit date in
 * [0, yearDays), pollution is a visual background in [0, 1], and sun size is an Overworld display scale.
 * This record contains no clock, world reference, physical body mutation, or resource ownership.
 */
public record PlanetarySkyProfile(int yearDays, double latitudeDegrees, double axialTiltDegrees,
        double eccentricity, double seasonOffsetDays, double lightPollution, double sunSizeMultiplier) {
    public static final int MAX_YEAR_DAYS = 1_000_000;
    public static final PlanetarySkyProfile EARTH = new PlanetarySkyProfile(365, 45, 23.44, 0.0167, 0, 0.02, 3);
    public static final PlanetarySkyProfile DEFAULT = EARTH;

    /** Rejects non-finite and unsupported inputs before a profile can replace authoritative settings. */
    public PlanetarySkyProfile {
        if (yearDays < 4 || yearDays > MAX_YEAR_DAYS) {
            throw new IllegalArgumentException("Sky year must contain 4..1000000 solar days");
        }
        range(latitudeDegrees, -90, 90, "latitude");
        range(axialTiltDegrees, 0, 90, "axial tilt");
        range(eccentricity, 0, 0.95, "eccentricity");
        range(seasonOffsetDays, 0, yearDays, "season offset");
        if (seasonOffsetDays >= yearDays) {
            throw new IllegalArgumentException("Sky season offset must be smaller than the year");
        }
        range(lightPollution, 0, 1, "light pollution");
        range(sunSizeMultiplier, 1, 8, "sun display size");
    }

    /** Changes year length, preserving the offset's fraction of a year. Does not alter host time. */
    public PlanetarySkyProfile withYearDays(int days) {
        return new PlanetarySkyProfile(days, latitudeDegrees, axialTiltDegrees, eccentricity,
                seasonOffsetDays / yearDays * days, lightPollution, sunSizeMultiplier);
    }

    /** Returns a copy for a latitude in degrees north; negative values describe the southern hemisphere. */
    public PlanetarySkyProfile withLatitudeDegrees(double latitude) {
        return new PlanetarySkyProfile(yearDays, latitude, axialTiltDegrees, eccentricity,
                seasonOffsetDays, lightPollution, sunSizeMultiplier);
    }

    /** Returns a copy with a 0..90 degree obliquity. */
    public PlanetarySkyProfile withAxialTiltDegrees(double tilt) {
        return new PlanetarySkyProfile(yearDays, latitudeDegrees, tilt, eccentricity,
                seasonOffsetDays, lightPollution, sunSizeMultiplier);
    }

    /** Returns a copy with an elapsed mean-orbit offset in [0, yearDays). */
    public PlanetarySkyProfile withSeasonOffsetDays(double days) {
        return new PlanetarySkyProfile(yearDays, latitudeDegrees, axialTiltDegrees, eccentricity,
                days, lightPollution, sunSizeMultiplier);
    }

    /** Returns a copy with visual background pollution in [0, 1]; it does not modify block light. */
    public PlanetarySkyProfile withLightPollution(double pollution) {
        return new PlanetarySkyProfile(yearDays, latitudeDegrees, axialTiltDegrees, eccentricity,
                seasonOffsetDays, pollution, sunSizeMultiplier);
    }

    /** Returns a copy with a 1..8 apparent solar disk scale; astronomical descriptors remain unchanged. */
    public PlanetarySkyProfile withSunSizeMultiplier(double multiplier) {
        return new PlanetarySkyProfile(yearDays, latitudeDegrees, axialTiltDegrees, eccentricity,
                seasonOffsetDays, lightPollution, multiplier);
    }

    private static void range(double value, double minimum, double maximum, String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException("Invalid sky " + field + ": " + value);
        }
    }
}
