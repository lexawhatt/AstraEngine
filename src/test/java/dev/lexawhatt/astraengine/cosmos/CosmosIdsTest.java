package dev.lexawhatt.astraengine.cosmos;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmosIdsTest {
    @Test
    void customIdsHaveExplicitNamespacesAndOneBoundedAsciiSegment() {
        for (String id : List.of("solartech:eden", "author.name:world-1_test", "m:" + "x".repeat(62))) {
            assertTrue(CosmosIds.isCustom(id), id);
            assertTrue(CosmosIds.isKnownId(id), id);
            assertFalse(CosmosIds.isBuiltin(id), id);
            assertEquals(id, CosmosIds.requireId(id));
        }
        for (String id : List.of("", "sol", "s_1_2_3", "legacy", ":world", "mod:", "mod:a:b", "Mod:world",
                "mod:World", "mod:world/path", "mod: world", "m:" + "x".repeat(63), "mod:\u043c\u0438\u0440")) {
            assertFalse(CosmosIds.isCustom(id), id);
        }
        assertFalse(CosmosIds.isCustom(null));
        assertFalse(CosmosIds.isBuiltin(null));
        assertFalse(CosmosIds.isKnownId(null));
        assertThrows(IllegalArgumentException.class, () -> CosmosIds.requireId(null));
    }

    @Test
    void builtinsAcceptOnlyCanonicalSignedIntegerSectorsAndSol() {
        for (String id : List.of("sol", "s_1_2_3", "s_-1_0_3", "s_-2147483648_2147483647_0")) {
            assertTrue(CosmosIds.isBuiltin(id), id);
            assertEquals(id, CosmosIds.requireId(id));
            assertEquals(id, CosmosGenerator.byId(7, id).id());
        }
        for (String id : List.of("s_0_0_0", "s_01_2_3", "s_-0_2_3", "s_+1_2_3", "s_1_2", "s_1_2_3_",
                "s_2147483648_0_0", "s_-2147483649_0_0", "../sol", "SOL", "legacy_fixture")) {
            assertFalse(CosmosIds.isBuiltin(id), id);
            assertFalse(CosmosIds.isKnownId(id), id);
            assertThrows(IllegalArgumentException.class, () -> CosmosIds.requireId(id));
        }
    }

    @Test
    void genericDescriptorsRetainLegacyFixtureIdsWhileSupportingCustomIds() {
        List<CelestialBody> bodies = CosmosGenerator.sol().bodies();
        for (String id : List.of("legacy_fixture", "s_1_2_3", "sol", "mod:world.with-dots")) {
            CosmosSystem system = new CosmosSystem(id, "System", 1, CosmosSystem.Kind.SINGLE, SpaceVector.ZERO, bodies);
            assertEquals(id, system.id());
        }
        assertThrows(IllegalArgumentException.class, () -> new CosmosSystem("mod:world/path", "System", 1,
                CosmosSystem.Kind.SINGLE, SpaceVector.ZERO, bodies));
    }
}
