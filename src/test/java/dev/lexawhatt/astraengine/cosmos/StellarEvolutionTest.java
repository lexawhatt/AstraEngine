package dev.lexawhatt.astraengine.cosmos;

import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot.Phase;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests occupied-time ownership, exact accounting, phase boundaries and repeatable diagnostic cycles. */
class StellarEvolutionTest {
    @Test
    void pristineStateDoesNotDrainAndDurationsAreBounded() {
        StellarEvolution model = new StellarEvolution();
        StellarEvolutionSnapshot pristine = model.snapshot();
        assertEquals(StellarEvolutionSnapshot.CAPACITY, pristine.remaining());
        assertFalse(model.resume()); assertFalse(model.pause()); assertFalse(model.tick(true));
        assertThrows(IllegalArgumentException.class, () -> model.startDemo(9));
        assertThrows(IllegalArgumentException.class, () -> model.startDemo(3601));
        assertEquals(pristine, model.snapshot());
    }

    @Test
    void gradualDrainHasExactPhaseBoundariesAndTerminalRemnant() {
        StellarEvolution model = new StellarEvolution();
        model.startDemo(10);
        advance(model, 69);
        assertEquals(Phase.STABLE, model.snapshot().phase());
        advance(model, 1);
        assertEquals(650_000, model.snapshot().remaining());
        assertEquals(Phase.DISTENDED, model.snapshot().phase()); assertEquals(0, model.snapshot().phaseTicks());
        advance(model, 90);
        assertEquals(200_000, model.snapshot().remaining());
        assertEquals(Phase.CRITICAL, model.snapshot().phase()); assertEquals(0, model.snapshot().phaseTicks());
        advance(model, 40);
        assertEquals(0, model.snapshot().remaining()); assertEquals(1_000_000, model.snapshot().extracted());
        assertEquals(Phase.COLLAPSING, model.snapshot().phase()); assertEquals(0, model.snapshot().phaseTicks());
        advance(model, 79);
        assertEquals(Phase.COLLAPSING, model.snapshot().phase()); assertEquals(79, model.snapshot().phaseTicks());
        advance(model, 1);
        assertEquals(Phase.SUPERNOVA, model.snapshot().phase()); assertEquals(0, model.snapshot().phaseTicks());
        advance(model, 319);
        assertEquals(Phase.SUPERNOVA, model.snapshot().phase()); assertEquals(319, model.snapshot().phaseTicks());
        advance(model, 1);
        assertEquals(Phase.REMNANT, model.snapshot().phase()); assertEquals(600, model.snapshot().activeTicks());
        StellarEvolutionSnapshot remnant = model.snapshot();
        assertFalse(remnant.running()); assertFalse(model.resume()); assertFalse(model.tick(true));
        assertEquals(remnant, model.snapshot());
    }

    @Test
    void pauseAndAbsentObserversFreezeEveryClockIncludingExplosion() {
        StellarEvolution model = new StellarEvolution();
        model.startDemo(10);
        advance(model, 290);
        assertEquals(Phase.SUPERNOVA, model.snapshot().phase());
        StellarEvolutionSnapshot before = model.snapshot();
        for (int i = 0; i < 100; i++) { assertFalse(model.tick(false)); }
        assertEquals(before, model.snapshot());
        assertTrue(model.pause());
        StellarEvolutionSnapshot paused = model.snapshot();
        assertEquals(before.activeTicks(), paused.activeTicks());
        assertEquals(before.phaseTicks(), paused.phaseTicks());
        assertEquals(before.revision() + 1, paused.revision());
        for (int i = 0; i < 100; i++) { assertFalse(model.tick(true)); }
        assertEquals(paused, model.snapshot());
        StellarEvolution restored = StellarEvolution.restore(paused);
        assertTrue(restored.resume()); advance(restored, 1);
        assertEquals(paused.phaseTicks() + 1, restored.snapshot().phaseTicks());
        assertEquals(paused.activeTicks() + 1, restored.snapshot().activeTicks());
        assertEquals(paused.extracted(), restored.snapshot().extracted());
    }

    @Test
    void fractionalDrainConservesEnergyAndNewCyclesKeepMonotonicIdentities() {
        StellarEvolution model = new StellarEvolution();
        model.startDemo(11);
        for (int elapsed = 1; elapsed <= 220; elapsed++) {
            assertTrue(model.tick(true));
            assertEquals(1_000_000L * elapsed / 220, model.snapshot().extracted());
            assertEquals(1_000_000, model.snapshot().remaining() + model.snapshot().extracted());
        }
        StellarEvolutionSnapshot prior = model.snapshot();
        model.reset();
        assertEquals(prior.revision() + 1, model.snapshot().revision());
        assertEquals(prior.cycle() + 1, model.snapshot().cycle());
        assertEquals(1_000_000, model.snapshot().remaining()); assertFalse(model.snapshot().running());
        assertEquals(0, model.snapshot().activeTicks()); assertEquals(0, model.snapshot().drainTicks());
        long resetRevision = model.snapshot().revision(); long resetCycle = model.snapshot().cycle();
        model.startDemo(3600);
        assertEquals(resetRevision + 1, model.snapshot().revision());
        assertEquals(resetCycle + 1, model.snapshot().cycle()); assertEquals(72_000, model.snapshot().drainTicks());
    }

    @Test
    void snapshotsRejectContradictoryClocksAccountingAndTerminalState() {
        assertThrows(IllegalArgumentException.class, () -> new StellarEvolutionSnapshot(650_001, 350_000,
                70, Phase.DISTENDED, 0, 200, 70, true, 71, 1));
        assertThrows(IllegalArgumentException.class, () -> new StellarEvolutionSnapshot(650_000, 350_000,
                70, Phase.DISTENDED, 1, 200, 70, true, 71, 1));
        assertThrows(IllegalArgumentException.class, () -> new StellarEvolutionSnapshot(650_000, 350_000,
                70, Phase.STABLE, 70, 200, 70, true, 71, 1));
        assertThrows(IllegalArgumentException.class, () -> new StellarEvolutionSnapshot(0, 1_000_000,
                600, Phase.REMNANT, 0, 200, 200, true, 601, 1));
        assertThrows(IllegalArgumentException.class, () -> new StellarEvolutionSnapshot(1_000_000, 0,
                0, Phase.STABLE, 0, 0, 0, true, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new StellarEvolutionSnapshot(650_000, 350_000,
                70, Phase.DISTENDED, 0, 200, 70, true, 0, 1));
    }

    private static void advance(StellarEvolution model, int ticks) {
        for (int i = 0; i < ticks; i++) {
            StellarEvolutionSnapshot before = model.snapshot();
            assertTrue(model.tick(true));
            assertEquals(before.revision() + 1, model.snapshot().revision());
            assertEquals(StellarEvolutionSnapshot.CAPACITY, model.snapshot().remaining() + model.snapshot().extracted());
        }
    }
}
