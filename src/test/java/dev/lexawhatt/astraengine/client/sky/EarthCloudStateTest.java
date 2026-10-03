package dev.lexawhatt.astraengine.client.sky;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthChart;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EarthCloudStateTest {
    @Test
    void oneBodyFixedFieldSurvivesFaceAndBandRepresentations() {
        var noise = new CloudNoiseField();
        var state = EarthCloudState.sample(19471, .25, .65f, .24, .3f, 0, 1);
        for (var normal : new SpaceVector[] {new SpaceVector(1, .2, 1).normalized(),
                new SpaceVector(-1, 1, .7).normalized(), new SpaceVector(.3, -1, -1).normalized()}) {
            var reference = normal.multiply(EarthChart.RADIUS_METERS + 2200).multiply(.001);
            double density = state.density(noise, reference, 6371);
            for (CubeFace face : CubeFace.values()) {
                if (normal.dot(face.outward()) <= .4) { continue; }
                double scale = EarthChart.RADIUS_METERS / normal.dot(face.outward());
                for (int band : new int[] {0, 1, 15}) {
                    var chart = new EarthChart(face, band);
                    double hostY = 2200 - chart.altitudeOriginMeters();
                    var point = chart.tangentFrame(normal.dot(face.u()) * scale, normal.dot(face.v()) * scale,
                            hostY + chart.altitudeOriginMeters()).originMeters().multiply(.001);
                    assertTrue(point.subtract(reference).length() < 2e-12);
                    assertEquals(density, state.density(noise, point, 6371), 2e-10);
                    assertTrue(state.advected(point).subtract(state.advected(reference)).length() < 2e-12);
                }
            }
        }
    }

    @Test
    void windUsesOnlyHostSnapshotAndWrapsContinuously() {
        var frozen = EarthCloudState.sample(5000, 0, .55f, .25, .4f, .1f, 1);
        assertEquals(frozen, EarthCloudState.sample(5000, 0, .55f, .25, .4f, .1f, 1));
        assertEquals(frozen, EarthCloudState.sample(5000 + EarthCloudState.WIND_PERIOD_TICKS, 0,
                .55f, .25, .4f, .1f, 1));
        var end = EarthCloudState.sample(EarthCloudState.WIND_PERIOD_TICKS - 1, .999999, .55f, .25, 0, 0, 1);
        var start = EarthCloudState.sample(0, 0, .55f, .25, 0, 0, 1);
        assertEquals(start.windX(), end.windX(), 1e-8);
        assertEquals(start.windZ(), end.windZ(), 1e-8);
    }

    @Test
    void disabledCloudsAndLayerLimitsHaveExactZeroDensityWhileWeatherKeepsLatitude() {
        var noise = new CloudNoiseField();
        var off = EarthCloudState.sample(0, 0, 0, .25, 1, 1, 0);
        var automatic = EarthCloudState.sample(0, 0, -1, .25, .2f, 0, 1);
        var unlit = EarthCloudState.sample(0, 0, -1, .25, .2f, 0, 0);
        assertTrue(automatic.coverage(new SpaceVector(0, -6373, 0))
                > automatic.coverage(new SpaceVector(0, 6373, 0)));
        double maximum = 0;
        for (int i = 0; i < 1000; i++) {
            var point = new SpaceVector(1, Math.sin(i * .3), Math.cos(i * .3)).normalized().multiply(6373.2);
            assertEquals(0, off.density(noise, point, 6371));
            maximum = Math.max(maximum, automatic.density(noise, point, 6371));
            assertEquals(automatic.density(noise, point, 6371), unlit.density(noise, point, 6371),
                    "An absent star removes reflected radiance, not physical cloud occlusion");
            assertEquals(0, automatic.density(noise, point.normalized().multiply(6371.5), 6371));
            assertEquals(0, automatic.density(noise, point.normalized().multiply(6380), 6371));
        }
        assertTrue(maximum > .05, "Cloud field must contain actual billows, not only an empty identity path");
    }
}
