package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectionFlightStepTest {
    private final EarthChart chart = new EarthChart(CubeFace.POSITIVE_X, 4, 3);

    @Test void nearbyUnrelatedSeamsDoNotThrottleAscentOrMovementAway() {
        var atFaceEdge = new SpaceVector(chart.radiusMeters() - 1, 0, 0);
        var ascent = new SpaceVector(0, 1000, 0);
        assertEquals(ascent, InspectionFlightStep.seam(chart, atFaceEdge, ascent));
        var away = new SpaceVector(-128, 0, 0);
        assertEquals(away, InspectionFlightStep.seam(chart, atFaceEdge, away));
        var atLowerBand = new SpaceVector(0, chart.minY() + 1, 0);
        assertEquals(ascent, InspectionFlightStep.seam(chart, atLowerBand, ascent));
    }

    @Test void approachedBoundaryKeepsObservationEnvelopeWithoutChangingHeading() {
        var feet = new SpaceVector(0, chart.minY() + chart.height() - 12, 0);
        var movement = new SpaceVector(0, 2560, 0);
        assertEquals(new SpaceVector(0, 8, 0), InspectionFlightStep.seam(chart, feet, movement));
        var farther = new SpaceVector(0, chart.minY() + chart.height() - 700, 0);
        assertEquals(new SpaceVector(0, 668, 0), InspectionFlightStep.seam(chart, farther, movement));
        assertEquals(SpaceVector.ZERO, InspectionFlightStep.seam(chart, feet, SpaceVector.ZERO));
    }

    @Test void horizontalChunkDemandIsBoundedWhileVerticalAirCanBeFast() {
        var vertical = new SpaceVector(0, 2560, 0);
        assertEquals(vertical, InspectionFlightStep.corridor(vertical));
        var diagonal = new SpaceVector(300, 400, 400);
        var limited = InspectionFlightStep.corridor(diagonal);
        assertEquals(128, Math.hypot(limited.x(), limited.z()), 1e-9);
        assertTrue(limited.normalized().dot(diagonal.normalized()) > .999999999);
    }
}
