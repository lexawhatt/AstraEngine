package dev.lexawhatt.astraengine.api.celestial;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CelestialBuildersTest {
    @Test
    void bodyBuildersPreservePhysicalUnitsAndAllExistingMaterials() {
        CelestialBody planet = CelestialBodies.planet("eden", "Eden", CelestialBody.Kind.OCEAN, 6_371_000)
                .orbit(CosmosGenerator.AU, 365.256 * 86_400).phaseRadians(0.75)
                .inclinationRadians(-0.2).eccentricity(0.0167).color(0.13, 0.4, 0.8)
                .atmosphere(0.7f).rings(1.4f, 2.8f).axialTiltRadians(0.4).build();
        assertEquals(new CelestialBody("eden", "Eden", CelestialBody.Kind.OCEAN, 6_371_000,
                CosmosGenerator.AU, 365.256 * 86_400, 0.75, -0.2, 0.0167,
                new SpaceVector(0.13, 0.4, 0.8), 0.7f, 1.4f, 2.8f, 0.4), planet);
        EnumSet<CelestialBody.Kind> materials = EnumSet.noneOf(CelestialBody.Kind.class);
        materials.add(CelestialBodies.star("sun", "Sun", 695_700_000).build().kind());
        materials.add(CelestialBodies.blackHole("hole", "Hole", 50_000).build().kind());
        for (CelestialBody.Kind kind : List.of(CelestialBody.Kind.ROCKY, CelestialBody.Kind.OCEAN,
                CelestialBody.Kind.GAS_GIANT, CelestialBody.Kind.ICE)) {
            materials.add(CelestialBodies.planet("world", "World", kind, 1_000).build().kind());
        }
        assertEquals(EnumSet.allOf(CelestialBody.Kind.class), materials);
        assertThrows(IllegalArgumentException.class,
                () -> CelestialBodies.planet("bad", "Bad", CelestialBody.Kind.STAR, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> CelestialBodies.planet("bad", "Bad", CelestialBody.Kind.BLACK_HOLE, 1_000));
        assertThrows(IllegalArgumentException.class, () -> CelestialBodies.planet("bad", "Bad", null, 1_000));
    }

    @Test
    void completedBodyAndSystemDescriptorsDoNotChangeWithTheirBuilders() {
        CelestialBodies.Builder bodyDraft = CelestialBodies.star("primary", "Primary", 100_000);
        CelestialBody firstBody = bodyDraft.build();
        CelestialBody secondBody = bodyDraft.color(0.4, 0.5, 0.6).build();
        assertEquals(new SpaceVector(1, 1, 1), firstBody.color());
        assertEquals(new SpaceVector(0.4, 0.5, 0.6), secondBody.color());

        CelestialSystems.Builder systemDraft = CelestialSystems.builder("consumer:eden", "Eden")
                .kind(CosmosSystem.Kind.BINARY).seed(Long.MIN_VALUE).galaxyPositionLightYears(4.5, -2, 7)
                .body(firstBody);
        CosmosSystem first = systemDraft.build();
        CelestialBody planet = CelestialBodies.planet("planet", "Planet", CelestialBody.Kind.ROCKY, 1_000)
                .orbit(1_000_000, 86_400).build();
        CosmosSystem second = systemDraft.body(planet).seed(17).build();
        assertEquals(List.of(firstBody), first.bodies());
        assertEquals(List.of(firstBody, planet), second.bodies());
        assertEquals(Long.MIN_VALUE, first.seed());
        assertEquals(17, second.seed());
        assertEquals(new SpaceVector(4.5, -2, 7), first.galaxyPosition());
        assertEquals(CosmosSystem.Kind.BINARY, first.kind());
        assertThrows(UnsupportedOperationException.class, () -> first.bodies().clear());
        assertEquals(first, systemDraftFor(first).build());
    }

    @Test
    void bodyDraftsRejectUnsupportedNumericAndIdentityData() {
        for (double radius : new double[] {0, -1, 1e12 + 1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> CelestialBodies.star("star", "Star", radius).build());
        }
        assertThrows(IllegalArgumentException.class, () -> CelestialBodies.star(null, "Star", 1_000).build());
        assertThrows(IllegalArgumentException.class, () -> CelestialBodies.star("planet/path", "Star", 1_000).build());
        assertThrows(IllegalArgumentException.class, () -> CelestialBodies.star("planet", " ", 1_000).build());
        assertThrows(IllegalArgumentException.class, () -> planetDraft().orbit(1e8, 0).build());
        assertThrows(IllegalArgumentException.class, () -> planetDraft().orbit(0, 86_400).build());
        assertThrows(IllegalArgumentException.class, () -> planetDraft().orbit(1_000, 86_400).build());
        assertThrows(IllegalArgumentException.class, () -> planetDraft().eccentricity(0.31).build());
        assertThrows(IllegalArgumentException.class, () -> planetDraft().phaseRadians(7).build());
        assertThrows(IllegalArgumentException.class, () -> planetDraft().inclinationRadians(4).build());
        assertThrows(IllegalArgumentException.class, () -> planetDraft().axialTiltRadians(Double.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> planetDraft().atmosphere(Float.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> planetDraft().rings(0.9f, 2).build());
        assertThrows(IllegalArgumentException.class, () -> planetDraft().rings(2, 1.5f).build());
        assertThrows(IllegalArgumentException.class, () -> planetDraft().color(1.01, 0, 0).build());
        assertThrows(IllegalArgumentException.class, () -> planetDraft().color(Double.NaN, 0, 0).build());
    }

    @Test
    void systemBuildersRejectReservedIdsDuplicatesMissingBodiesAndExcessCapacity() {
        CelestialBody primary = CelestialBodies.star("primary", "Primary", 100_000).build();
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.builder("sol", "Replacement")
                .body(primary).build());
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.builder("s_1_2_3", "Replacement")
                .body(primary).build());
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.builder("consumer:empty", "Empty").build());
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.builder("consumer:duplicate", "Duplicate")
                .body(primary).body(primary).build());
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.builder("consumer:null", "Null").body(null));
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.builder("consumer:null", "Null")
                .kind(null).body(primary).build());
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.validateCustom(null));
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.validateCustom(CosmosGenerator.sol()));
        CelestialSystems.Builder full = CelestialSystems.builder("consumer:full", "Full");
        for (int index = 0; index < CelestialSystems.MAX_BODIES; index++) {
            full.body(CelestialBodies.star("body_" + index, "Body " + index, 1_000).build());
        }
        assertEquals(12, full.build().bodies().size());
        assertThrows(IllegalArgumentException.class, () -> full.body(primary));
        assertEquals(12, full.build().bodies().size());
    }

    @Test
    void customGalaxyBoundsAreFiniteAndPerAxisWithoutChangingSol() {
        CelestialBody primary = CelestialBodies.star("primary", "Primary", 1_000).build();
        CosmosSystem system = CelestialSystems.builder("consumer:edge", "Edge")
                .galaxyPositionLightYears(1e6, -1e6, 1e6).body(primary).build();
        assertEquals(new SpaceVector(1e6, -1e6, 1e6), system.galaxyPosition());
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.builder("consumer:far", "Far")
                .galaxyPositionLightYears(0, 1e6 + 1, 0).body(primary).build());
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.builder("consumer:far", "Far")
                .galaxyPositionLightYears(Double.POSITIVE_INFINITY, 0, 0));
        assertEquals(695_700_000, CosmosGenerator.sol().bodies().getFirst().radiusMeters());
        assertEquals(9, CosmosGenerator.sol().bodies().size());
    }

    @Test
    void apoapsisAndEachObservationConventionMustFitTheFlightBoundary() {
        assertObservationBound(CosmosSystem.Kind.SINGLE, CelestialBody.Kind.ROCKY, false, 4);
        assertObservationBound(CosmosSystem.Kind.SINGLE, CelestialBody.Kind.GAS_GIANT, true, 8);
        assertObservationBound(CosmosSystem.Kind.BLACK_HOLE, CelestialBody.Kind.BLACK_HOLE, false, 24);
        assertObservationBound(CosmosSystem.Kind.SUPERNOVA, CelestialBody.Kind.STAR, false, 60);
        CelestialBody eccentric = planetDraft().orbit(FlightDynamics.LOCAL_RADIUS / 1.1, 1e9)
                .eccentricity(0.3).build();
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.builder("consumer:wide", "Wide")
                .body(eccentric).build());
        CelestialBody tiny = CelestialBodies.planet("tiny", "Tiny", CelestialBody.Kind.ICE, 1)
                .orbit(FlightDynamics.LOCAL_RADIUS - 99_999, 1e9).build();
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.builder("consumer:tiny", "Tiny")
                .body(tiny).build());
    }

    private static void assertObservationBound(CosmosSystem.Kind systemKind, CelestialBody.Kind bodyKind,
            boolean rings, double marginRadii) {
        double radius = 100_000;
        double orbit = FlightDynamics.LOCAL_RADIUS - radius * marginRadii;
        CelestialBody body = new CelestialBody("primary", "Primary", bodyKind, radius, orbit, 1e9,
                0, 0, 0, new SpaceVector(1, 1, 1), 0, rings ? 1.5f : 0, rings ? 2.5f : 0, 0);
        CelestialSystems.builder("consumer:boundary", "Boundary").kind(systemKind).body(body).build();
        CelestialBody outside = new CelestialBody("primary", "Primary", bodyKind, radius, orbit + 1, 1e9,
                0, 0, 0, new SpaceVector(1, 1, 1), 0, rings ? 1.5f : 0, rings ? 2.5f : 0, 0);
        assertThrows(IllegalArgumentException.class, () -> CelestialSystems.builder("consumer:outside", "Outside")
                .kind(systemKind).body(outside).build());
    }

    private static CelestialBodies.Builder planetDraft() {
        return CelestialBodies.planet("planet", "Planet", CelestialBody.Kind.ROCKY, 1_000);
    }

    private static CelestialSystems.Builder systemDraftFor(CosmosSystem system) {
        return CelestialSystems.builder(system.id(), system.name()).seed(system.seed()).kind(system.kind())
                .galaxyPositionLightYears(system.galaxyPosition().x(), system.galaxyPosition().y(),
                        system.galaxyPosition().z()).body(system.bodies().getFirst());
    }
}
