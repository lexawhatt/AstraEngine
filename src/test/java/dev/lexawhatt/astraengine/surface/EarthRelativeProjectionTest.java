package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarthRelativeProjectionTest {
    @Test void sectionFloatsPreserveSubMillimeterGeometryAtEveryFaceAndBandEdge() {
        int checked = 0;
        double radius = EarthChart.RADIUS_METERS;
        for (EarthChart source : EarthChart.all(2)) {
            for (double x : new double[] {-radius + 8, 0, radius - 8}) {
                for (double z : new double[] {-radius + 8, 0, radius - 8}) {
                    var origin = new SpaceVector(x, 2016, z);
                    for (CubeFace face : CubeFace.values()) {
                        if (source.normal(x, z).dot(face.outward()) <= .2) { continue; }
                        var target = new EarthChart(face, Math.min(3, source.band() + 1), 2);
                        var transform = new EarthChartTransform(source, target);
                        var camera = transform.position(origin).add(new SpaceVector(15, -4, -17));
                        var projection = EarthRelativeProjection.between(source, origin, target, camera);
                        for (double lx : new double[] {0, 1.5, 16}) {
                            for (double lz : new double[] {0, 1.5, 16}) {
                                var local = new SpaceVector(lx, 7, lz);
                                var exact = transform.position(origin.add(local)).subtract(camera);
                                assertTrue(projection.position(local).distance(exact) < 1e-7);
                                float denominator = shaderDot(projection.denominator(), local);
                                var gpu = new SpaceVector(shaderDot(projection.xNumerator(), local) / denominator,
                                        (float) projection.yOffset() + 7, shaderDot(projection.zNumerator(), local) / denominator);
                                assertTrue(gpu.distance(exact) < .0001, "Chart coefficients lost local precision: " + gpu.distance(exact));
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(checked > 5000);
    }
    private static float shaderDot(SpaceVector coefficients, SpaceVector local) {
        return (float) coefficients.x() + (float) coefficients.y() * (float) local.x()
                + (float) coefficients.z() * (float) local.z();
    }
}
