package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubeChartRebaseTest {
    @Test void adjacentAltitudeBandsPreserveOneUlpCrossingsAtEverySupportedHeight() {
        var system = CosmosGenerator.sol();
        var moon = SolidPlanetProfile.create(system, system.bodies().stream()
                .filter(body -> body.id().equals("moon")).findFirst().orElseThrow()).orElseThrow();
        for (var face : CubeFace.values()) {
            for (int band = PlanetChart.MIN_BAND; band <= PlanetChart.MAX_BAND; band++) {
                for (var source : new CubeStorageChart[] {new EarthChart(face, band, 3), new PlanetChart(moon, face, band)}) {
                    if (band > PlanetChart.MIN_BAND) { crossing(source, Math.nextDown((double) source.minY()), -1); }
                    if (band < PlanetChart.MAX_BAND) { crossing(source, source.minY() + source.height(), 1); }
                }
            }
        }
    }

    @Test void unsupportedCoreAndOuterStorageExtensionsRefuseWithoutAnInvalidPose() {
        var profile = new SolidPlanetProfile(1, "verify:tiny", "rock", 7, 16,
                CelestialBody.Kind.ROCKY, 40000, .1, 0);
        var tiny = new PlanetChart(profile, CubeFace.POSITIVE_X, 0);
        for (double y : new double[] {Math.nextDown(-15.0), -16, -17, -100}) {
            assertTrue(CubeChartRebase.resolve(tiny, new SpaceVector(0, y, 0), SpaceVector.ZERO,
                    FlightOrientation.IDENTITY).isEmpty());
        }
        var upper = new EarthChart(CubeFace.POSITIVE_X, EarthChart.MAX_BAND, 3);
        assertTrue(CubeChartRebase.resolve(upper, new SpaceVector(0, upper.minY() + upper.height(), 0),
                SpaceVector.ZERO, FlightOrientation.IDENTITY).isEmpty());
    }

    private static void crossing(CubeStorageChart source, double y, int shift) {
        // The lunar descent crash reached exactly this horizontal pose and a sub-ULP lower band edge.
        var feet = new SpaceVector(595650.4520459474, y, -189326.40433837482);
        var velocity = new SpaceVector(0, 8 * shift, -2.9103830456733704e-11);
        var view = FlightOrientation.fromAngles(176.5041, 90, 37);
        var result = CubeChartRebase.resolve(source, feet, velocity, view).orElseThrow();
        assertEquals(source.band() + shift, result.chart().band());
        assertEquals(source.face(), result.chart().face());
        assertTrue(result.chart().contains(result.feet()));
        assertEquals(feet.x(), result.feet().x());
        assertEquals(feet.z(), result.feet().z());
        assertEquals(y + source.height() * -shift, result.feet().y());
        var reversed = new EarthChartTransform(result.chart(), source).position(result.feet());
        assertEquals(feet.x(), reversed.x());
        assertEquals(feet.z(), reversed.z());
        assertEquals(y, reversed.y(), Math.ulp(y));
        assertEquals(velocity.y(), result.velocity().y());
        assertTrue(view.forward().dot(result.orientation().forward()) > .999999999);
        assertTrue(view.up().dot(result.orientation().up()) > .999999999);
    }
}
