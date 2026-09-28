package dev.lexawhatt.astraengine.client.flight;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frame cadence must not set the camera's smoothing speed or impose the walking pitch limit. */
class FlightCameraTest {
    @Test
    void batchedGuidanceCannotForceFastCatchUpAndSettlesBeforeManualHandoff() {
        FlightCamera camera = new FlightCamera();
        FlightOrientation previous = new FlightOrientation(0.11766001736184363, -0.5663283603724277,
                0.02009569499301429, 0.8154903258745845);
        FlightOrientation target = new FlightOrientation(0.12522591129634147, -0.6759820480431245,
                0.007327716502827731, 0.7261632367686194);
        camera.reset(previous);
        double seconds = 0.061561628;
        camera.follow(target, seconds);
        assertTrue(degrees(previous, camera.orientation()) <= 180 * seconds + 1e-8);
        assertTrue(!target.equals(camera.orientation()), "A delayed snapshot must not relocate the view");
        for (int frame = 0; frame < 100; frame++) {
            previous = camera.orientation();
            seconds = frame % 2 == 0 ? 1.0 / 120 : 0.075;
            camera.follow(target, seconds);
            assertTrue(degrees(previous, camera.orientation()) <= 180 * seconds + 1e-6);
        }
        assertEquals(target, camera.orientation());
        camera.update(0, 0, 0, 0.05, 0.8f);
        assertEquals(target, camera.orientation());
        assertEquals(target, camera.target());
    }

    @Test
    void guidedAngularLimitUsesTimeAcrossFrameCadences() {
        FlightCamera coarse = new FlightCamera(), fine = new FlightCamera();
        FlightOrientation target = FlightOrientation.fromAngles(180, 0, 0);
        coarse.follow(target, 0);
        assertEquals(FlightOrientation.IDENTITY, coarse.orientation());
        for (int frame = 0; frame < 5; frame++) { coarse.follow(target, 0.1); }
        for (int frame = 0; frame < 60; frame++) { fine.follow(target, 1.0 / 120); }
        assertEquals(90, degrees(FlightOrientation.IDENTITY, coarse.orientation()), 1e-8);
        assertTrue(coarse.orientation().forward().dot(fine.orientation().forward()) > 1 - 1e-10);
        assertThrows(IllegalArgumentException.class, () -> fine.follow(target, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> fine.follow(target, 0.11));
    }

    private static double degrees(FlightOrientation first, FlightOrientation second) {
        double dot = Math.abs(first.x() * second.x() + first.y() * second.y()
                + first.z() * second.z() + first.w() * second.w());
        return Math.toDegrees(2 * Math.acos(Math.clamp(dot, 0, 1)));
    }

    @Test
    void smoothedIdlePoseSettlesExactlyAndKeepsItsSavedBits() {
        FlightCamera camera = new FlightCamera();
        camera.update(132, 128, 49, 0, 0.8f);
        FlightOrientation target = camera.target();
        for (int i = 0; i < 300; i++) { camera.update(0, 0, 0, 1.0 / 60, 0.8f); }
        assertEquals(target, camera.orientation());
        for (int i = 0; i < 1000; i++) {
            camera.update(0, 0, 0, 0.05, 0.95f);
            assertEquals(target, camera.target());
            assertEquals(target, camera.orientation());
        }
    }

    @Test
    void smoothingUsesElapsedTimeAndResetDiscardsOldLook() {
        FlightCamera coarse = new FlightCamera(), fine = new FlightCamera();
        coarse.update(100, 50, 70, 0, 0.35f);
        fine.update(100, 50, 70, 0, 0.35f);
        for (int i = 0; i < 2; i++) { coarse.update(0, 0, 0, 0.1, 0.35f); }
        for (int i = 0; i < 8; i++) { fine.update(0, 0, 0, 0.025, 0.35f); }
        assertTrue(coarse.orientation().forward().dot(fine.orientation().forward()) > 1 - 1e-9);
        assertTrue(coarse.orientation().up().dot(fine.orientation().up()) > 1 - 1e-9);
        FlightOrientation arrival = FlightOrientation.fromAngles(175, -35, 90);
        coarse.reset(arrival);
        assertEquals(arrival, coarse.orientation()); assertEquals(arrival, coarse.target());
        coarse.update(0, 0, 0, 0.05, 0.35f);
        assertTrue(arrival.forward().dot(coarse.orientation().forward()) > 1 - 1e-12);
    }

    @Test
    void directLookCrossesPolesAndRollRotatesTheLocalBasis() {
        FlightCamera camera = new FlightCamera();
        camera.update(0, 120, 0, 0.016, 0);
        assertTrue(camera.orientation().forward().z() < 0);
        assertTrue(camera.orientation().up().y() < 0);
        camera.reset(FlightOrientation.IDENTITY);
        camera.update(0, 0, 90, 0.016, 0);
        assertEquals(1, camera.orientation().left().y(), 1e-12);
        camera.update(0, 60, 0, 0.016, 0);
        assertTrue(camera.orientation().forward().x() > 0.8);
        assertEquals(0, camera.orientation().forward().y(), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> camera.update(0, 0, 0, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> camera.update(0, 0, 0, 0.01, 1));
    }
}
