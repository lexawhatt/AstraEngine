package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceScaleTest {
    @Test
    void humanCameraAndBlockMetersKeepCanonicalPlanetScale() {
        for (String body : new String[]{"earth", "moon"}) {
            SurfacePatch patch = SurfaceDefinition.byBody(body).patch();
            double radius = body.equals("earth") ? 6_371_000 : 1_737_400;
            assertEquals(radius, patch.radiusMeters());
            SpaceVector ground = patch.toBody(new SpaceVector(0, patch.seaY(), 0));
            SpaceVector eye = patch.toBody(new SpaceVector(0, patch.seaY() + 1.62, 0));
            SpaceVector oneBlockEast = patch.toBody(new SpaceVector(1, patch.seaY(), 0));
            assertEquals(radius, ground.length(), 1e-8);
            assertEquals(1.62, eye.length() - radius, 1e-8);
            assertEquals(1, oneBlockEast.distance(ground), 1e-8);
            assertTrue(Math.sqrt(1.62 * (2 * radius + 1.62)) > 2_000,
                    "Human-height geometric horizon must remain kilometers away");
        }
    }
}
