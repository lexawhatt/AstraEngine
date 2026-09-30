package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceGeographyTest {
    @Test
    void fixedPatchesContainVisibleReliefAndEarthContainsBothDryLandAndOcean() {
        for (String body : new String[]{"moon", "earth"}) {
            SurfaceDefinition definition = SurfaceDefinition.byBody(body);
            double min = 1000, max = -1000;
            int oceans = 0, columns = 0;
            for (int x = -2040; x <= 2040; x += 32) {
                for (int z = -2040; z <= 2040; z += 32) {
                    SurfaceGeography.Sample sample = definition.geography().sample(definition.patch().normal(x + .5, z + .5));
                    min = Math.min(min, sample.heightMeters());
                    max = Math.max(max, sample.heightMeters());
                    if (sample.ocean()) { oceans++; }
                    columns++;
                    assertTrue(sample.heightMeters() >= -48 && sample.heightMeters() <= 112);
                    assertEquals((int) Math.floor(64 + sample.heightMeters()), definition.terrainY(x + .5, z + .5));
                }
            }
            assertTrue(max - min > (body.equals("moon") ? 40 : 20), body + " needs visible patch relief");
            if (body.equals("earth")) {
                assertTrue(oceans > columns * .3 && oceans < columns * .6, "Fixed Earth coast must expose land and ocean");
            } else {
                assertEquals(0, oceans);
            }
            assertFalse(definition.geography().sample(definition.patch().normal(.5, .5)).ocean());
        }
    }

    @Test
    void versionOneGoldenSamplesPinSavedChunkAndShaderMirrorGeography() {
        SpaceVector[] directions = {new SpaceVector(1, 0, 0), new SpaceVector(0, 1, 0),
                new SpaceVector(0, 0, 1), new SpaceVector(1, 2, -3)};
        double[][] expected = {{1.3091201782226562, 26.8975772857666, 1.459272861480713, 20.83668327331543},
                {8.991991996765137, 112, 32.47537612915039, 112}};
        String[] ids = {"moon", "earth"};
        for (int body = 0; body < ids.length; body++) {
            SurfaceGeography geography = SurfaceDefinition.byBody(ids[body]).geography();
            for (int direction = 0; direction < directions.length; direction++) {
                assertEquals(expected[body][direction], geography.sample(directions[direction]).heightMeters());
                assertEquals(geography.sample(directions[direction]), geography.sample(directions[direction].multiply(10)));
            }
        }
    }

    @Test
    void sphereSamplerHasNoLongitudeSeamAndKeepsGlobalBounds() {
        for (String id : new String[]{"moon", "earth"}) {
            SurfaceGeography geography = SurfaceDefinition.byBody(id).geography();
            for (int i = 0; i < 1000; i++) {
                double y = -1 + 2 * (i + .5) / 1000;
                double radius = Math.sqrt(1 - y * y), angle = i * 2.399963229728653;
                var sample = geography.sample(new SpaceVector(radius * Math.cos(angle), y, radius * Math.sin(angle)));
                assertTrue(sample.heightMeters() >= -48 && sample.heightMeters() <= 112);
            }
            double first = geography.sample(new SpaceVector(-1, 0, 1e-10)).heightMeters();
            double second = geography.sample(new SpaceVector(-1, 0, -1e-10)).heightMeters();
            assertEquals(first, second, 0.001);
        }
        assertThrows(IllegalArgumentException.class, () -> new SurfaceGeography(2, 1, SurfaceGeography.Kind.MOON));
        assertThrows(IllegalArgumentException.class, () -> SurfaceDefinition.byBody("moon").geography().sample(SpaceVector.ZERO));
    }
}
