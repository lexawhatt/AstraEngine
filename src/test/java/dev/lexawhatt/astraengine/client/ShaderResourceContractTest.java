package dev.lexawhatt.astraengine.client;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Names populated by Minecraft 1.21.1 ShaderInstance must retain the host's buffer layout. */
class ShaderResourceContractTest {
    private record Layout(String type, int count) { }

    @Test
    void automaticHostUniformsCannotCollideWithCustomBufferLayouts() throws Exception {
        Map<String, Layout> host = Map.ofEntries(
                Map.entry("ModelViewMat", new Layout("matrix4x4", 16)),
                Map.entry("ProjMat", new Layout("matrix4x4", 16)),
                Map.entry("TextureMat", new Layout("matrix4x4", 16)),
                Map.entry("ColorModulator", new Layout("float", 4)),
                Map.entry("GlintAlpha", new Layout("float", 1)),
                Map.entry("FogStart", new Layout("float", 1)),
                Map.entry("FogEnd", new Layout("float", 1)),
                Map.entry("FogColor", new Layout("float", 4)),
                Map.entry("FogShape", new Layout("int", 1)),
                Map.entry("GameTime", new Layout("float", 1)),
                Map.entry("ScreenSize", new Layout("float", 2)),
                Map.entry("LineWidth", new Layout("float", 1)),
                Map.entry("Light0_Direction", new Layout("float", 3)),
                Map.entry("Light1_Direction", new Layout("float", 3)));
        try (var resources = Files.list(Path.of("src/main/resources/assets/astraengine/shaders/core"))) {
            var shaders = resources.filter(path -> path.toString().endsWith(".json")).toList();
            assertFalse(shaders.isEmpty(), "The resource contract must inspect shipped shaders");
            for (Path shader : shaders) {
                var uniforms = JsonParser.parseString(Files.readString(shader)).getAsJsonObject().getAsJsonArray("uniforms");
                for (var entry : uniforms) {
                    var uniform = entry.getAsJsonObject();
                    String name = uniform.get("name").getAsString();
                    Layout expected = host.get(name);
                    if (expected != null) {
                        Layout actual = new Layout(uniform.get("type").getAsString(), uniform.get("count").getAsInt());
                        assertEquals(expected, actual, shader + ": host writes " + name + " automatically");
                    }
                }
            }
        }
    }
}
