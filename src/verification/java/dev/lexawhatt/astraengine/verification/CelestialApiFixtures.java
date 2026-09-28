package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.api.celestial.CelestialBodies;
import dev.lexawhatt.astraengine.api.celestial.CelestialSystems;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;

/** Pure consumer examples shared by isolated native and dedicated fixtures; stores no runtime registry. */
final class CelestialApiFixtures {
    static final String RING_SYSTEM = "verification:ringhaven";
    static final String HOLE_SYSTEM = "verification:umbra";
    static final String HIDDEN_SYSTEM = "verification:hidden";

    private CelestialApiFixtures() {}

    static CosmosSystem ringSystem() {
        return CelestialSystems.builder(RING_SYSTEM, "Consumer Ringhaven")
                .seed(2026092901L).kind(CosmosSystem.Kind.SINGLE).galaxyPositionLightYears(1.25, -0.5, 2.75)
                .body(CelestialBodies.star("primary", "Consumer Amber", 350_000_000)
                        .color(1, 0.72, 0.42).build())
                .body(CelestialBodies.planet("ringworld", "Consumer Aureole", CelestialBody.Kind.GAS_GIANT, 60_000_000)
                        .orbit(30_000_000_000.0, 1_000_000_000).phaseRadians(1.2).inclinationRadians(0.1)
                        .eccentricity(0.04).color(0.72, 0.5, 0.28).atmosphere(0.35f).rings(1.35f, 2.5f)
                        .axialTiltRadians(0.48).build())
                .build();
    }

    static CosmosSystem holeSystem() {
        return CelestialSystems.builder(HOLE_SYSTEM, "Consumer Umbra")
                .seed(2026092902L).kind(CosmosSystem.Kind.BLACK_HOLE).galaxyPositionLightYears(-2.5, 0.75, 1.5)
                .body(CelestialBodies.blackHole("primary", "Consumer Darkwell", 60_000)
                        .color(1, 0.68, 0.35).axialTiltRadians(0.15).build())
                .body(CelestialBodies.planet("ice", "Consumer Frost", CelestialBody.Kind.ICE, 4_000_000)
                        .orbit(2_000_000_000, 1_000_000_000).phaseRadians(-1.4)
                        .color(0.3, 0.62, 0.9).atmosphere(0.2f).build())
                .build();
    }

    static CosmosSystem simple(String id, String name) {
        return CelestialSystems.builder(id, name).seed(91).galaxyPositionLightYears(3, 2, 1)
                .body(CelestialBodies.star("primary", "Consumer Primary", 100_000_000).build()).build();
    }
}
