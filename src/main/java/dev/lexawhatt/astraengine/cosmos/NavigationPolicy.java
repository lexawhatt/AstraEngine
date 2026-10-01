package dev.lexawhatt.astraengine.cosmos;

/** Immutable server navigation cheats. Duration zero preserves normal timing; positive values are game seconds. */
public record NavigationPolicy(boolean freeNavigation, int travelSeconds) {
    public static final NavigationPolicy DEFAULT = new NavigationPolicy(false, 0);
    public static final int MAX_SECONDS = BodyApproach.MAX_TICKS / 20;

    /** Rejects durations outside zero through one hour, before converting seconds to ticks. */
    public NavigationPolicy {
        if (travelSeconds < 0 || travelSeconds > MAX_SECONDS) {
            throw new IllegalArgumentException("Navigation duration must be between 0 and 3600 seconds");
        }
    }

    /** Explicit duration in host ticks, or zero for the ordinary route planner. */
    public int overrideTicks() { return travelSeconds * 20; }

    /** Duration of an interstellar jump, captured when that jump is accepted. */
    public int jumpTicks() { return travelSeconds == 0 ? 80 : overrideTicks(); }

    /** Visits remain authoritative; enabling the cheat does not modify the exploration catalog. */
    public boolean permitsJump(boolean visited) { return freeNavigation || visited; }
}
