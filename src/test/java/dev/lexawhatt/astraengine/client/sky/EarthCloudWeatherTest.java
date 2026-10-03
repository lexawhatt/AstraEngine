package dev.lexawhatt.astraengine.client.sky;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EarthCloudWeatherTest {
    @Test
    void frontsLeaveLargeClearRegionsAndIncludeDifferentVerticalTypes() {
        var noise = new CloudNoiseField();
        var state = EarthCloudState.sample(300, 0, .65f, .15, 0, 0, 1);
        int clear = 0, cloudy = 0, sheets = 0, deep = 0;
        // Equal-area sphere samples prevent polar pixels from inflating clear-sky coverage.
        for (int i = 0; i < 8192; i++) {
            double y = 1 - (i + .5) * 2 / 8192, angle = i * 2.399963229728653;
            double radius = Math.sqrt(1 - y * y);
            var n = new SpaceVector(radius * Math.cos(angle), y, radius * Math.sin(angle));
            var region = EarthCloudWeather.sample(noise, state, n.multiply(6373));
            double mass = Math.max(region.stratus(), Math.max(region.cumulus(), region.convection()));
            if (mass < .01) { clear++; }
            if (mass > .2) { cloudy++; }
            if (region.stratus() > .3 && region.convection() < .1) { sheets++; }
            if (region.convection() > .3) { deep++; }
        }
        assertTrue(clear > 8192 * .3 && clear < 8192 * .85, "Weather must include broad clear gaps");
        assertTrue(cloudy > 8192 * .08 && cloudy < 8192 * .55, "Weather must not become a uniform blanket");
        assertTrue(sheets > 30 && deep > 30, "Low sheets and deep convection must coexist");
    }

    @Test
    void longitudeSeamDoesNotCutRegionalFronts() {
        var noise = new CloudNoiseField();
        var state = EarthCloudState.sample(12500, 0, .7f, .75, .2f, 0, 1);
        for (int i = 0; i < 101; i++) {
            double latitude = -Math.PI * .49 + i * Math.PI * .98 / 100;
            var a = EarthCloudWeather.sample(noise, state, point(latitude, Math.PI - 1e-9));
            var b = EarthCloudWeather.sample(noise, state, point(latitude, -Math.PI + 1e-9));
            assertEquals(a.stratus(), b.stratus(), 1e-6);
            assertEquals(a.cumulus(), b.cumulus(), 1e-6);
            assertEquals(a.convection(), b.convection(), 1e-6);
        }
    }

    @Test
    void aThinDeckKeepsTheSameOpticalMassAtDistantStepSizes() {
        double expected = 0;
        for (int i = 0; i < 100_000; i++) {
            double height = (i + .5) * 10 / 100_000;
            expected += EarthCloudWeather.profile(.85, 1.85, height, height) * 10 / 100_000;
        }
        for (int count : new int[] {2, 3, 8, 12, 48, 120}) {
            double mass = 0;
            for (int i = 0; i < count; i++) {
                double start = (double) i * 10 / count, finish = (double) (i + 1) * 10 / count;
                mass += EarthCloudWeather.profile(.85, 1.85, start, finish) * (finish - start);
            }
            assertEquals(expected, mass, 1e-8, "A sub-step stratus deck must not disappear or gain optical mass");
        }
    }

    @Test
    void frontalFlowHasNoAngularSingularityAtItsOccupiedCenter() {
        assertEquals(SpaceVector.ZERO, EarthCloudWeather.flowCoordinates(0, 0, 2100, 1.8));
        for (double r : new double[] {1e-9, 1e-7, 1e-5, .001, .01}) {
            for (int i = 0; i < 64; i++) {
                double angle = i * Math.PI * 2 / 64;
                var flow = EarthCloudWeather.flowCoordinates(r * Math.cos(angle), r * Math.sin(angle), 2100, 1.8);
                assertTrue(flow.length() <= r * 2100 * .739,
                        "An arbitrarily small displacement must not traverse a finite mesoscale texture ring");
            }
        }
        for (double pitch : new double[] {1.8, 2.4}) {
            for (int i = 1; i <= 180; i++) {
                double r = i * .01, step = 1e-5;
                var a = EarthCloudWeather.flowCoordinates(r - step, 0, 2100, pitch);
                var b = EarthCloudWeather.flowCoordinates(r + step, 0, 2100, pitch);
                double derivative = b.subtract(a).length() / (2 * step * 2100);
                assertTrue(derivative <= EarthCloudWeather.flowFootprintScale(r, pitch) + 1e-8,
                        "Frontal texture shear must stay bounded at the outer tail");
            }
        }
    }

    @Test
    void logarithmicArmAndTextureShareTheSameExpandingCenterline() {
        for (double pitch : new double[] {1.8, 2.4}) {
            for (int i = 0; i <= 120; i++) {
                double angle = -2 + i * 3.8 / 120;
                // Independent inverse of the regularized logarithmic arm relation:
                // r^2+core^2 = (reference^2+core^2)*exp(2*theta/pitch).
                double radius = Math.sqrt((.6 * .6 + .12 * .12) * Math.exp(2 * angle / pitch) - .12 * .12);
                var spiral = EarthCloudWeather.spiralCoordinates(radius * Math.cos(angle), radius * Math.sin(angle), pitch);
                assertEquals(radius, spiral.x(), 1e-12);
                assertEquals(0, spiral.y(), 1e-12, "The arm must be a straight radial line in its texture coordinates");
                assertEquals(0, EarthCloudWeather.spiralArmDistance(spiral, radius, pitch, 0), 2e-8);
                var opposite = EarthCloudWeather.spiralCoordinates(-radius * Math.cos(angle), -radius * Math.sin(angle), pitch);
                assertTrue(EarthCloudWeather.spiralArmDistance(opposite, radius, pitch, 0) > radius * .3,
                        "A logarithmic arm must not turn into an azimuth-independent concentric ring");
                var dry = EarthCloudWeather.spiralCoordinates(radius * Math.cos(angle - .75),
                        radius * Math.sin(angle - .75), pitch);
                assertEquals(0, EarthCloudWeather.spiralArmDistance(dry, radius, pitch, .75), 2e-8,
                        "The dry slot must follow the same winding, with only its phase offset changed");
            }
        }
    }

    @Test
    void logarithmicFlowIsSmoothAtTheCoreAndAcrossTheAngularSeam() {
        for (double pitch : new double[] {1.8, 2.4}) {
            double coreRotation = -.5 * pitch * Math.log((.12 * .12) / (.6 * .6 + .12 * .12));
            for (int i = 0; i < 64; i++) {
                double angle = Math.PI * 2 * i / 64, radius = 1e-7;
                var flow = EarthCloudWeather.flowCoordinates(radius * Math.cos(angle), radius * Math.sin(angle), 1, pitch);
                assertEquals(.65 * Math.cos(angle + coreRotation), flow.x() / radius, 1e-10);
                assertEquals(.65 * Math.sin(angle + coreRotation), flow.y() / radius, 1e-10);
                assertTrue(flow.z() <= radius * radius * .35 / .24,
                        "The third texture coordinate must have zero derivative at the storm center");
            }
            for (double radius : new double[] {.01, .12, .6, 1.8}) {
                var above = EarthCloudWeather.flowCoordinates(-radius, 1e-9, 1, pitch);
                var below = EarthCloudWeather.flowCoordinates(-radius, -1e-9, 1, pitch);
                assertTrue(above.subtract(below).length() <= 2e-9 * EarthCloudWeather.flowFootprintScale(radius, pitch) * 1.00001);
                var a = EarthCloudWeather.spiralCoordinates(-radius, 1e-9, pitch);
                var b = EarthCloudWeather.spiralCoordinates(-radius, -1e-9, pitch);
                assertEquals(EarthCloudWeather.spiralArmDistance(a, Math.hypot(radius, 1e-9), pitch, 0),
                        EarthCloudWeather.spiralArmDistance(b, Math.hypot(radius, 1e-9), pitch, 0), 2e-9);
            }
        }
    }

    @Test
    void actualFlowJacobianPreservesPlanarAreaAndFitsTheFilteringBound() {
        for (double pitch : new double[] {1.8, 2.4}) {
            for (double radius : new double[] {0, .0001, .03, .12, .3, .7, 1.2, 1.8}) {
                for (int i = 0; i < 24; i++) {
                    double angle = Math.PI * 2 * i / 24, x = radius * Math.cos(angle), y = radius * Math.sin(angle);
                    double h = 1e-6;
                    var dx = EarthCloudWeather.flowCoordinates(x + h, y, 1, pitch)
                            .subtract(EarthCloudWeather.flowCoordinates(x - h, y, 1, pitch)).multiply(.5 / h);
                    var dy = EarthCloudWeather.flowCoordinates(x, y + h, 1, pitch)
                            .subtract(EarthCloudWeather.flowCoordinates(x, y - h, 1, pitch)).multiply(.5 / h);
                    assertEquals(.65 * .65, dx.x() * dy.y() - dx.y() * dy.x(), 2e-8,
                            "The planar twist must not fold or collapse occupied weather");
                    double a = dx.dot(dx), b = dx.dot(dy), c = dy.dot(dy);
                    double actual = Math.sqrt((a + c + Math.sqrt((a - c) * (a - c) + 4 * b * b)) * .5);
                    double bound = EarthCloudWeather.flowFootprintScale(radius, pitch);
                    assertTrue(actual <= bound + 2e-8, "The declared footprint must cover every tangent displacement");
                    assertTrue(bound <= .65 * (1 + pitch) + .35,
                            "The bound must stay finite rather than hiding a singular core with unlimited filtering");
                }
            }
        }
    }

    @Test
    void cloudBanksTaperTheirTopsWithoutLeavingGlobalLayerBounds() {
        var edge = EarthCloudWeather.tops(new EarthCloudWeather.Region(0, 0, 0));
        var head = EarthCloudWeather.tops(new EarthCloudWeather.Region(1, 1, 1));
        assertTrue(edge.x() < head.x() && edge.y() < head.y() && edge.z() < head.z());
        assertEquals(1.85, head.x(), 1e-12);
        assertEquals(4.2, head.y(), 1e-12);
        assertEquals(EarthCloudState.TOP_KM, head.z(), 1e-12);
        assertEquals(0, EarthCloudWeather.profile(1.3, edge.y(), 3, 3));
        assertTrue(EarthCloudWeather.profile(1.3, head.y(), 3, 3) > .9);
    }

    @Test
    void filteredCoveragePreservesClearSupportAndIntegratesOpticalMassContinuously() {
        for (double variance : new double[] {0, 1e-10, .0001, .002, .01}) {
            assertEquals(0, EarthCloudWeather.filteredMass(0, .5, variance));
            for (double mass : new double[] {.1, .4, .9}) {
                for (double field : new double[] {.25, .42, .5, .6, .75}) {
                    double spread = Math.sqrt(3 * variance), integral = 0;
                    int samples = 8192;
                    for (int i = 0; i < samples; i++) {
                        double point = field - spread + 2 * spread * (i + .5) / samples;
                        double threshold = .58 - mass * .20;
                        integral += mass * EarthCloudWeather.smooth(threshold - .12, threshold + .12, point) / samples;
                    }
                    double filtered = EarthCloudWeather.filteredMass(mass, field, variance);
                    assertEquals(integral, filtered, 3e-8, "Filter must average nonlinear coverage, not threshold its mean");
                    assertTrue(filtered >= 0 && filtered <= mass);
                }
            }
        }
        assertEquals(EarthCloudWeather.filteredMass(.4, .5, 0),
                EarthCloudWeather.filteredMass(.4, .5, 1e-10), 1e-8);
    }

    @Test
    void invalidProfileAndWeatherInputsAreRejected() {
        var state = EarthCloudState.sample(0, 0, .5f, 0, 0, 0, 1);
        var noise = new CloudNoiseField();
        assertThrows(IllegalArgumentException.class, () -> EarthCloudWeather.sample(noise, state, SpaceVector.ZERO));
        assertThrows(IllegalArgumentException.class, () -> EarthCloudWeather.sample(null, state, new SpaceVector(1, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> EarthCloudWeather.profile(0, Double.POSITIVE_INFINITY, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> EarthCloudWeather.profile(1, 2, 3, 2));
    }

    private static SpaceVector point(double latitude, double longitude) {
        return new SpaceVector(Math.cos(latitude) * Math.cos(longitude), Math.sin(latitude),
                Math.cos(latitude) * Math.sin(longitude)).multiply(6373);
    }
}
