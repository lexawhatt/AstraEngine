package dev.lexawhatt.astraengine.client.solar;

import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot.Phase;

/** Immutable presentation parameters, derived from server state; never an independent evolution clock. */
public record SolarVisual(float depletion, float collapse, float explosionSeconds, float remnant,
        float luminosity, float flash, float radiusScale) {
    public static final SolarVisual HEALTHY = new SolarVisual(0, 0, -1, 0, 1, 0, 1);

    public SolarVisual {
        bounded(depletion, 0, 1); bounded(collapse, 0, 1); bounded(explosionSeconds, -1, 16);
        bounded(remnant, 0, 1); bounded(luminosity, 0, 2); bounded(flash, 0, 1); bounded(radiusScale, 0.01f, 10);
    }

    /** Evaluates continuous artistic radiance and envelope parameters from an interpolated phase age in ticks. */
    public static SolarVisual from(float depletion, Phase phase, float phaseTicks) {
        if (phase == null || !Float.isFinite(phaseTicks) || phaseTicks < 0) {
            throw new IllegalArgumentException("Solar presentation needs a phase and finite nonnegative age");
        }
        bounded(depletion, 0, 1);
        float envelope = 1 + 9 * smooth(0.15f, 1, depletion);
        float light = 1 - 0.88f * smooth(0, 1, depletion);
        if (phase == Phase.COLLAPSING) {
            float progress = Math.clamp(phaseTicks / 80, 0, 1);
            float contraction = smooth(0, 1, progress);
            return new SolarVisual(depletion, progress, -1, 0, 0.12f * (1 - contraction) + 0.008f * contraction,
                    0, envelope * (1 - contraction) + 0.03f * contraction);
        }
        if (phase == Phase.SUPERNOVA) {
            float age = Math.clamp(phaseTicks / 20, 0, 16);
            float flash = smooth(0, 0.2f, age) * (float) Math.exp(-Math.max(0, age - 0.2f) * 1.4);
            return new SolarVisual(depletion, 1, age, smooth(12, 16, age), 0.035f + flash * 1.5f, flash, 0.12f);
        }
        if (phase == Phase.REMNANT) { return new SolarVisual(depletion, 1, 16, 1, 0.035f, 0, 0.12f); }
        return new SolarVisual(depletion, 0, -1, 0, light, 0, envelope);
    }

    private static float smooth(float low, float high, float value) {
        float t = Math.clamp((value - low) / (high - low), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static void bounded(float value, float low, float high) {
        if (!Float.isFinite(value) || value < low || value > high) {
            throw new IllegalArgumentException("Solar visual parameter is outside its finite bounds");
        }
    }
}
