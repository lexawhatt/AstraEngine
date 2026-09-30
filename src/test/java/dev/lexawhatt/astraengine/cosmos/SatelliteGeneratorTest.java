package dev.lexawhatt.astraengine.cosmos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SatelliteGeneratorTest {
    @Test
    void additivePopulationPreservesHistoricalParentDefinitionsAndPositions() throws Exception {
        // Captured from the pre-satellite de10c6b parent generator, including positions at 0, 1234 and 1e10 seconds.
        assertEquals("1e5cfe128779687f26c26beb4e530bf2218e18a16d8fd9b2bbb43de428792d45", fingerprint(CosmosGenerator.sol()));
        assertEquals("ab23243d707cc71e4b0309b862081ace496da7909882111a7ba6e3a08bdd416d",
                fingerprint(CosmosGenerator.generate(1234, -7, 2, 14)));
        assertEquals("5db6600f898da81f4dd43ec9b1c076dd3cde935a7ade406ee0222ebb34edc0fa",
                fingerprint(CosmosGenerator.generate(20260927, 16, -5, 2)));
        assertEquals("0679e406edc7caafe6d28ef36eef2ce8fafbb4b547665debf0f54c822921de13",
                fingerprint(CosmosGenerator.generate(Long.MAX_VALUE, 3, -4, 12)));
    }

    @Test
    void majorSolarSatellitesAreAppendedInStableGroupsAndMercuryVenusRemainMoonless() {
        CosmosSystem sol = CosmosGenerator.sol();
        assertEquals(30, sol.bodies().size());
        assertEquals("moon", sol.bodies().get(9).id());
        Map<String, Integer> counts = Map.of("mercury", 0, "venus", 0, "earth", 1, "mars", 2,
                "jupiter", 4, "saturn", 7, "uranus", 5, "neptune", 2);
        counts.forEach((parent, expected) -> assertEquals(expected.longValue(), sol.bodies().stream()
                .filter(body -> body.parentId().equals(parent)).count()));
        assertEquals(List.of("io", "europa", "ganymede", "callisto"), sol.bodies().stream()
                .filter(body -> body.parentId().equals("jupiter")).map(CelestialBody::id).toList());
        for (CelestialBody moon : sol.bodies()) {
            if (moon.parentId().isEmpty()) { continue; }
            SpaceVector center = sol.positionAt(moon.parentId(), 73);
            assertEquals(moon.positionAt(73).length(), sol.positionAt(moon, 73).distance(center), 0.002);
            assertTrue(moon.orbitMeters() > moon.radiusMeters());
        }
        CelestialBody triton = sol.bodies().stream().filter(body -> body.id().equals("triton")).findFirst().orElseThrow();
        assertTrue(Math.abs(triton.inclinationRadians()) > Math.PI / 2, "Triton must retain retrograde motion");
    }

    @Test
    void proceduralSatellitesAreDeterministicBoundedAndOutsideTheirParentsAndRings() {
        int total = 0;
        for (int index = 1; index <= 200; index++) {
            CosmosSystem system = CosmosGenerator.generate(491827, index, 41, -19);
            assertEquals(system, CosmosGenerator.generate(491827, index, 41, -19));
            assertTrue(system.bodies().size() <= CosmosSystem.MAX_BODIES);
            for (CelestialBody moon : system.bodies()) {
                if (moon.parentId().isEmpty()) { continue; }
                total++;
                CelestialBody parent = system.bodies().stream().filter(value -> value.id().equals(moon.parentId()))
                        .findFirst().orElseThrow();
                assertTrue(parent.parentId().isEmpty());
                assertTrue(moon.orbitMeters() * (1 - moon.eccentricity()) - moon.radiusMeters()
                        > parent.radiusMeters() * Math.max(1, parent.ringOuterRatio()));
                assertEquals(0, system.positionAt(parent, 71).add(moon.positionAt(71))
                        .distance(system.positionAt(moon, 71)), 0.001);
            }
        }
        assertTrue(total > 200, "The procedural sample should contain a meaningful satellite population");
    }

    private static String fingerprint(CosmosSystem system) throws Exception {
        StringBuilder text = new StringBuilder().append(system.id()).append('|').append(system.name()).append('|')
                .append(system.seed()).append('|').append(system.kind()).append('|').append(system.galaxyPosition());
        for (CelestialBody body : system.bodies()) {
            if (!body.parentId().isEmpty()) { continue; }
            text.append('|').append(body.id()).append('|').append(body.name()).append('|').append(body.kind())
                    .append('|').append(body.radiusMeters()).append('|').append(body.orbitMeters()).append('|').append(body.orbitalPeriodSeconds())
                    .append('|').append(body.phaseRadians()).append('|').append(body.inclinationRadians()).append('|').append(body.eccentricity())
                    .append('|').append(body.color()).append('|').append(body.atmosphere()).append('|').append(body.ringInnerRatio())
                    .append('|').append(body.ringOuterRatio()).append('|').append(body.axialTiltRadians());
            for (double seconds : new double[]{0, 1234, 1e10}) { text.append('|').append(system.positionAt(body, seconds)); }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
