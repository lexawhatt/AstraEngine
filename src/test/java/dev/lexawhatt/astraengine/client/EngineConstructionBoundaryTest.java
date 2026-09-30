package dev.lexawhatt.astraengine.client;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the engine/consumer boundary against accidental reintroduction of construction behavior. */
class EngineConstructionBoundaryTest {
    private static final Path MAIN = Path.of("src/main/java/dev/lexawhatt/astraengine");

    @Test
    void engineDoesNotShipTheFormerConstructionModelOrProtocol() throws Exception {
        try (var files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                assertFalse(relative.startsWith("rocket/") || relative.startsWith("api/rocket/")
                        || relative.startsWith("server/rocket/") || relative.startsWith("client/rocket/"), relative);
                String source = Files.readString(file);
                for (String removed : new String[]{"RocketCatalog", "RocketBlueprint", "RocketStats",
                        "RocketEditorCommandPayload", "RocketEditorStatePayload", "RocketEditorSessions"}) {
                    assertFalse(source.contains(removed), file + " retains construction dependency " + removed);
                }
            }
        }
    }

    @Test
    void shipInputsContainNoEngineeringOrGameplayState() throws Exception {
        Path api = MAIN.resolve("api/ship");
        assertTrue(Files.isDirectory(api), "Engine must retain a visual consumer boundary");
        try (var files = Files.list(api)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String forbidden : new String[]{"Fuel", "Thrust", "DeltaV", "Recipe", "Inventory",
                        "dev.lexawhatt.solartech", "net.minecraft.client", "server.rocket"}) {
                    assertFalse(source.contains(forbidden), file + " is not a common visual value contract");
                }
            }
        }
    }
}
