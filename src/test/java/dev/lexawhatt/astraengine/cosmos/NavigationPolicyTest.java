package dev.lexawhatt.astraengine.cosmos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavigationPolicyTest {
    @Test
    void defaultsKeepVisitsAndExistingTimingWhileOverridesStayBounded() {
        assertFalse(NavigationPolicy.DEFAULT.permitsJump(false));
        assertTrue(NavigationPolicy.DEFAULT.permitsJump(true));
        assertEquals(0, NavigationPolicy.DEFAULT.overrideTicks());
        assertEquals(80, NavigationPolicy.DEFAULT.jumpTicks());
        assertEquals(20, new NavigationPolicy(true, 1).jumpTicks());
        assertTrue(new NavigationPolicy(true, 1).permitsJump(false));
        assertEquals(72_000, new NavigationPolicy(false, 3600).overrideTicks());
        for (int invalid : new int[] {-1, 3601, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new NavigationPolicy(false, invalid));
        }
    }

    @Test
    void oneSecondRoutesFollowMovingTargetsAndRetainActualTickCollisionChecks() {
        var sol = CosmosGenerator.sol();
        var earth = sol.bodies().stream().filter(body -> body.id().equals("earth")).findFirst().orElseThrow();
        var timeline = OrbitalTimeline.elapsed(1234);
        var source = FlightDynamics.observation(sol, earth, timeline.secondsAt(0));
        for (var target : sol.bodies()) {
            var route = BodyApproach.plan(sol, target, new FlightDynamics.State(source.position(), SpaceVector.ZERO),
                    source.orientation(), timeline, 20).orElseThrow(() -> new AssertionError(target.id()));
            assertEquals(20, route.durationTicks());
            assertEquals(source.position(), route.frame(0).state().position());
            assertEquals(FlightDynamics.observation(sol, target, timeline.secondsAt(20)).position(),
                    route.frame(20).state().position());
            assertEquals(SpaceVector.ZERO, route.frame(20).state().velocity());
            for (int tick = 1; tick <= 20; tick++) {
                assertTrue(FlightDynamics.clearSegment(route.frame(tick - 1).state().position(),
                        route.frame(tick).state().position(), sol.bodies(), timeline.secondsAt(tick - 1),
                        timeline.secondsAt(tick)), target.id());
            }
        }
        for (int invalid : new int[] {-1, 1, 19, 72_001, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> BodyApproach.plan(sol, earth,
                    new FlightDynamics.State(source.position(), SpaceVector.ZERO), source.orientation(), timeline, invalid));
        }
        assertTrue(BodyApproach.plan(sol, earth, new FlightDynamics.State(sol.positionAt(earth, 1234), SpaceVector.ZERO),
                source.orientation(), timeline, 20).isEmpty(), "The cheat cannot escape invalid initial geometry");
    }
}
