package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarsSurfaceTest {
    @Test void explicitMaterialVersionPreservesEveryHeightFrameAndPermanentDimension() {
        var sol = CosmosGenerator.sol();
        var mars = sol.bodies().stream().filter(body -> body.id().equals("mars")).findFirst().orElseThrow();
        var current = SolidPlanetProfile.create(sol, mars).orElseThrow();
        var old = legacy(current);
        assertEquals(2, current.version()); assertEquals(3_389_500, current.radiusMeters());
        assertEquals(old.frame(sol, 23812), current.frame(sol, 23812));
        assertEquals(new PlanetChart(old, CubeFace.POSITIVE_X, 0).dimensionId(),
                new PlanetChart(current, CubeFace.POSITIVE_X, 0).dimensionId());
        assertNotEquals(old.geographyId(), current.geographyId());
        var a = new SolidPlanetTerrain(old); var b = new SolidPlanetTerrain(current);
        int rust = 0;
        for (int i = 0; i < 1000; i++) {
            double y = 1 - 2 * (i + .5) / 1000;
            double angle = i * Math.PI * (3 - Math.sqrt(5)), radius = Math.sqrt(1 - y * y);
            var direction = new SpaceVector(radius * Math.cos(angle), y, radius * Math.sin(angle));
            var oldSample = a.sample(direction); var newSample = b.sample(direction);
            assertEquals(oldSample.heightMeters(), newSample.heightMeters());
            assertEquals(oldSample.topMeters(), newSample.topMeters());
            assertTrue(oldSample.material() == SolidPlanetTerrain.Material.REGOLITH
                    || oldSample.material() == SolidPlanetTerrain.Material.ROCK);
            // Keep this sampling assertion outside the exact polar threshold, where normalization rounds by one ULP.
            if (Math.abs(y) < .984) {
                assertTrue(newSample.material() == SolidPlanetTerrain.Material.OXIDIZED_DUST
                        || newSample.material() == SolidPlanetTerrain.Material.OXIDIZED_ROCK);
                var color = SolidPlanetPalette.DEFAULT.color(newSample);
                assertTrue(color.x() > color.y() && color.y() > color.z()); rust++;
            } else if (Math.abs(y) > .986) {
                assertEquals(SolidPlanetTerrain.Material.ICE, newSample.material());
            }
        }
        assertTrue(rust > 980);
        for (String id : new String[] {"moon", "europa", "mercury", "venus"}) {
            assertEquals(1, SolidPlanetProfile.create(sol, sol.bodies().stream()
                    .filter(body -> body.id().equals(id)).findFirst().orElseThrow()).orElseThrow().version());
        }
    }

    @Test void dustIsWarmInDaylightAndBlueOnlyNearTheTwilightSun() {
        var daylight = MarsAtmosphere.fog(.6, 1, .13, 1);
        var oppositeTwilight = MarsAtmosphere.fog(0, -1, .13, 1);
        var solarTwilight = MarsAtmosphere.fog(0, 1, .13, 1);
        assertTrue(daylight.x() > daylight.y() && daylight.y() > daylight.z());
        assertTrue(oppositeTwilight.x() > oppositeTwilight.z());
        assertTrue(solarTwilight.z() > solarTwilight.x());
        assertEquals(SpaceVector.ZERO, MarsAtmosphere.fog(.6, 1, .13, 0));
        assertEquals(SpaceVector.ZERO, MarsAtmosphere.light(.6, .13, 0));
        assertEquals(MarsAtmosphere.light(.6, .13, 1).multiply(.01), MarsAtmosphere.light(.6, .13, .01));
        assertTrue(MarsAtmosphere.fog(.6, 0, .00001, 1).length() < daylight.length() * .001);
    }

    @Test void legacyMarsGetsTheSameThinDustPresentationWithoutChangingItsMaterials() {
        var sol = CosmosGenerator.sol();
        var profile = SolidPlanetProfile.create(sol, sol.bodies().stream().filter(body -> body.id().equals("mars"))
                .findFirst().orElseThrow()).orElseThrow();
        assertEquals(profile.atmosphereDensity(12000), legacy(profile).atmosphereDensity(12000));
        assertTrue(profile.atmosphereDensity(0) < .14);
        assertTrue(profile.atmosphereDensity(100000) < .00002);
        assertTrue(MarsAtmosphere.light(.6, profile.atmosphereDensity(0), 1).x()
                > MarsAtmosphere.light(.6, profile.atmosphereDensity(0), 1).z());
    }

    private static SolidPlanetProfile legacy(SolidPlanetProfile current) {
        return new SolidPlanetProfile(1, current.systemId(), current.bodyId(), current.seed(), current.radiusMeters(),
                current.kind(), current.rotationSeconds(), current.axialTiltRadians(), current.atmosphereStrength());
    }
}
