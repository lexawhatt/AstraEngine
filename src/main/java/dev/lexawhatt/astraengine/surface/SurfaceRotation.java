package dev.lexawhatt.astraengine.surface;

/** Immutable spin phase at an occupied-clock epoch; radians and radians/tick are explicit, not a second clock. */
public record SurfaceRotation(long epochTick, double phaseAtEpochRadians, double radiansPerTick) {
    public SurfaceRotation {
        if (epochTick < 0 || epochTick > 1_000_000_000_000L || !Double.isFinite(phaseAtEpochRadians)
                || !Double.isFinite(radiansPerTick) || Math.abs(radiansPerTick) > Math.PI * 2) {
            throw new IllegalArgumentException("Invalid surface rotation mapping");
        }
    }

    /** Stable reduced phase for a finite occupied tick, optionally including its render fraction. */
    public double radiansAt(double clockTicks) {
        if (!Double.isFinite(clockTicks) || clockTicks < 0 || clockTicks > 1_000_000_000_001.0) {
            throw new IllegalArgumentException("Invalid surface rotation clock");
        }
        if (radiansPerTick == 0) { return Math.IEEEremainder(phaseAtEpochRadians, Math.PI * 2); }
        double elapsed = Math.IEEEremainder(clockTicks - epochTick, Math.PI * 2 / Math.abs(radiansPerTick));
        return Math.IEEEremainder(phaseAtEpochRadians + elapsed * radiansPerTick, Math.PI * 2);
    }

    /** Changes rate at the exact existing phase; signed rates permit retrograde rotation. */
    public SurfaceRotation rebase(long clockTicks, double newRadiansPerTick) {
        return new SurfaceRotation(clockTicks, radiansAt(clockTicks), newRadiansPerTick);
    }
}
