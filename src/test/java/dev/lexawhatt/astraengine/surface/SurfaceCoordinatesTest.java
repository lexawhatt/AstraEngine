package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceCoordinatesTest {
    @Test
    void geographicCoordinatesRoundTripAcrossPolesSeamAndAltitude() {
        for (double latitude : new double[]{-Math.PI / 2, -1.2, 0, 1.2, Math.PI / 2}) {
            for (double longitude : new double[]{-Math.PI, -2.1, 0, 2.1, Math.PI - 1e-10}) {
                for (double altitude : new double[]{-48, 0, 100_000}) {
                    GeographicPosition original = new GeographicPosition(latitude, longitude, altitude);
                    SpaceVector body = original.toBody(1_737_400);
                    GeographicPosition restored = GeographicPosition.fromBody(body, 1_737_400);
                    assertTrue(body.distance(restored.toBody(1_737_400)) < 1e-8);
                    assertEquals(latitude, restored.latitudeRadians(), 1e-12);
                    assertEquals(altitude, restored.altitudeMeters(), 1e-8);
                }
            }
        }
        assertEquals(0, GeographicPosition.fromBody(new SpaceVector(0, 3, 0), 2).longitudeRadians());
        assertThrows(IllegalArgumentException.class, () -> GeographicPosition.fromBody(SpaceVector.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new GeographicPosition(0, Math.PI, 0));
        assertThrows(IllegalArgumentException.class, () -> new GeographicPosition(0, 0, -1).toBody(1));
    }

    @Test
    void permanentPatchInvertsPositionsAndItsBasisIsRightHanded() {
        for (String body : new String[]{"moon", "earth"}) {
            SurfacePatch patch = SurfaceDefinition.byBody(body).patch();
            for (int x : new int[]{-2048, -731, 0, 813, 2048}) {
                for (int z : new int[]{-2048, -415, 0, 956, 2048}) {
                    for (double y : new double[]{16, 64, 121.25, 15000}) {
                        SpaceVector local = new SpaceVector(x, y, z);
                        assertTrue(local.distance(patch.toLocal(patch.toBody(local))) < 1e-7);
                        FlightOrientation basis = patch.orientationAt(x, z);
                        assertTrue(basis.up().distance(patch.normal(x, z)) < 1e-12);
                        assertEquals(0, basis.left().dot(basis.up()), 1e-12);
                        assertEquals(1, cross(basis.left(), basis.up()).dot(basis.forward()), 1e-12);
                    }
                }
            }
            assertTrue(patch.contains(2048, -2048));
            assertFalse(patch.contains(2048.001, 0));
            assertFalse(patch.contains(Double.NaN, 0));
            assertThrows(IllegalArgumentException.class, () -> patch.toLocal(new SpaceVector(-1, 0, 0)));
            assertEquals(patch.radiusMeters(), patch.toBody(new SpaceVector(0, 64, 0)).length(), 1e-8);
            SpaceVector direction = new SpaceVector(3, -5, 7);
            assertTrue(direction.distance(patch.toLocalDirection(415, -891,
                    patch.toBodyDirection(415, -891, direction))) < 1e-12);
            FlightOrientation camera = FlightOrientation.fromAngles(73, 131, -49);
            FlightOrientation restored = patch.toLocalOrientation(415, -891,
                    patch.toBodyOrientation(415, -891, camera));
            assertTrue(camera.forward().distance(restored.forward()) < 1e-12);
            assertTrue(camera.up().distance(restored.up()) < 1e-12);
        }
    }

    @Test
    void parentResolvedFramePreservesPointsDirectionsAndFullRolledOrientation() {
        var sol = CosmosGenerator.sol();
        var definition = SurfaceDefinition.byBody("moon");
        double seconds = 928374;
        BodyFixedFrame frame = definition.frame(sol, seconds, seconds * 20);
        assertEquals(sol.positionAt("moon", seconds), frame.centerMeters());
        assertTrue(frame.centerMeters().distance(sol.positionAt("earth", seconds)) > 300_000_000);
        SpaceVector bodyPoint = definition.patch().toBody(new SpaceVector(415.25, 104.125, -891.5));
        assertTrue(frame.toBodyPoint(frame.toSystemPoint(bodyPoint)).distance(bodyPoint) < 0.0001);
        SpaceVector direction = new SpaceVector(-4, 5, 8);
        assertTrue(frame.toBodyDirection(frame.toSystemDirection(direction)).distance(direction) < 1e-12);
        FlightOrientation camera = FlightOrientation.fromAngles(27, 113, -89);
        FlightOrientation restored = frame.toBodyOrientation(frame.toSystemOrientation(camera));
        assertTrue(camera.forward().distance(restored.forward()) < 1e-12);
        assertTrue(camera.up().distance(restored.up()) < 1e-12);
    }

    @Test
    void bodyFrameExactlyInvertsTheOrbitalShaderTiltThenSpinConvention() {
        var sol = CosmosGenerator.sol();
        var earth = sol.bodies().stream().filter(body -> body.id().equals("earth")).findFirst().orElseThrow();
        double spin = 1.7, tilt = earth.axialTiltRadians();
        BodyFixedFrame frame = BodyFixedFrame.of(sol, earth, 700, spin);
        SpaceVector system = new SpaceVector(0.3, -0.4, 0.5);
        double tiltedY = Math.cos(tilt) * system.y() + Math.sin(tilt) * system.z();
        double tiltedZ = -Math.sin(tilt) * system.y() + Math.cos(tilt) * system.z();
        SpaceVector shader = new SpaceVector(Math.cos(spin) * system.x() + Math.sin(spin) * tiltedZ,
                tiltedY, -Math.sin(spin) * system.x() + Math.cos(spin) * tiltedZ);
        assertTrue(shader.distance(frame.toBodyDirection(system)) < 1e-12);
    }

    @Test
    void unsupportedAndAlteredBindingsNeverAcquireAReplacementSurface() {
        assertTrue(SurfaceDefinition.find("example:sol", "earth").isEmpty());
        assertTrue(SurfaceDefinition.find("sol", "mars").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SurfaceDefinition.byBody(null));
        SurfaceDefinition earth = SurfaceDefinition.byBody("earth");
        assertThrows(IllegalArgumentException.class, () -> new SurfaceDefinition("sol", "earth", 1, 7,
                earth.patch(), new SurfaceGeography(1, 7, SurfaceGeography.Kind.EARTH)));
        assertThrows(IllegalArgumentException.class, () -> earth.terrainY(2049, 0));
    }

    private static SpaceVector cross(SpaceVector first, SpaceVector second) {
        return new SpaceVector(first.y() * second.z() - first.z() * second.y(),
                first.z() * second.x() - first.x() * second.z(), first.x() * second.y() - first.y() * second.x());
    }
}
