package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceTimeTest {
    @Test
    void compatibilityClockDoesNotAccelerateExistingOrbitsAndRebasePreservesEveryPhase() {
        long tick = 893_127;
        SurfaceTime compatibility = SurfaceTime.compatibility();
        double now = compatibility.orbitSeconds(tick, 0);
        assertEquals(tick / 20.0, now, 1e-10);
        var sol = CosmosGenerator.sol();
        var earth = sol.bodies().stream().filter(body -> body.id().equals("earth")).findFirst().orElseThrow();
        SurfaceTime explicitlyFaster = compatibility.rebase(tick, earth.orbitalPeriodSeconds() / (365 * 24000.0));
        assertEquals(now, explicitlyFaster.orbitSeconds(tick, 0));
        for (var body : sol.bodies()) {
            assertEquals(sol.positionAt(body, now), sol.positionAt(body, explicitlyFaster.orbitSeconds(tick, 0)));
        }
        assertEquals(earth.orbitalPeriodSeconds(),
                explicitlyFaster.orbitSeconds(tick + 365 * 24000L, 0) - now, 1e-8);
        assertEquals(.025, compatibility.orbitSeconds(tick, .5) - now, 1e-10);
        assertThrows(IllegalArgumentException.class, () -> compatibility.rebase(tick, 0));
        assertThrows(IllegalArgumentException.class, () -> compatibility.orbitSeconds(tick, 1.01));
    }

    @Test
    void earthMeanSolarDayAccountsForPhysicalOrbitalIncrementWithoutAdoptingOverworldCalendar() {
        SurfaceDefinition earth = SurfaceDefinition.byBody("earth");
        double period = CosmosGenerator.sol().bodies().stream().filter(body -> body.id().equals("earth"))
                .findFirst().orElseThrow().orbitalPeriodSeconds();
        double afterDay = earth.spinRadians(1200, 24000);
        assertEquals(1200 / period * 2 * Math.PI, afterDay, 1e-12);
        assertTrue(Math.abs(afterDay - 2 * Math.PI / 365) > .01);
        assertEquals(Math.PI, Math.abs(earth.spinRadians(0, 12000)), 1e-12);
    }

    @Test
    void rotationRateChangesPreservePhaseAndRemainStableAtLongSessionTimes() {
        SurfaceRotation rotation = new SurfaceRotation(0, .7, Math.PI * 2 / 24000);
        SurfaceRotation changed = rotation.rebase(981337, -.003);
        assertEquals(rotation.radiansAt(981337), changed.radiansAt(981337));
        double a = rotation.radiansAt(999_999_984_000L);
        double b = rotation.radiansAt(999_999_984_000L - 24000);
        assertEquals(a, b, 1e-10);
        assertThrows(IllegalArgumentException.class, () -> rotation.radiansAt(Double.NaN));
    }
}
