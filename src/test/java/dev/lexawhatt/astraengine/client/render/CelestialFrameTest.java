package dev.lexawhatt.astraengine.client.render;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CelestialFrameTest {
    @Test
    void beamExtentKeepsACompactPulsarVisibleWithoutInflatingItsPhysicalDisc() {
        var bodies = new ArrayList<CelestialBody>();
        bodies.add(body("primary", CelestialBody.Kind.STAR, 10, 0));
        for (int index = 0; index < 20; index++) {
            bodies.add(body("planet_" + index, CelestialBody.Kind.ROCKY, 100, 10000 + index));
        }
        bodies.add(body("pulsar", CelestialBody.Kind.PULSAR, 10, 20000));
        CosmosSystem system = new CosmosSystem("fixture", "Fixture", 1, CosmosSystem.Kind.SINGLE,
                SpaceVector.ZERO, bodies);
        var frame = CelestialFrame.extract(system, new SpaceVector(0, 0, 100), 0);
        var pulsar = frame.bodies().stream().filter(value -> value.descriptor().id().equals("pulsar"))
                .findFirst().orElseThrow();
        assertEquals(CelestialFrame.MAX_RENDERED_BODIES, frame.bodies().size());
        assertEquals(10 / Math.hypot(20000, 100), pulsar.radiusRatio(), 1e-10);
        assertEquals(48 * 10 / Math.hypot(20000, 100), pulsar.angularExtent(), 1e-15);
    }

    @Test
    void solarCatalogExceedsGpuBudgetButNearbySatellitesAndPrimaryRemainVisible() {
        CosmosSystem sol = CosmosGenerator.sol();
        for (String id : List.of("moon", "phobos", "europa", "titan", "triton")) {
            CelestialBody body = sol.bodies().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
            SpaceVector observer = sol.positionAt(body, 731).add(new SpaceVector(0, 0, body.radiusMeters() * 4));
            CelestialFrame frame = CelestialFrame.extract(sol, observer, 731);
            assertEquals(CelestialFrame.MAX_RENDERED_BODIES, frame.bodies().size());
            assertTrue(frame.bodies().stream().anyMatch(value -> value.descriptor().id().equals(id)));
            assertTrue(frame.bodies().stream().anyMatch(value -> value.descriptor().id().equals("sun")));
            for (int index = 1; index < frame.bodies().size(); index++) {
                assertTrue(frame.bodies().get(index - 1).distance() >= frame.bodies().get(index).distance());
            }
        }
        assertEquals(30, sol.bodies().size());
    }

    @Test
    void moonFrameSubtractsTheObserverAfterResolvingItsEarthRelativeOrbit() {
        CosmosSystem sol = CosmosGenerator.sol();
        CelestialBody moon = sol.bodies().get(9);
        SpaceVector center = sol.positionAt(moon, 731);
        SpaceVector observer = center.add(new SpaceVector(0, 0, 4 * moon.radiusMeters()));
        CelestialFrame frame = CelestialFrame.extract(sol, observer, 731);
        CelestialFrame.Body projected = frame.bodies().stream()
                .filter(value -> value.descriptor().id().equals("moon")).findFirst().orElseThrow();
        assertEquals(center, projected.position());
        assertEquals(4 * moon.radiusMeters(), projected.distance(), 0.0001);
        assertEquals(new SpaceVector(0, 0, -1), projected.direction());
        assertEquals(0.25f, projected.radiusRatio());
    }

    @Test
    void meterOffsetSurvivesAstronomicalOriginBeforeFloatConversion() {
        CelestialBody planet = body("planet", CelestialBody.Kind.ROCKY, 1, 1.0e15);
        SpaceVector position = planet.positionAt(0);
        CelestialFrame frame = CelestialFrame.extract(system(planet), position.add(new SpaceVector(3, 4, 0)), 0);
        CelestialFrame.Body projected = frame.bodies().getFirst();
        assertEquals(5, projected.distance());
        assertEquals(-0.6, projected.direction().x(), 1e-15);
        assertEquals(-0.8, projected.direction().y(), 1e-15);
        assertEquals(0.2f, projected.radiusRatio());
    }

    @Test
    void closestBlackHoleDefinesDepthUnitsAfterFarToNearSort() {
        CelestialBody far = body("far", CelestialBody.Kind.BLACK_HOLE, 10, 4000);
        CelestialBody near = body("near", CelestialBody.Kind.BLACK_HOLE, 10, 1000);
        CelestialBody planet = body("planet", CelestialBody.Kind.ROCKY, 10, 500);
        CelestialFrame frame = CelestialFrame.extract(system(planet, near, far), SpaceVector.ZERO, 0);
        assertEquals(List.of("far", "near", "planet"), frame.bodies().stream()
                .map(projected -> projected.descriptor().id()).toList());
        assertEquals(1, frame.lensIndex());
        assertEquals(4, frame.distanceRatio(0));
        assertEquals(1, frame.distanceRatio(1));
        assertEquals(0.5, frame.distanceRatio(2));
        assertThrows(UnsupportedOperationException.class, () -> frame.bodies().clear());
    }

    @Test
    void centerAndSubnormalOffsetsHaveFiniteDirectionsAndRatios() {
        CosmosSystem system = system(body("hole", CelestialBody.Kind.BLACK_HOLE, 1, 0));
        for (SpaceVector camera : List.of(SpaceVector.ZERO, new SpaceVector(Double.MIN_VALUE, 0, 0))) {
            CelestialFrame frame = CelestialFrame.extract(system, camera, 0);
            CelestialFrame.Body projected = frame.bodies().getFirst();
            assertEquals(1, projected.direction().length(), 1e-15);
            assertEquals(1000, projected.radiusRatio());
            assertEquals(1, frame.distanceRatio(0));
        }
    }

    @Test
    void healthySolRetainsPhysicalAngularRadiusWithoutALens() {
        SpaceVector camera = new SpaceVector(0, 0, CosmosGenerator.AU);
        CelestialFrame frame = CelestialFrame.extract(CosmosGenerator.sol(), camera, 0);
        assertEquals(-1, frame.lensIndex());
        for (int i = 0; i < frame.bodies().size(); i++) {
            assertEquals(1, frame.distanceRatio(i));
            CelestialFrame.Body projected = frame.bodies().get(i);
            if (projected.descriptor().id().equals("sun")) {
                double diameter = Math.toDegrees(2 * Math.asin(projected.radiusRatio()));
                assertEquals(0.5329, diameter, 0.0001);
            }
        }
    }

    @Test
    void distantCameraKeepsTinyPhysicalDiscsAndAllDepthInputsFinite() {
        CelestialFrame frame = CelestialFrame.extract(system(body("hole", CelestialBody.Kind.BLACK_HOLE, 1, 0),
                body("planet", CelestialBody.Kind.ROCKY, 1, 1.0e15)), new SpaceVector(0, 0, 6.0e14), 0);
        for (int i = 0; i < frame.bodies().size(); i++) {
            assertTrue(frame.bodies().get(i).radiusRatio() < 1e-14);
            assertTrue(Float.isFinite(frame.distanceRatio(i)));
            assertTrue(frame.distanceRatio(i) > 0);
        }
    }

    @Test
    void invalidProjectionInputsAreRejectedBeforeShaderUpload() {
        CosmosSystem system = CosmosGenerator.sol();
        assertThrows(IllegalArgumentException.class, () -> CelestialFrame.extract(null, SpaceVector.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> CelestialFrame.extract(system, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> CelestialFrame.extract(system, SpaceVector.ZERO, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> CelestialFrame.extract(system,
                new SpaceVector(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 0));
    }

    private static CelestialBody body(String id, CelestialBody.Kind kind, double radius, double orbit) {
        return new CelestialBody(id, id, kind, radius, orbit, orbit == 0 ? 0 : 1000,
                0, 0, 0, new SpaceVector(1, 1, 1), 0, 0, 0, 0);
    }

    private static CosmosSystem system(CelestialBody... bodies) {
        return new CosmosSystem("fixture", "Fixture", 1, CosmosSystem.Kind.BLACK_HOLE, SpaceVector.ZERO,
                List.of(bodies));
    }
}
