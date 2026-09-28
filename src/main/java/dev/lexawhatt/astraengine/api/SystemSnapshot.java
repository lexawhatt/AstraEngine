package dev.lexawhatt.astraengine.api;

import java.util.Objects;

/** Immutable celestial state. Time is counted in occupied-system server ticks, never wall time or frames. */
public record SystemSnapshot(SystemDescriptor descriptor, long remainingResource, long activeTicks,
                             int ticksUntilBurst, long burstCount, long revision) {
    public SystemSnapshot {
        Objects.requireNonNull(descriptor, "descriptor");
        if (remainingResource < 0 || remainingResource > descriptor.resourceCapacity()
                || activeTicks < 0 || ticksUntilBurst < 1 || ticksUntilBurst > 200
                || burstCount < 0 || revision < 0) {
            throw new IllegalArgumentException("Invalid celestial snapshot");
        }
    }

    /** Fraction of the initial resource still available, in [0, 1]. */
    public double resourceFraction() {
        return (double) remainingResource / descriptor.resourceCapacity();
    }

    /** Derives the phase from the authoritative remaining resource. */
    public StellarStage stage() {
        if (remainingResource == 0) {
            return StellarStage.BLACK_HOLE;
        }
        return remainingResource * 100 <= descriptor.resourceCapacity() * 35
                ? StellarStage.UNSTABLE : StellarStage.ACTIVE;
    }
}
