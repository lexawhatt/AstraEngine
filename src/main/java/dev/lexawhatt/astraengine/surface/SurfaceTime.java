package dev.lexawhatt.astraengine.surface;

/**
 * Immutable phase-preserving mapping from an existing occupied server tick clock to physical ephemeris seconds.
 * Owns no ticking or persistence. Changing a rate requires an explicit rebase; the compatibility rate is 0.05.
 */
public record SurfaceTime(long epochTick, double epochOrbitSeconds, double orbitSecondsPerTick) {
    public static final double COMPATIBILITY_RATE = 1.0 / 20;

    public SurfaceTime {
        if (epochTick < 0 || epochTick > 1_000_000_000_000L || !Double.isFinite(epochOrbitSeconds)
                || epochOrbitSeconds < 0 || !Double.isFinite(orbitSecondsPerTick)
                || orbitSecondsPerTick <= 0 || orbitSecondsPerTick > 1e6) {
            throw new IllegalArgumentException("Invalid orbital time mapping");
        }
    }

    /** Existing saved clock representation, without changing any orbital phase or period. */
    public static SurfaceTime compatibility() { return new SurfaceTime(0, 0, COMPATIBILITY_RATE); }

    /** Physical seconds at an occupied tick plus a finite render fraction in [0,1]. */
    public double orbitSeconds(long clockTicks, double partialTick) {
        if (clockTicks < 0 || clockTicks > 1_000_000_000_000L || !Double.isFinite(partialTick)
                || partialTick < 0 || partialTick > 1) {
            throw new IllegalArgumentException("Invalid surface clock tick or render fraction");
        }
        double result = epochOrbitSeconds + (clockTicks - epochTick + partialTick) * orbitSecondsPerTick;
        if (!Double.isFinite(result) || result < 0) {
            throw new IllegalArgumentException("Orbital mapping predates its nonnegative time domain");
        }
        return result;
    }

    /** Pins the current phase before selecting a new explicit rate; never resets the occupied server clock. */
    public SurfaceTime rebase(long clockTicks, double newOrbitSecondsPerTick) {
        return new SurfaceTime(clockTicks, orbitSeconds(clockTicks, 0), newOrbitSecondsPerTick);
    }
}
