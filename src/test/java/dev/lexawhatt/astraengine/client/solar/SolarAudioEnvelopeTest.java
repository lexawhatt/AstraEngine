package dev.lexawhatt.astraengine.client.solar;

import dev.lexawhatt.astraengine.cosmos.StellarEvolution;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot.Phase;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Auditory impact is an observed event, not a repeatable consequence of reading a nova snapshot. */
class SolarAudioEnvelopeTest {
    @Test
    void fullCycleHasOneImpactBoundedGainsAndDecayingRumble() {
        var model = new StellarEvolution();
        var envelope = new SolarAudioEnvelope();
        model.startDemo(10);
        int impacts = 0;
        boolean heardTension = false;
        boolean heardCollapse = false;
        float previousRumble = 1;
        for (int tick = 0; tick < 650; tick++) {
            var frame = envelope.observe(model.snapshot(), "overworld", true);
            if (frame.triggerImpact()) { impacts++; }
            heardTension |= frame.tension() > 0;
            heardCollapse |= frame.collapse() > 0;
            for (float gain : new float[]{frame.tension(), frame.collapse(), frame.impact(), frame.rumble()}) {
                assertTrue(Float.isFinite(gain) && gain >= 0 && gain <= 1);
            }
            assertTrue(frame.impact() + frame.rumble() <= 1.05f, "Layered peak budget remains bounded");
            if (model.snapshot().phase() == Phase.SUPERNOVA) {
                assertTrue(frame.rumble() <= previousRumble);
                previousRumble = frame.rumble();
            }
            assertFalse(envelope.observe(model.snapshot(), "overworld", true).triggerImpact());
            model.tick(true);
        }
        assertTrue(heardTension && heardCollapse);
        assertEquals(1, impacts);
        assertEquals(SolarAudioEnvelope.Frame.SILENT, envelope.observe(model.snapshot(), "overworld", true));
    }

    @Test
    void lateJoinReloadMuteAndContextReentryNeverReplayImpact() {
        var model = nova();
        var envelope = new SolarAudioEnvelope();
        assertFalse(envelope.observe(model.snapshot(), "overworld", true).triggerImpact());
        envelope.interrupt();
        assertFalse(envelope.observe(model.snapshot(), "overworld", true).triggerImpact());
        assertEquals(SolarAudioEnvelope.Frame.SILENT, envelope.observe(model.snapshot(), "overworld", false));
        assertFalse(envelope.observe(model.snapshot(), "overworld", true).triggerImpact());
        assertEquals(SolarAudioEnvelope.Frame.SILENT, envelope.observe(model.snapshot(), null, true));
        assertFalse(envelope.observe(model.snapshot(), "sol:4", true).triggerImpact());
        assertFalse(envelope.observe(model.snapshot(), "overworld", true).triggerImpact());
    }

    @Test
    void pauseAndResetSilenceVoicesWhileANewObservedCycleCanImpact() {
        var model = nova();
        var envelope = new SolarAudioEnvelope();
        envelope.observe(model.snapshot(), "overworld", true);
        model.pause();
        assertEquals(SolarAudioEnvelope.Frame.SILENT, envelope.observe(model.snapshot(), "overworld", true));
        model.resume();
        assertFalse(envelope.observe(model.snapshot(), "overworld", true).triggerImpact());
        model.reset();
        assertEquals(SolarAudioEnvelope.Frame.SILENT, envelope.observe(model.snapshot(), "overworld", true));
        model.startDemo(10);
        advanceTo(model, Phase.COLLAPSING);
        envelope.observe(model.snapshot(), "overworld", true);
        advanceTo(model, Phase.SUPERNOVA);
        assertTrue(envelope.observe(model.snapshot(), "overworld", true).triggerImpact());
        envelope.disconnect();
        assertFalse(envelope.observe(model.snapshot(), "overworld", true).triggerImpact());
    }

    @Test
    void crossingTheBoundaryMutedElsewhereOrTooLateDoesNotTriggerImpact() {
        for (int condition = 0; condition < 4; condition++) {
            var model = new StellarEvolution();
            var envelope = new SolarAudioEnvelope();
            model.startDemo(10);
            advanceTo(model, Phase.COLLAPSING);
            envelope.observe(model.snapshot(), "overworld", true);
            if (condition == 0) { envelope.observe(model.snapshot(), "overworld", false); }
            if (condition == 1) { envelope.interrupt(); }
            advanceTo(model, Phase.SUPERNOVA);
            if (condition == 2) { for (int i = 0; i < 11; i++) { model.tick(true); } }
            assertFalse(envelope.observe(model.snapshot(), condition == 3 ? "sol:9" : "overworld", true).triggerImpact());
        }
    }

    @Test
    void virtualDistanceGainUsesMetersAndRemainsFiniteAtLimits() {
        double au = SolarAudioEnvelope.ASTRONOMICAL_UNIT_METERS;
        assertEquals(1, SolarAudioEnvelope.distanceGain(0));
        assertEquals(1, SolarAudioEnvelope.distanceGain(au));
        assertTrue(SolarAudioEnvelope.distanceGain(10 * au) < SolarAudioEnvelope.distanceGain(2 * au));
        assertTrue(SolarAudioEnvelope.distanceGain(4096 * au) < 0.001);
        assertTrue(Float.isFinite(SolarAudioEnvelope.distanceGain(Double.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> SolarAudioEnvelope.distanceGain(-1));
        assertThrows(IllegalArgumentException.class, () -> SolarAudioEnvelope.distanceGain(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> SolarAudioEnvelope.distanceGain(Double.POSITIVE_INFINITY));
    }

    private static StellarEvolution nova() {
        var model = new StellarEvolution();
        model.startDemo(10);
        advanceTo(model, Phase.SUPERNOVA);
        return model;
    }

    private static void advanceTo(StellarEvolution model, Phase target) {
        for (int i = 0; i < 650 && model.snapshot().phase() != target; i++) { model.tick(true); }
        assertEquals(target, model.snapshot().phase());
    }
}
