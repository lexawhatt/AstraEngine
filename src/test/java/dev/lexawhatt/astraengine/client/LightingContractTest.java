package dev.lexawhatt.astraengine.client;

import com.google.gson.JsonParser;
import dev.lexawhatt.astraengine.client.environment.EnvironmentProfile;
import dev.lexawhatt.astraengine.client.lighting.LightCollector;
import dev.lexawhatt.astraengine.client.lighting.LightVector;
import dev.lexawhatt.astraengine.client.lighting.SceneLight;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightingContractTest {
    private static final LightVector ORIGIN = new LightVector(0, 0, 0);
    private static final LightVector WHITE = new LightVector(1, 1, 1);
    private static final LightVector FORWARD = new LightVector(0, 0, 1);

    @Test
    void rejectsInvalidGpuInputs() {
        assertThrows(IllegalArgumentException.class, () -> new LightVector(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ORIGIN.normalized());
        assertThrows(IllegalArgumentException.class, () -> SceneLight.point("test", ORIGIN, WHITE, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> SceneLight.point("test", ORIGIN, WHITE, 1, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> SceneLight.point("test", ORIGIN, new LightVector(10, 1, 1), 1, 20));
    }

    @Test
    void selectsRelevantLightsWithinBudgetAndSealsTheFrame() {
        var collector = new LightCollector(ORIGIN, 2);
        collector.add(SceneLight.point("far", new LightVector(9, 0, 0), WHITE, 1, 10));
        collector.add(SceneLight.point("near", new LightVector(1, 0, 0), WHITE, 1, 10));
        collector.add(SceneLight.directional("sun", FORWARD, WHITE, 1));
        assertFalse(collector.add(SceneLight.point("outside", new LightVector(100, 0, 0), WHITE, 10, 10)));
        assertFalse(collector.add(SceneLight.point("near", ORIGIN, WHITE, 10, 10)));
        var lights = collector.seal();
        assertEquals(2, lights.size());
        assertEquals("sun", lights.getFirst().id());
        assertEquals("near", lights.getLast().id());
        assertThrows(UnsupportedOperationException.class, lights::clear);
        assertThrows(IllegalStateException.class, () -> collector.add(SceneLight.point("late", ORIGIN, WHITE, 1, 10)));
    }

    @Test
    void lightCanIlluminateVisibleGeometryWithoutReachingTheCamera() {
        var collector = new LightCollector(ORIGIN, 2, 96);
        assertTrue(collector.add(SceneLight.point("distant_wall", new LightVector(40, 0, 0), WHITE, 2, 8)));
        assertEquals(1, collector.seal().size());
    }

    @Test
    void boundsSubmissionWorkAndResolvesTiesDeterministically() {
        var collector = new LightCollector(ORIGIN, 1);
        collector.add(SceneLight.point("z", ORIGIN, WHITE, 1, 10));
        collector.add(SceneLight.point("a", ORIGIN, WHITE, 1, 10));
        for (int i = 2; i < LightCollector.MAX_SUBMISSIONS; i++) {
            collector.add(SceneLight.point("z" + i, ORIGIN, WHITE, 1, 10));
        }
        assertFalse(collector.add(SceneLight.point("brighter", ORIGIN, WHITE, 10, 10)));
        assertEquals("a", collector.seal().getFirst().id());
    }

    @Test
    void coneHasFullCenterSmoothPenumbraAndNoRearLighting() {
        var spot = new SceneLight("spot", SceneLight.Kind.SPOT, ORIGIN, FORWARD, WHITE, 2, 20, 10, 25, false);
        assertEquals(1, spot.coneWeight(1));
        assertEquals(0, spot.coneWeight(-1));
        double edge = spot.coneWeight(Math.cos(Math.toRadians(18)));
        assertTrue(edge > 0 && edge < 1);
        assertThrows(IllegalArgumentException.class,
                () -> new SceneLight("bad", SceneLight.Kind.SPOT, ORIGIN, FORWARD, WHITE, 1, 20, 25, 10, false));
    }

    @Test
    void sunTracksMinecraftTimeWithStableCycles() {
        var planet = EnvironmentProfile.PLANET;
        assertTrue(planet.sunDirection(6000).y() > 0.9);
        assertTrue(planet.sunDirection(18000).y() < -0.9);
        assertEquals(planet.sunDirection(6000), planet.sunDirection(30000));
        assertEquals(EnvironmentProfile.SPACE.sunDirection(0), EnvironmentProfile.SPACE.sunDirection(6000));
    }

    @Test
    void profileRejectsBadVersionsTypesAndUnboundedValues() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":1,\"bloom\":-1}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":1,\"planetary\":\"yes\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":1,\"exposure\":\"NaN\"}"));
        assertEquals(EnvironmentProfile.PLANET, parse("{\"version\":1,\"planetary\":true}"));
    }

    private EnvironmentProfile parse(String json) {
        return EnvironmentProfile.parse(JsonParser.parseString(json).getAsJsonObject());
    }
}
