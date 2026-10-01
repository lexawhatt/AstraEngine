package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceReferenceTest {
    @Test
    void allSavedWindowsRoundTripWithPhysicalAltitude() {
        assertEquals(6, SurfaceReferences.builtIns().size());
        for (var reference : SurfaceReferences.builtIns()) {
            for (double fraction : new double[]{-1, -.999, -.53, 0, .731, .999, 1}) {
                SpaceVector host = new SpaceVector(reference.patch().halfWidth() * fraction, 31.125,
                        reference.patch().halfWidth() * fraction * -.71);
                var geographic = reference.geographic(host);
                assertEquals(host.y() + reference.altitudeOffsetMeters() - reference.patch().seaY(),
                        geographic.altitudeMeters(), 0);
                assertTrue(host.distance(reference.resolve(geographic).orElseThrow()) < 1e-8);
                var pose = reference.pose(host, SpaceVector.ZERO, FlightOrientation.IDENTITY);
                assertTrue(pose.bodyPositionMeters().distance(geographic.toBody(reference.patch().radiusMeters())) < 1e-8);
            }
        }
    }

    @Test
    void datelineAndBothPolesUseTheSameInverseWithoutLongitudeSeams() {
        for (double latitude : new double[]{Math.PI / 2, -Math.PI / 2, 0}) {
            var patch = new SurfacePatch(6371000, latitude, -Math.PI, 4096, 64);
            var reference = new SurfaceReference("test:planet", "test:window", patch, -5120,
                    new PlanetaryTopology(1, 12, 6371000));
            for (double x : new double[]{-2017.25, 0, 2017.25}) {
                var feet = new SpaceVector(x, 77.5, 21.25);
                var geographic = reference.geographic(feet);
                assertTrue(geographic.longitudeRadians() >= -Math.PI && geographic.longitudeRadians() < Math.PI);
                assertTrue(reference.resolve(geographic).orElseThrow().distance(feet) < 1e-8);
            }
        }
    }

    @Test
    void independentTerrainIdentitiesAreNotAliasedByEarthRadius() {
        var coast = SurfaceReferences.forDimension(ContinentalRegion.COAST.dimensionId()).orElseThrow();
        var alpine = SurfaceReferences.forDimension(ContinentalRegion.ALPINE.dimensionId()).orElseThrow();
        var legacy = SurfaceReferences.forDimension("astraengine:surface_earth").orElseThrow();
        assertEquals(coast.geographyId(), alpine.geographyId());
        assertFalse(coast.geographyId().equals(legacy.geographyId()));
        assertFalse(coast.dimensionId().equals(alpine.dimensionId()));
        assertTrue(SurfaceReferences.forDimension("minecraft:overworld").isEmpty());
        assertTrue(SurfaceReferences.forDimension(null).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> SurfaceReferences.builtIns().clear());
    }

    @Test
    void outsideAddressesNeverClipOrFallBackToAWindowCenter() {
        var reference = SurfaceReferences.forDimension("astraengine:surface_earth").orElseThrow();
        assertTrue(reference.resolve(new GeographicPosition(0, -Math.PI, 100)).isEmpty());
        assertTrue(reference.resolve(new GeographicPosition(.1, 0, 100)).isEmpty());
        assertTrue(reference.resolve(new GeographicPosition(0, 0, -6371000)).isEmpty());
        assertFalse(reference.contains(new SpaceVector(2049, 64, 0)));
        assertFalse(reference.contains(null));
        assertThrows(IllegalArgumentException.class, () -> reference.geographic(new SpaceVector(2049, 64, 0)));
        assertThrows(IllegalArgumentException.class, () -> reference.resolve(null));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceReference("missing_namespace", "a:b",
                reference.patch(), 0, reference.topology()));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceReference("a:b", "c:d",
                reference.patch(), 0, new PlanetaryTopology(1, 12, 1737400)));
    }

    @Test
    void shiftedAltitudeUsesTheSameDifferentialVelocityAsBodyPositions() {
        var reference = SurfaceReferences.forDimension(ContinentalRegion.ALPINE.dimensionId()).orElseThrow();
        var feet = new SpaceVector(412.5, 1100, -191.25);
        var velocity = new SpaceVector(.17, -.08, .11);
        var pose = reference.pose(feet, velocity, FlightOrientation.fromAngles(31, -27, 12));
        double dt = .01;
        var next = reference.geographic(feet.add(velocity.multiply(dt))).toBody(reference.patch().radiusMeters());
        var previous = reference.geographic(feet.subtract(velocity.multiply(dt))).toBody(reference.patch().radiusMeters());
        assertTrue(next.subtract(previous).multiply(.5 / dt).distance(pose.bodyVelocityMetersPerTick()) < 2e-7);
        assertEquals(8268, reference.geographic(feet).altitudeMeters());
    }
}
