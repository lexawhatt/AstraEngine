package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolidPlanetProfileTest {
    @Test void emptyAirShortcutUsesAConservativeBoundForEverySolidFamilyAndRadius() {
        for (var kind : new CelestialBody.Kind[] {CelestialBody.Kind.ROCKY, CelestialBody.Kind.ICE, CelestialBody.Kind.OCEAN}) {
            for (double radius : new double[] {16, 1_737_400, 6_371_000, SolidPlanetProfile.MAX_RADIUS_METERS}) {
                for (long seed : new long[] {0, -3, Long.MAX_VALUE}) {
                    var profile = new SolidPlanetProfile(1, "verify:bounds", "body", seed, radius, kind, 86400, .4, 1);
                    var terrain = new SolidPlanetTerrain(profile);
                    for (int index = 0; index < 576; index++) {
                        double y = 1 - 2 * (index + .5) / 576;
                        double angle = index * Math.PI * (3 - Math.sqrt(5)), length = Math.sqrt(1 - y * y);
                        var sample = terrain.sample(new SpaceVector(length * Math.cos(angle), y, length * Math.sin(angle)));
                        assertTrue(sample.topMeters() <= terrain.maximumHeightMeters(),
                                kind + " upper air bound would erase an actual terrain column");
                        assertTrue(sample.heightMeters() >= -radius * .2);
                    }
                }
            }
        }
    }

    @Test void tinySolidCoreHasNoWritableOrTraversableNegativeRadiusStorage() {
        var profile = new SolidPlanetProfile(1, "verify:tiny", "rock", 7, 16,
                CelestialBody.Kind.ROCKY, 40000, .1, 0);
        var chart = new PlanetChart(profile, CubeFace.POSITIVE_X, 0);
        assertEquals(-15, chart.coreFloorY());
        assertTrue(chart.contains(new SpaceVector(0, -15, 0)));
        assertFalse(chart.contains(new SpaceVector(0, Math.nextDown(-15.0), 0)));
        assertTrue(chart.ownerChart(new GeographicPosition(0, 0, -15)).isPresent());
        assertTrue(chart.ownerChart(new GeographicPosition(0, 0, Math.nextDown(-15.0))).isEmpty());
        assertTrue(chart.chart(CubeFace.POSITIVE_Z, -1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new PlanetChart(profile, CubeFace.POSITIVE_X, -1));
        assertEquals(1, chart.pose(new SpaceVector(0, -15, 0), SpaceVector.ZERO,
                FlightOrientation.IDENTITY).bodyPositionMeters().length(), 1e-12);
    }

    @Test void solidsRetainRadiusAndDistinctIdentityWhileGiantsNeverGetFloors() {
        var sol = CosmosGenerator.sol();
        var moon = sol.bodies().stream().filter(body -> body.id().equals("moon")).findFirst().orElseThrow();
        var profile = SolidPlanetProfile.create(sol, moon).orElseThrow();
        assertEquals(moon.radiusMeters(), profile.radiusMeters());
        assertEquals(moon.orbitalPeriodSeconds(), profile.rotationSeconds());
        assertEquals(0, profile.atmosphereDensity(0));
        assertEquals(64, profile.bindingKey().length());
        assertEquals(profile, SolidPlanetProfile.create(sol, moon).orElseThrow());
        for (var body : sol.bodies()) {
            if (body.kind() == CelestialBody.Kind.GAS_GIANT || body.kind() == CelestialBody.Kind.STAR
                    || body.id().equals("uranus") || body.id().equals("neptune")) {
                assertTrue(SolidPlanetProfile.create(sol, body).isEmpty(), body.id());
            }
        }
        var europa = sol.bodies().stream().filter(body -> body.id().equals("europa")).findFirst().orElseThrow();
        var ice = SolidPlanetProfile.create(sol, europa).orElseThrow();
        assertNotEquals(profile.geographyId(), ice.geographyId());
        assertNotEquals(new PlanetChart(profile, CubeFace.POSITIVE_X, 0).dimensionId(),
                new PlanetChart(ice, CubeFace.POSITIVE_X, 0).dimensionId());
        assertThrows(IllegalArgumentException.class, () -> profile.atmosphereDensity(Double.NaN));
    }

    @Test void chartRoundTripsWholeBodiesAcrossPolesAndPhysicalAltitudeBands() {
        var sol = CosmosGenerator.sol();
        var random = new Random(719882L);
        for (String id : new String[] {"moon", "europa", "mars"}) {
            var profile = SolidPlanetProfile.create(sol, sol.bodies().stream().filter(body -> body.id().equals(id))
                    .findFirst().orElseThrow()).orElseThrow();
            for (int index = 0; index < 3000; index++) {
                var address = new GeographicPosition(Math.asin(random.nextDouble() * 2 - 1),
                        random.nextDouble() * Math.PI * 2 - Math.PI, -9000 + random.nextDouble() * 109000);
                var chart = PlanetChart.owner(profile, address).orElseThrow();
                var feet = chart.resolve(address).orElseThrow();
                assertTrue(chart.contains(feet));
                assertEquals(address.altitudeMeters(), chart.geographic(feet).altitudeMeters(), 1e-11);
                assertTrue(address.normal().distance(chart.geographic(feet).normal()) < 2e-15);
                var pose = chart.pose(feet, new SpaceVector(.01, .03, -.02), FlightOrientation.IDENTITY);
                assertTrue(pose.bodyPositionMeters().distance(address.toBody(profile.radiusMeters())) < 2e-8);
            }
            for (int band = PlanetChart.MIN_BAND + 1; band <= PlanetChart.MAX_BAND; band++) {
                double boundary = PlanetChart.MIN_Y + band * PlanetChart.HEIGHT;
                assertEquals(band, PlanetChart.owner(profile, new GeographicPosition(Math.PI / 2, 0, boundary)).orElseThrow().band());
                assertEquals(band - 1, PlanetChart.owner(profile,
                        new GeographicPosition(-Math.PI / 2, 0, Math.nextDown(boundary))).orElseThrow().band());
            }
        }
    }

    @Test void globalTerrainIsContinuousAtSeamsDistinctAndWaterPolicyIsExplicit() {
        var sol = CosmosGenerator.sol();
        double[] hashes = new double[3];
        int ordinal = 0;
        for (String id : new String[] {"moon", "europa", "mars"}) {
            var profile = SolidPlanetProfile.create(sol, sol.bodies().stream().filter(body -> body.id().equals(id))
                    .findFirst().orElseThrow()).orElseThrow();
            var terrain = new SolidPlanetTerrain(profile);
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < 2000; index++) {
                double y = 1 - 2 * (index + .5) / 2000;
                double angle = index * Math.PI * (3 - Math.sqrt(5)), length = Math.sqrt(1 - y * y);
                var direction = new SpaceVector(length * Math.cos(angle), y, length * Math.sin(angle));
                var sample = terrain.sample(direction);
                min = Math.min(min, sample.heightMeters()); max = Math.max(max, sample.heightMeters());
                assertFalse(sample.water());
                hashes[ordinal] += sample.heightMeters();
            }
            assertTrue(max - min > 300, id + " must have substantial regional relief");
            for (var direction : new SpaceVector[] {new SpaceVector(1, 1, 1), new SpaceVector(-1, 0, 0), new SpaceVector(0, 1, 0)}) {
                var a = terrain.sample(direction);
                var b = terrain.sample(direction.add(new SpaceVector(1e-11, -1e-11, 1e-11)));
                assertEquals(a.heightMeters(), b.heightMeters(), .001);
            }
            ordinal++;
        }
        assertNotEquals(hashes[0], hashes[1]); assertNotEquals(hashes[0], hashes[2]);
    }
}
