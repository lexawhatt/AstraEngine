package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceApproachTest {
    @Test
    void arrivalGoesAroundTheBodyThenDescendsWithoutCuttingThroughItsInterior() {
        SurfaceDefinition moon = SurfaceDefinition.byBody("moon");
        double radius = moon.patch().radiusMeters();
        SpaceVector end = moon.patch().toBody(new SpaceVector(.5, moon.terrainY(.5, .5) + 1.62, .5));
        for (SpaceVector start : new SpaceVector[]{new SpaceVector(-radius * 4, 0, 0),
                new SpaceVector(radius * 2, radius, radius), end.multiply(1.00001)}) {
            SurfaceApproach route = new SurfaceApproach(start, end, radius, false);
            assertEquals(480, route.durationTicks());
            assertSame(start, route.positionAt(0));
            assertSame(end, route.positionAt(480));
            for (double tick = 0; tick <= 480; tick += .5) {
                assertTrue(route.positionAt(tick).length() >= Math.min(start.length(), end.length()) - 1e-7);
                assertEquals(1, route.travelDirectionAt(tick).length(), 1e-12);
                if (tick >= 240) {
                    assertTrue(route.positionAt(tick).normalized().distance(end.normalized()) < 1e-12);
                }
            }
            assertTrue(route.positionAt(240).length() >= radius + 10000 - 1e-7);
        }
    }

    @Test
    void ascentIsAnExactReversalAndAntipodalAlignmentRemainsFinite() {
        double radius = 1_737_400;
        SpaceVector surface = new SpaceVector(radius - 20, 0, 0);
        SpaceVector orbit = new SpaceVector(-radius * 4, 0, 0);
        SurfaceApproach down = new SurfaceApproach(orbit, surface, radius, false);
        SurfaceApproach up = new SurfaceApproach(surface, orbit, radius, true);
        for (int tick = 0; tick <= 480; tick++) {
            assertEquals(down.positionAt(480 - tick), up.positionAt(tick));
            assertTrue(up.positionAt(tick).length() >= surface.length() - 1e-7);
        }
        assertThrows(IllegalArgumentException.class, () -> up.positionAt(-1));
        assertThrows(IllegalArgumentException.class, () -> up.positionAt(481));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceApproach(SpaceVector.ZERO, orbit, radius, true));
    }

    @Test
    void guidanceKeepsThePlanetInViewAtAltitudeAndSettlesIntoTheExactUprightLandingView() {
        SurfaceDefinition moon = SurfaceDefinition.byBody("moon");
        double radius = moon.patch().radiusMeters();
        SpaceVector end = moon.patch().toBody(new SpaceVector(.5, moon.terrainY(.5, .5) + 1.62, .5));
        SurfaceApproach route = new SurfaceApproach(new SpaceVector(-radius * 4, radius, 0), end, radius, false);
        FlightOrientation start = FlightOrientation.fromAngles(73, -15, 89);
        FlightOrientation finish = moon.patch().toBodyOrientation(.5, .5, FlightOrientation.fromAngles(17, 15, 0));
        assertSame(start, route.orientationAt(0, start, finish));
        assertSame(finish, route.orientationAt(480, start, finish));
        for (double tick = 60; tick <= 420; tick += .25) {
            SpaceVector point = route.positionAt(tick);
            FlightOrientation camera = route.orientationAt(tick, start, finish);
            assertTrue(camera.up().dot(point.normalized()) > 0, "Guided camera inverted its local horizon");
            if (point.length() >= radius * 2) {
                assertTrue(camera.forward().dot(point.normalized().multiply(-1)) > .96,
                        "The planet left the guided camera's central 16-degree cone");
            }
        }
        FlightOrientation local = moon.patch().toLocalOrientation(.5, .5, route.orientationAt(480, start, finish));
        assertEquals(15, local.pitch(), 1e-5);
        assertEquals(0, local.roll(), 1e-5);
    }

    @Test
    void cameraAngularChangesStayBoundedAtSubticksAcrossPolesAntipodesAndDeparture() {
        double radius = 1_737_400;
        SpaceVector[] starts = {new SpaceVector(-radius * 4, 0, 0), new SpaceVector(0, radius * 4, 0),
                new SpaceVector(0, -radius * 4, 0), new SpaceVector(radius * 4, 0, 0)};
        SpaceVector end = new SpaceVector(radius + 3, 0, 0);
        FlightOrientation first = FlightOrientation.fromAngles(31, 117, -169);
        FlightOrientation last = new SurfacePatch(radius, 0, 0, 2048, 64).toBodyOrientation(0, 0,
                FlightOrientation.fromAngles(0, 15, 0));
        double step = .125;
        for (SpaceVector start : starts) {
            for (boolean ascending : new boolean[]{false, true}) {
                SurfaceApproach route = new SurfaceApproach(ascending ? end : start, ascending ? start : end, radius, ascending);
                FlightOrientation previous = route.orientationAt(0, first, last);
                for (double tick = step; tick <= 480; tick += step) {
                    FlightOrientation current = route.orientationAt(tick, first, last);
                    double quaternionDot = Math.abs(previous.x() * current.x() + previous.y() * current.y()
                            + previous.z() * current.z() + previous.w() * current.w());
                    double angleDegrees = Math.toDegrees(2 * Math.acos(Math.clamp(quaternionDot, 0, 1)));
                    assertTrue(angleDegrees / step < 6, "Surface camera exceeded 6 degrees per tick at " + tick);
                    previous = current;
                }
            }
        }
    }

    @Test
    void radialEndViewHasAStableHeadingFallbackAndRejectsInvalidCameraInputs() {
        double radius = 1_737_400;
        SurfaceApproach route = new SurfaceApproach(new SpaceVector(-4 * radius, 0, 0),
                new SpaceVector(radius + 3, 0, 0), radius, false);
        FlightOrientation radial = FlightOrientation.fromAngles(-90, 0, 0);
        for (int tick = 0; tick <= 480; tick++) {
            assertEquals(1, route.orientationAt(tick, FlightOrientation.IDENTITY, radial).forward().length(), 1e-12);
        }
        assertThrows(IllegalArgumentException.class, () -> route.orientationAt(240, null, radial));
        assertThrows(IllegalArgumentException.class, () -> route.orientationAt(Double.NaN, radial, radial));
    }

    @Test
    void physicalScaleDescentReachesHumanScaleBeforeTheFinalFullTickAndSubtick() {
        for (String id : new String[]{"earth", "moon"}) {
            SurfaceDefinition definition = SurfaceDefinition.byBody(id);
            double radius = definition.patch().radiusMeters();
            SpaceVector end = definition.patch().toBody(new SpaceVector(.5, definition.terrainY(.5, .5) + 1.62, .5));
            SpaceVector start = end.normalized().multiply(radius * 4);
            SurfaceApproach down = new SurfaceApproach(start, end, radius, false);
            SurfaceApproach up = new SurfaceApproach(end, start, radius, true);
            assertTrue(down.positionAt(479).distance(end) < .1, id + " still teleports at the final full tick");
            assertTrue(down.positionAt(479.875).distance(end) < .001, id + " still jumps at the final render subtick");
            assertTrue(up.positionAt(1).distance(end) < .1, id + " departure skips its initial human-scale altitude");
            double middleClearance = down.positionAt(360).length() - end.length();
            assertTrue(middleClearance > 10_000 && middleClearance < 30_000,
                    id + " radial midpoint is not within the intended tens-of-kilometers envelope");
            assertTrue(down.positionAt(420).length() - end.length() < 250,
                    id + " final three seconds do not show human-scale approach");

            // A half-sized interval has about one quarter the displacement at each radial endpoint:
            // this checks the zero limiting velocity without dividing sub-ulp positions by vanishing times.
            for (double delta : new double[]{.1, .02}) {
                double first = down.positionAt(240 + delta).distance(down.positionAt(240));
                double firstHalf = down.positionAt(240 + delta / 2).distance(down.positionAt(240));
                double last = down.positionAt(480 - delta).distance(end);
                double lastHalf = down.positionAt(480 - delta / 2).distance(end);
                assertEquals(.25, firstHalf / first, .001);
                assertEquals(.25, lastHalf / last, .005);
            }
        }
    }

    @Test
    void radialLogarithmStaysFiniteAtTheAcceptedEnvelopeAndRejectsAstronomicallyInvalidRoutes() {
        double radius = 1e12;
        SpaceVector start = new SpaceVector(FlightDynamics.LOCAL_RADIUS, 0, 0);
        SpaceVector end = new SpaceVector(radius + 100, 0, 0);
        SurfaceApproach route = new SurfaceApproach(start, end, radius, false);
        for (double tick : new double[]{0, 240, 240.00001, 360, 479, 479.99999, 480}) {
            double distance = route.positionAt(tick).length();
            assertTrue(Double.isFinite(distance) && distance >= end.length() && distance <= start.length());
        }
        assertThrows(IllegalArgumentException.class, () -> new SurfaceApproach(
                new SpaceVector(Double.MAX_VALUE, 0, 0), end, radius, false));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceApproach(
                new SpaceVector(FlightDynamics.LOCAL_RADIUS * 2, 0, 0), end, radius, false));
    }
}
