package dev.lexawhatt.astraengine.cosmos;

/**
 * Immutable server-authored diagnostic stellar evolution. Energy uses bounded engine units, not joules;
 * all clocks count occupied, unpaused ticks. This fictional supernova sequence is not a solar forecast.
 */
public record StellarEvolutionSnapshot(long remaining, long extracted, long activeTicks, Phase phase,
        int phaseTicks, int drainTicks, int drainElapsed, boolean running, long revision, long cycle) {
    public static final long CAPACITY = 1_000_000;
    public static final int COLLAPSE_TICKS = 80;
    public static final int NOVA_TICKS = 320;

    /** Ordered phases of the controlled diagnostic cycle; REMNANT persists until an explicit reset/demo. */
    public enum Phase { STABLE, DISTENDED, CRITICAL, COLLAPSING, SUPERNOVA, REMNANT }

    public StellarEvolutionSnapshot {
        if (remaining < 0 || remaining > CAPACITY || extracted < 0 || extracted > CAPACITY
                || remaining + extracted != CAPACITY || phase == null || phaseTicks < 0
                || drainTicks < 0 || drainTicks > 72_000 || (drainTicks > 0 && drainTicks < 200)
                || drainElapsed < 0 || drainElapsed > drainTicks || activeTicks < 0
                || activeTicks > drainTicks + COLLAPSE_TICKS + NOVA_TICKS
                || cycle < 0 || revision < 0 || cycle > revision || activeTicks > revision - cycle) {
            throw new IllegalArgumentException("Invalid stellar evolution bounds or energy accounting");
        }
        if (drainTicks == 0) {
            if (remaining != CAPACITY || extracted != 0 || drainElapsed != 0 || activeTicks != 0
                    || phase != Phase.STABLE || phaseTicks != 0 || running) {
                throw new IllegalArgumentException("Unconfigured stellar evolution must remain pristine and paused");
            }
        } else {
            if (cycle == 0 || extracted != CAPACITY * drainElapsed / drainTicks) {
                throw new IllegalArgumentException("Stellar depletion disagrees with its configured diagnostic schedule");
            }
            if (drainElapsed < drainTicks) {
                Phase expected = remaining > CAPACITY * 65 / 100 ? Phase.STABLE
                        : remaining > CAPACITY / 5 ? Phase.DISTENDED : Phase.CRITICAL;
                int enteredAt = expected == Phase.STABLE ? 0 : expected == Phase.DISTENDED
                        ? ceilingTicks(CAPACITY * 35 / 100, drainTicks) : ceilingTicks(CAPACITY * 80 / 100, drainTicks);
                if (phase != expected || activeTicks != drainElapsed || phaseTicks != drainElapsed - enteredAt) {
                    throw new IllegalArgumentException("Stellar phase or clock disagrees with remaining energy");
                }
            } else {
                boolean valid = switch (phase) {
                    case COLLAPSING -> phaseTicks < COLLAPSE_TICKS && activeTicks == drainTicks + phaseTicks;
                    case SUPERNOVA -> phaseTicks < NOVA_TICKS && activeTicks == drainTicks + COLLAPSE_TICKS + phaseTicks;
                    case REMNANT -> phaseTicks == 0 && !running
                            && activeTicks == drainTicks + COLLAPSE_TICKS + NOVA_TICKS;
                    default -> false;
                };
                if (!valid) { throw new IllegalArgumentException("Invalid post-depletion stellar phase clock"); }
            }
        }
    }

    private static int ceilingTicks(long extractedThreshold, int ticks) {
        return (int) ((extractedThreshold * ticks + CAPACITY - 1) / CAPACITY);
    }
}
