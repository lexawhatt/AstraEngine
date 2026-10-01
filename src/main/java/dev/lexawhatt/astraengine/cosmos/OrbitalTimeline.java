package dev.lexawhatt.astraengine.cosmos;

import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import dev.lexawhatt.astraengine.surface.EarthEphemeris;

/**
 * Immutable prediction of orbital sampling time from elapsed host ticks. A route owns this value, not a
 * mutable clock or world. The server must abandon/replan a route if its calendar/settings change externally.
 */
public final class OrbitalTimeline {
    private final double initialSeconds;
    private final PlanetarySkyProfile calendar;
    private final long initialDayTime;
    private final boolean advancing;

    private OrbitalTimeline(double initialSeconds, PlanetarySkyProfile calendar, long initialDayTime, boolean advancing) {
        this.initialSeconds = initialSeconds;
        this.calendar = calendar;
        this.initialDayTime = initialDayTime;
        this.advancing = advancing;
    }

    /** Existing observation-clock policy: one orbital second per twenty occupied host ticks. */
    public static OrbitalTimeline elapsed(double initialSeconds) {
        if (!Double.isFinite(initialSeconds)) { throw new IllegalArgumentException("Orbital time must be finite"); }
        return new OrbitalTimeline(initialSeconds, null, 0, true);
    }

    /** Exact host-calendar prediction, including a frozen daylight rule and custom year/season policy. */
    public static OrbitalTimeline calendar(PlanetarySkyProfile profile, long dayTime, boolean advancing) {
        return new OrbitalTimeline(EarthEphemeris.sample(profile, dayTime, 0).orbitalSeconds(), profile, dayTime, advancing);
    }

    /**
     * Resolves finite elapsed ticks in [0,72000], including fractional planning samples. No host time advances.
     * An overflowing future host date is explicitly unsupported rather than wrapping into another saved date.
     */
    public double secondsAt(double elapsedTicks) {
        if (!Double.isFinite(elapsedTicks) || elapsedTicks < 0 || elapsedTicks > BodyApproach.MAX_TICKS) {
            throw new IllegalArgumentException("Orbital prediction requires bounded elapsed host ticks");
        }
        if (calendar == null) { return initialSeconds + elapsedTicks / 20; }
        if (!advancing) { return initialSeconds; }
        long whole = (long) Math.floor(elapsedTicks);
        try {
            return EarthEphemeris.sample(calendar, Math.addExact(initialDayTime, whole), elapsedTicks - whole).orbitalSeconds();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Predicted host calendar date overflows", overflow);
        }
    }
}
