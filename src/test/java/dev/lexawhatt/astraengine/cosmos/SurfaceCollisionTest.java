package dev.lexawhatt.astraengine.cosmos;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceCollisionTest {
    @Test
    void landingExemptionRetainsParentGraphAndOtherBodies() {
        CosmosSystem system = CosmosGenerator.sol();
        double seconds = 134.5;
        CelestialBody moon = system.bodies().stream().filter(body -> body.id().equals("moon")).findFirst().orElseThrow();
        SpaceVector center = system.positionAt(moon, seconds);
        SpaceVector above = center.add(new SpaceVector(0, FlightDynamics.safeRadius(moon) + 100, 0));
        SpaceVector near = center.add(new SpaceVector(0, moon.radiusMeters() + 2, 0));
        assertFalse(FlightDynamics.clearSegment(above, near, system.bodies(), seconds, seconds));
        assertTrue(FlightDynamics.clearSurfaceSegment(above, near, system.bodies(), seconds, seconds, "moon"));
        assertFalse(FlightDynamics.clearSurfaceSegment(above, near, system.bodies(), seconds, seconds, "earth"));
        SpaceVector earth = system.positionAt("earth", seconds);
        assertFalse(FlightDynamics.clearSurfaceSegment(above, earth, system.bodies(), seconds, seconds, "moon"));
        assertThrows(IllegalArgumentException.class, () -> FlightDynamics.clearSurfaceSegment(
                above, near, system.bodies(), seconds, seconds, "missing"));
    }
}
