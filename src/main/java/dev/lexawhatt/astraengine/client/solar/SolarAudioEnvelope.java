package dev.lexawhatt.astraengine.client.solar;

import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot.Phase;

/**
 * Pure, connection-owned cinematic sound gating. Consumes server snapshots without advancing time.
 * An interrupt discards observation continuity, so rejoining/reloading cannot replay a nova impact.
 */
public final class SolarAudioEnvelope {
    public static final double ASTRONOMICAL_UNIT_METERS = 149_597_870_700.0;
    private StellarEvolutionSnapshot previous;
    private String previousContext;
    private boolean previouslyListening;
    private long impactedCycle = -1;

    /** Normalized voice gains before the user's and virtual-distance gains. */
    public record Frame(float tension, float collapse, float impact, float rumble, boolean triggerImpact) {
        public static final Frame SILENT = new Frame(0, 0, 0, 0, false);
    }

    /**
     * Context identifies the observed world/navigation location, null when not observing Sol.
     * Listening is false while paused, muted, outside that context, or disconnected. Impact requires
     * a fresh observed collapse boundary in the same cycle/context; duplicates and late joins cannot fire it.
     */
    public Frame observe(StellarEvolutionSnapshot snapshot, String context, boolean listening) {
        if (snapshot == null) { interrupt(); return Frame.SILENT; }
        if (previous != null && snapshot.revision() < previous.revision()) { return Frame.SILENT; }
        boolean audible = listening && context != null && snapshot.running();
        boolean continuous = audible && previouslyListening && context.equals(previousContext)
                && previous != null && previous.running() && previous.cycle() == snapshot.cycle();
        boolean impact = continuous && previous.phase() == Phase.COLLAPSING && snapshot.phase() == Phase.SUPERNOVA
                && snapshot.phaseTicks() <= 10 && impactedCycle != snapshot.cycle();
        if (impact) { impactedCycle = snapshot.cycle(); }
        previous = snapshot;
        previousContext = context;
        previouslyListening = audible;
        if (!audible) { return Frame.SILENT; }
        float depletion = 1.0f - (float) snapshot.remaining() / StellarEvolutionSnapshot.CAPACITY;
        return switch (snapshot.phase()) {
            case STABLE, REMNANT -> Frame.SILENT;
            case DISTENDED, CRITICAL -> new Frame(0.05f + 0.22f * smooth(0.35f, 1, depletion), 0, 0, 0, false);
            case COLLAPSING -> new Frame(0, 0.14f + 0.22f * smooth(0, StellarEvolutionSnapshot.COLLAPSE_TICKS,
                    snapshot.phaseTicks()), 0, 0, false);
            case SUPERNOVA -> {
                float seconds = snapshot.phaseTicks() / 20.0f;
                float rumble = 0.32f * (float) Math.exp(-seconds / 5.0f)
                        * (1 - smooth(12, 16, seconds));
                float impactGain = 0.72f * (float) Math.exp(-seconds / 2.6f);
                yield new Frame(0, 0, impactGain, rumble, impact);
            }
        };
    }

    /** Leaves a baseline gap without forgetting which cycle's impact has already been consumed. */
    public void interrupt() {
        previous = null;
        previousContext = null;
        previouslyListening = false;
    }

    /** A connection change begins a new observer lifetime, including its impact deduplication history. */
    public void disconnect() {
        interrupt();
        impactedCycle = -1;
    }

    /**
     * Artistic cinematic falloff, not propagation through vacuum. 1 AU has gain 1;
     * near-Sun views are bounded at 1, very distant views fade continuously toward silence.
     */
    public static float distanceGain(double meters) {
        if (!Double.isFinite(meters) || meters < 0) {
            throw new IllegalArgumentException("Solar audio distance must be finite nonnegative meters");
        }
        double au = meters / ASTRONOMICAL_UNIT_METERS;
        return (float) Math.clamp(2.0 / (1.0 + au), 0, 1);
    }

    private static float smooth(float start, float end, float value) {
        float t = Math.clamp((value - start) / (end - start), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
