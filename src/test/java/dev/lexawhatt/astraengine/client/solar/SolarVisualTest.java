package dev.lexawhatt.astraengine.client.solar;

import dev.lexawhatt.astraengine.cosmos.StellarEvolution;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot.Phase;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Presentation envelopes must remain finite and produce one bounded flash through an entire event. */
class SolarVisualTest {
    @Test
    void wholeCycleProducesFiniteParametersAndOneFlash() {
        StellarEvolution evolution = new StellarEvolution();
        evolution.startDemo(10);
        boolean flashStarted = false;
        boolean flashEnded = false;
        float previousFlash = 0;
        for (int tick = 0; tick <= 600; tick++) {
            StellarEvolutionSnapshot state = evolution.snapshot();
            SolarVisual visual = SolarVisual.from(1 - (float) state.remaining() / StellarEvolutionSnapshot.CAPACITY,
                    state.phase(), state.phaseTicks());
            assertTrue(Float.isFinite(visual.radiusScale()));
            assertTrue(visual.radiusScale() >= 0.01f && visual.radiusScale() <= 10);
            if (visual.flash() > previousFlash) { assertFalse(flashEnded); flashStarted = true; }
            if (visual.flash() < previousFlash) { flashEnded = true; }
            previousFlash = visual.flash();
            evolution.tick(true);
        }
        assertTrue(flashStarted && flashEnded);
        assertEquals(0, SolarVisual.from(1, Phase.REMNANT, 0).flash());
        assertEquals(SolarVisual.HEALTHY, SolarVisual.from(0, Phase.STABLE, 0));
    }

    @Test
    void depletedSurfaceDimsAndResetRestoresPhysicalRadius() {
        SolarVisual before = SolarVisual.from(0.35f, Phase.DISTENDED, 0);
        SolarVisual critical = SolarVisual.from(0.9f, Phase.CRITICAL, 0);
        assertTrue(critical.luminosity() < before.luminosity());
        assertTrue(critical.radiusScale() > before.radiusScale());
        SolarVisual end = SolarVisual.from(1, Phase.SUPERNOVA, 320);
        SolarVisual remnant = SolarVisual.from(1, Phase.REMNANT, 0);
        assertEquals(end.remnant(), remnant.remnant());
        assertEquals(end.luminosity(), remnant.luminosity(), 0.00001f);
        assertThrows(IllegalArgumentException.class, () -> SolarVisual.from(Float.NaN, Phase.STABLE, 0));
        assertThrows(IllegalArgumentException.class, () -> SolarVisual.from(0, Phase.STABLE, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> SolarVisual.from(0, Phase.STABLE, -1));
    }
}
