package dev.lexawhatt.astraengine.cosmos;

import dev.lexawhatt.astraengine.api.celestial.CelestialBodies;
import dev.lexawhatt.astraengine.api.celestial.CelestialSystems;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sparse compact landmarks must never rewrite prior worlds, body identity or seeded orbital parameters. */
class PulsarGeneratorTest {
    @Test
    void priorCatalogDescriptorsRetainTheirExactGoldenFingerprint() throws NoSuchAlgorithmException {
        // Captured from the pre-pulsar 6b3d350 production classes, before changing any generator.
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int count = 0;
        for (long seed : new long[]{0, 42, Long.MIN_VALUE, Long.MAX_VALUE}) {
            for (String id : List.of("sol", "s_1_-2_3", "s_-19_7_82", "s_2147483647_-2147483648_1")) {
                digest.update((UniverseGenerator.byId(seed, id) + "\n").getBytes(StandardCharsets.UTF_8));
                count++;
            }
            for (int galaxy = 0; galaxy < UniverseGenerator.GALAXY_COUNT; galaxy++) {
                for (CosmosSystem system : UniverseGenerator.landmarkSystems(seed, galaxy)) {
                    digest.update((system + "\n").getBytes(StandardCharsets.UTF_8));
                    count++;
                }
                for (CosmosSystem system : UniverseGenerator.nearby(seed, galaxy,
                        UniverseGenerator.galaxy(seed, galaxy).centerLightYears(), 1)) {
                    digest.update((system + "\n").getBytes(StandardCharsets.UTF_8));
                    count++;
                }
            }
        }
        assertEquals(1228, count);
        assertEquals("8ecae2854bf0f488f337bf08ffa67c65ca1cc764d470ba3ea81e0d8985c17d91",
                HexFormat.of().formatHex(digest.digest()));
    }

    @Test
    void compactLandmarksAreDeterministicPublicAndApproachableInEveryGalaxy() {
        for (long seed : new long[]{0, 42, Long.MIN_VALUE, Long.MAX_VALUE}) {
            for (int galaxy = 0; galaxy < UniverseGenerator.GALAXY_COUNT; galaxy++) {
                CosmosSystem system = PulsarGenerator.landmark(seed, galaxy);
                assertEquals("p_" + galaxy, system.id());
                assertEquals(system, UniverseGenerator.byId(seed, system.id()));
                assertEquals(system, CosmosGenerator.byId(seed, system.id()));
                assertEquals(galaxy, UniverseGenerator.galaxyIndex(system.id()));
                assertTrue(UniverseGenerator.isAtlasSystemId(system.id()));
                assertTrue(CosmosIds.isBuiltin(system.id()));
                assertFalse(CosmosIds.isCustom(system.id()));
                assertEquals(CosmosSystem.Kind.PULSAR, system.kind());
                CelestialBody primary = system.bodies().getFirst();
                assertEquals(CelestialBody.Kind.PULSAR, primary.kind());
                assertTrue(primary.radiusMeters() >= 10_000 && primary.radiusMeters() < 14_000);
                assertEquals(SpaceVector.ZERO, system.positionAt(primary, 123_456));
                assertEquals(0, primary.atmosphere());
                assertEquals(0, primary.ringOuterRatio());
                FlightDynamics.Observation view = FlightDynamics.observation(system, primary, 42);
                assertEquals(primary.radiusMeters() * 80, view.position().length(), 1e-8);
                assertTrue(FlightDynamics.clearSegment(view.position(), view.position(), system.bodies(), 42, 42));
                assertEquals(UniverseGenerator.nearby(seed, galaxy, system.galaxyPosition(), 1),
                        UniverseGenerator.nearby(seed, system, 1));
            }
        }
        assertNotEquals(PulsarGenerator.landmark(42, 0), PulsarGenerator.landmark(43, 0));
        CosmosSystem target = PulsarGenerator.landmark(42, 0);
        SpaceVector destination = target.galaxyPosition().multiply(CosmosGenerator.LIGHT_YEAR);
        assertEquals(target, GalacticNavigation.firstArrival(CosmosGenerator.sol(), SpaceVector.ZERO, destination,
                List.of(CosmosGenerator.sol(), target)).orElseThrow().system());
        assertTrue(GalacticNavigation.firstArrival(CosmosGenerator.sol(), SpaceVector.ZERO, destination,
                List.of(CosmosGenerator.sol())).isEmpty());
        var start = new FlightDynamics.State(new SpaceVector(0, 0, -20_000_000), SpaceVector.ZERO);
        BodyApproach route = BodyApproach.plan(target, target.bodies().getFirst(), start,
                FlightOrientation.IDENTITY, 0).orElseThrow();
        assertTrue(route.durationTicks() > 0 && route.durationTicks() <= BodyApproach.MAX_TICKS);
    }

    @Test
    void canonicalIdentityDoesNotCaptureCustomNamespacesOrMalformedIndices() {
        assertFalse(PulsarGenerator.isPulsarId(null));
        for (String invalid : List.of("p", "p_", "p_9", "p_00", "p_-1", "p_+1", "p_1_0", "p_1 ",
                "p_mod:eden", "mod:p_1", "P_1", "p_\u0661")) {
            assertFalse(PulsarGenerator.isPulsarId(invalid), invalid);
            assertFalse(UniverseGenerator.isAtlasSystemId(invalid), invalid);
            assertThrows(IllegalArgumentException.class, () -> PulsarGenerator.byId(42, invalid));
        }
        assertTrue(CosmosIds.isCustom("p_mod:eden"));
        assertTrue(CosmosIds.isKnownId("mod:p_1"));
        assertThrows(IllegalArgumentException.class, () -> PulsarGenerator.landmark(42, 9));
        assertThrows(IllegalArgumentException.class, () -> PulsarGenerator.landmark(42, -1));
    }

    @Test
    void customPulsarsUseTheOrdinaryImmutableDescriptorAndRejectPlanetMaterialMisuse() {
        var draft = CelestialBodies.pulsar("primary", "Beacon", 12_000).axialTiltRadians(0.6);
        CelestialBody primary = draft.build();
        draft.color(0.3, 0.8, 1);
        assertEquals(new SpaceVector(1, 1, 1), primary.color());
        CosmosSystem system = CelestialSystems.builder("example:beacon", "Beacon")
                .kind(CosmosSystem.Kind.PULSAR).body(primary).build();
        assertEquals(CelestialBody.Kind.PULSAR, system.bodies().getFirst().kind());
        assertThrows(IllegalArgumentException.class,
                () -> CelestialBodies.planet("wrong", "Wrong", CelestialBody.Kind.PULSAR, 12_000));
        assertThrows(IllegalArgumentException.class,
                () -> CelestialBodies.pulsar("invalid", "Invalid", Double.NaN).build());
        assertEquals(0, CelestialBody.Kind.STAR.ordinal());
        assertEquals(5, CelestialBody.Kind.ICE.ordinal());
        assertEquals(6, CelestialBody.Kind.PULSAR.ordinal());
        assertEquals(4, CosmosSystem.Kind.PULSAR.ordinal());
    }
}
