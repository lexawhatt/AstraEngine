package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarthSkyIrradianceTest {
    private static final EarthAtmosphereOptics OPTICS = EarthAtmosphereOptics.bake(6371, () -> false);

    @Test void skyIrradianceMatchesIndependentConvergedHemisphereIntegral() {
        for (double[] ray : new double[][] {{.85, 1}, {2.7, .4}, {2.7, .1}, {8.5, .8}, {2.7, 0}, {2.7, -.05}}) {
            var coarse = reference(ray[0], ray[1], 16, 32, 48);
            var fine = reference(ray[0], ray[1], 32, 64, 96);
            var actual = OPTICS.skyIrradiance(ray[0], ray[1]);
            assertTrue(coarse.distance(fine) < .0008 + fine.length() * .015,
                    () -> "Independent hemisphere reference has not converged: " + coarse + " vs " + fine);
            assertTrue(actual.distance(fine) < .0007 + fine.length() * .045,
                    () -> "Baked hemisphere irradiance disagrees at " + ray[0] + "/" + ray[1]
                            + ": " + actual + " vs " + fine);
        }
    }

    @Test void daylightSkyIsBoundedBlueAndShadowDoesNotEmit() {
        assertEquals(SpaceVector.ZERO, OPTICS.skyIrradiance(2.7, -1));
        assertEquals(SpaceVector.ZERO, OPTICS.skyIrradiance(80, .4));
        var sky = OPTICS.skyIrradiance(2.7, .4);
        assertTrue(sky.z() > sky.y() && sky.y() > sky.x());
        for (int h = 0; h <= 80; h += 4) {
            for (int s = -20; s <= 20; s++) {
                var value = OPTICS.skyIrradiance(h, s / 20.0);
                assertTrue(value.x() >= 0 && value.y() >= 0 && value.z() >= 0
                        && value.x() < 1 && value.y() < 1 && value.z() < 1);
            }
        }
    }

    // Independent angular midpoint quadrature and Cartesian ray integration. View transmission
    // comes from column differences, not the production step-by-step extinction recurrence.
    private static SpaceVector reference(double height, double sunMu, int elevations, int azimuths, int steps) {
        double[] result = new double[3], rayleigh = {.0058, .0135, .0331}, ozone = {.00065, .00188, .00008};
        var origin = new SpaceVector(0, 6371 + height, 0);
        var sun = new SpaceVector(Math.sqrt(1 - sunMu * sunMu), sunMu, 0);
        for (int e = 0; e < elevations; e++) {
            double mu = (e + .5) / elevations;
            var startColumn = OPTICS.columns(height, mu);
            for (int a = 0; a < azimuths; a++) {
                double phi = (a + .5) * Math.PI * 2 / azimuths, horizontal = Math.sqrt(1 - mu * mu);
                var direction = new SpaceVector(horizontal * Math.cos(phi), mu, horizontal * Math.sin(phi));
                double along = origin.dot(direction);
                double distance = -along + Math.sqrt(along * along + 6451 * 6451.0 - origin.dot(origin));
                double cosine = direction.dot(sun);
                double phaseR = 3.0 * (1 + cosine * cosine) / (16 * Math.PI);
                double phaseM = (1 - .76 * .76) / (4 * Math.PI * Math.pow(1 + .76 * .76 - 1.52 * cosine, 1.5));
                for (int i = 0; i < steps; i++) {
                    double first = distance * i * i / (steps * steps);
                    double last = distance * (i + 1) * (i + 1) / (steps * steps);
                    var point = origin.add(direction.multiply((first + last) * .5));
                    double altitude = point.length() - 6371;
                    var remaining = OPTICS.columns(altitude, point.normalized().dot(direction));
                    var column = startColumn.subtract(remaining);
                    double[] viewColumns = {Math.max(0, column.x()), Math.max(0, column.y()), Math.max(0, column.z())};
                    var solar = OPTICS.transmission(altitude, Math.clamp(point.normalized().dot(sun), -1, 1));
                    double[] direct = {solar.x(), solar.y(), solar.z()};
                    for (int c = 0; c < 3; c++) {
                        double view = Math.exp(-rayleigh[c] * viewColumns[0] - .0032 * viewColumns[1] - ozone[c] * viewColumns[2]);
                        double source = rayleigh[c] * Math.exp(-altitude / 8) * phaseR
                                + .0032 * .9 * Math.exp(-altitude / 1.2) * phaseM;
                        result[c] += view * source * direct[c] * (last - first) * mu * 2 * Math.PI / (elevations * azimuths);
                    }
                }
            }
        }
        return new SpaceVector(result[0], result[1], result[2]);
    }
}
