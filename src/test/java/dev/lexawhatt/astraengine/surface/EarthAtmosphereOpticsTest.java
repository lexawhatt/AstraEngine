package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.nio.FloatBuffer;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarthAtmosphereOpticsTest {
    private static final double RADIUS = 6371;
    private static final EarthAtmosphereOptics OPTICS = EarthAtmosphereOptics.bake(RADIUS, () -> false);

    @Test void verticalColumnsMatchAnalyticExponentialsAndOzoneArea() {
        var columns = OPTICS.columns(0, 1);
        assertEquals(8 * (1 - Math.exp(-10)), columns.x(), .0001);
        assertEquals(1.2 * (1 - Math.exp(-80 / 1.2)), columns.y(), .001);
        assertEquals(15, columns.z(), .0001);
        assertEquals(new SpaceVector(1, 1, 1), OPTICS.transmission(80, 1));
    }

    @Test void interpolatedColumnsMatchIndependentDenseSphericalQuadrature() {
        var random = new Random(84105);
        for (int i = 0; i < 128; i++) {
            double altitude = random.nextDouble() * 79;
            double r = RADIUS + altitude;
            double horizon = -Math.sqrt(1 - RADIUS * RADIUS / (r * r));
            double cosine = horizon + (1 - horizon) * random.nextDouble();
            var expected = reference(altitude, cosine);
            var actual = OPTICS.columns(altitude, cosine);
            // Compare resulting spectral transport, where opaque long columns can differ harmlessly.
            var transmission = OPTICS.transmission(altitude, cosine);
            var referenceTransmission = transmission(expected);
            assertTrue(transmission.distance(referenceTransmission) < .035,
                    () -> "Spectral lookup error at " + altitude + "/" + cosine + ": " + actual + " vs " + expected);
        }
    }

    @Test void shadowAndSpectralExtinctionAreGeometricAndBounded() {
        assertEquals(SpaceVector.ZERO, OPTICS.transmission(0, -.01));
        var noon = OPTICS.transmission(0, 1);
        var sunset = OPTICS.transmission(0, .005);
        assertTrue(noon.x() > noon.z());
        assertTrue(sunset.z() < sunset.x() * .1);
        assertTrue(sunset.x() < noon.x());
        double altitude = 30, r = RADIUS + altitude;
        double horizon = -Math.sqrt(1 - RADIUS * RADIUS / (r * r));
        assertEquals(SpaceVector.ZERO, OPTICS.transmission(altitude, horizon - 1e-5));
        assertTrue(OPTICS.transmission(altitude, horizon + .01).length() > 0);
        for (double h : new double[] {0, 1, 10, 25, 50, 79, 80}) {
            for (double mu : new double[] {-.2, 0, .1, .5, 1}) {
                var value = OPTICS.transmission(h, mu);
                assertTrue(value.x() >= 0 && value.x() <= 1 && value.y() >= 0 && value.y() <= 1
                        && value.z() >= 0 && value.z() <= 1);
            }
        }
    }

    @Test void tableOwnershipIsBoundedIndependentAndCancellable() {
        var target = FloatBuffer.allocate(EarthAtmosphereOptics.WIDTH * EarthAtmosphereOptics.TEXTURE_HEIGHT * 4);
        OPTICS.writeTo(target);
        assertEquals(target.capacity(), target.position());
        var before = OPTICS.columns(2, .3);
        target.put(0, -1000);
        assertEquals(before, OPTICS.columns(2, .3));
        assertThrows(CancellationException.class, () -> EarthAtmosphereOptics.bake(RADIUS, () -> true));
        assertThrows(IllegalArgumentException.class, () -> EarthAtmosphereOptics.bake(0, () -> false));
        assertThrows(IllegalArgumentException.class, () -> OPTICS.transmission(0, Double.NaN));
    }

    @Test void raysFromOrbitKeepTheirImpactParameterAndSkipEmptySpace() {
        double altitude = 1000, r = RADIUS + altitude;
        assertEquals(new SpaceVector(1, 1, 1), OPTICS.transmission(altitude, 0));
        assertEquals(SpaceVector.ZERO, OPTICS.transmission(altitude, -1));
        for (double tangentAltitude : new double[] {1, 10, 25, 60}) {
            double impact = RADIUS + tangentAltitude;
            double cosine = -Math.sqrt(1 - impact * impact / (r * r));
            double entryCosine = -Math.sqrt(1 - impact * impact / Math.pow(RADIUS + 80, 2));
            var expected = transmission(reference(80, entryCosine));
            assertTrue(OPTICS.transmission(altitude, cosine).distance(expected) < .035);
        }
    }

    // Independent Cartesian midpoint reference with4096 samples; no LUT mapping or production integrator reuse.
    private static SpaceVector reference(double altitude, double cosine) {
        var origin = new SpaceVector(0, RADIUS + altitude, 0);
        var ray = new SpaceVector(Math.sqrt(1 - cosine * cosine), cosine, 0);
        double b = origin.dot(ray), top = RADIUS + 80;
        double distance = -b + Math.sqrt(b * b + top * top - origin.dot(origin));
        double dx = distance / 4096, a = 0, m = 0, o = 0;
        for (int i = 0; i < 4096; i++) {
            double h = Math.max(0, origin.add(ray.multiply((i + .5) * dx)).length() - RADIUS);
            a += Math.exp(-h / 8) * dx;
            m += Math.exp(-h / 1.2) * dx;
            o += Math.max(0, 1 - Math.abs(h - 25) / 15) * dx;
        }
        return new SpaceVector(a, m, o);
    }
    private static SpaceVector transmission(SpaceVector column) {
        return new SpaceVector(Math.exp(-.0058 * column.x() - .0032 * column.y() - .00065 * column.z()),
                Math.exp(-.0135 * column.x() - .0032 * column.y() - .00188 * column.z()),
                Math.exp(-.0331 * column.x() - .0032 * column.y() - .00008 * column.z()));
    }
}
