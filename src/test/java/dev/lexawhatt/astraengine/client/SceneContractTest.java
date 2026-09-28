package dev.lexawhatt.astraengine.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lexawhatt.astraengine.client.lighting.LightVector;
import dev.lexawhatt.astraengine.client.scene.SceneDocument;
import dev.lexawhatt.astraengine.client.scene.SceneHistory;
import dev.lexawhatt.astraengine.client.scene.SceneObject;
import dev.lexawhatt.astraengine.client.scene.ScenePresets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneContractTest {
    private static final LightVector ORIGIN = new LightVector(0, 0, 0);
    private static final String DIMENSION = "astraengine:alpha";

    @TempDir
    Path temporary;

    @Test
    void validatesExternalObjectsBeforeTheyReachRendering() {
        SceneObject original = shape("one");
        assertThrows(IllegalArgumentException.class, () -> shape("../one"));
        assertThrows(IllegalArgumentException.class, () -> shape(""));
        assertThrows(IllegalArgumentException.class, () -> SceneObject.create("one", null, ORIGIN));
        assertThrows(IllegalArgumentException.class, () -> SceneObject.create("one", SceneObject.Kind.BOX,
                new LightVector(30_000_001, 0, 0)));
        for (String field : List.of("emission", "innerRadius", "intensity", "range", "outerDegrees")) {
            JsonObject root = json(SceneDocument.empty(DIMENSION).withObject(original));
            root.getAsJsonArray("objects").get(0).getAsJsonObject().addProperty(field, -1);
            assertThrows(IllegalArgumentException.class, () -> ScenePresets.decode(root.toString()), field);
        }
        JsonObject root = json(SceneDocument.empty(DIMENSION).withObject(original));
        JsonObject object = root.getAsJsonArray("objects").get(0).getAsJsonObject();
        object.getAsJsonArray("scale").set(0, JsonParser.parseString("0"));
        assertThrows(IllegalArgumentException.class, () -> ScenePresets.decode(root.toString()));
        object.getAsJsonArray("scale").set(0, JsonParser.parseString("1"));
        object.addProperty("outerDegrees", original.innerDegrees());
        assertThrows(IllegalArgumentException.class, () -> ScenePresets.decode(root.toString()));
    }

    @Test
    void documentsAreImmutableAndReplaceByStableIdentity() {
        List<SceneObject> mutable = new ArrayList<>(List.of(shape("first"), shape("second")));
        SceneDocument original = new SceneDocument(DIMENSION, mutable);
        mutable.clear();
        assertEquals(2, original.objects().size());
        assertThrows(UnsupportedOperationException.class, original.objects()::clear);
        SceneObject replacement = SceneObject.create("first", SceneObject.Kind.BOX, new LightVector(1, 2, 3));
        SceneDocument changed = original.withObject(replacement);
        assertEquals(List.of(replacement, shape("second")), changed.objects());
        assertEquals(List.of(shape("second")), changed.withoutObject("first").objects());
        assertEquals(original, original.withoutObject("absent"));
        assertThrows(IllegalArgumentException.class, () -> new SceneDocument("bad dimension", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SceneDocument(DIMENSION, List.of(shape("same"), shape("same"))));
    }

    @Test
    void preservesSeparateShapeAndLightBudgetsIncludingHiddenObjects() {
        SceneDocument scene = SceneDocument.empty(DIMENSION);
        for (int index = 0; index < SceneDocument.MAX_SHAPES; index++) {
            scene = scene.withObject(shape("shape_" + index));
        }
        SceneDocument shapes = scene;
        assertThrows(IllegalArgumentException.class, () -> shapes.withObject(shape("overflow")));
        for (int index = 0; index < SceneDocument.MAX_LIGHTS; index++) {
            scene = scene.withObject(SceneObject.create("light_" + index, SceneObject.Kind.POINT, ORIGIN));
        }
        assertEquals(SceneDocument.MAX_OBJECTS, scene.objects().size());
        SceneDocument full = scene;
        assertThrows(IllegalArgumentException.class,
                () -> full.withObject(SceneObject.create("light_more", SceneObject.Kind.SPOT, ORIGIN)));
        assertThrows(IllegalArgumentException.class,
                () -> full.withObject(SceneObject.create("light_0", SceneObject.Kind.DISK, ORIGIN)));
        JsonObject hidden = json(shapes);
        hidden.getAsJsonArray("objects").forEach(value -> value.getAsJsonObject().addProperty("visible", false));
        SceneDocument hiddenShapes = ScenePresets.decode(hidden.toString());
        assertThrows(IllegalArgumentException.class, () -> hiddenShapes.withObject(shape("still_overflow")));
    }

    @Test
    void historyBoundsEditsAndInvalidatesRedoOnlyAfterARealCommit() {
        SceneDocument empty = SceneDocument.empty(DIMENSION);
        SceneHistory history = new SceneHistory(empty);
        for (int index = 0; index < 70; index++) {
            history.commit(empty.withObject(SceneObject.create("item", SceneObject.Kind.SPHERE,
                    new LightVector(index, 0, 0))));
        }
        int undos = 0;
        while (history.canUndo()) {
            history.undo();
            undos++;
        }
        assertEquals(SceneHistory.MAX_HISTORY, undos);
        assertEquals(5, history.current().objects().getFirst().position().x());
        history.commit(history.current());
        assertTrue(history.canRedo());
        assertEquals(6, history.redo().objects().getFirst().position().x());
        history.commit(empty.withObject(shape("branch")));
        assertFalse(history.canRedo());
        assertEquals(history.current(), history.redo());
        assertThrows(IllegalArgumentException.class, () -> history.commit(null));
        assertEquals("branch", history.current().objects().getFirst().id());
    }

    @Test
    void serializesAllKindsAndWorldCoordinatesWithoutLoss() {
        SceneDocument scene = SceneDocument.empty(DIMENSION);
        for (SceneObject.Kind kind : SceneObject.Kind.values()) {
            scene = scene.withObject(new SceneObject(kind.name().toLowerCase(java.util.Locale.ROOT), kind,
                    new LightVector(29_999_999.125, 203.5, -9999.875), new LightVector(12.5, -72, 360),
                    new LightVector(0.05, 256, 2), new LightVector(0, 0.5, 1),
                    8, 0.95f, 16, 256, 0, 89, false));
        }
        assertEquals(scene, ScenePresets.decode(ScenePresets.encode(scene)));
    }

    @Test
    void rejectsMalformedVersionedOrLooselyTypedJson() {
        for (String invalid : List.of("{}", "[]", "null", "/*comment*/{}",
                "{\"version\":1,\"dimension\":\"astraengine:alpha\",\"objects\":[],\"version\":1}",
                "{\"version\":1,\"dimension\":\"astraengine:alpha\",\"objects\":[]} true")) {
            assertThrows(IllegalArgumentException.class, () -> ScenePresets.decode(invalid), invalid);
        }
        JsonObject root = json(SceneDocument.empty(DIMENSION).withObject(shape("one")));
        root.addProperty("version", 1.5);
        assertThrows(IllegalArgumentException.class, () -> ScenePresets.decode(root.toString()));
        root.addProperty("version", "1");
        assertThrows(IllegalArgumentException.class, () -> ScenePresets.decode(root.toString()));
        root.addProperty("version", 1);
        JsonObject object = root.getAsJsonArray("objects").get(0).getAsJsonObject();
        object.addProperty("visible", "true");
        assertThrows(IllegalArgumentException.class, () -> ScenePresets.decode(root.toString()));
        object.addProperty("visible", true);
        object.addProperty("emission", "0.2");
        assertThrows(IllegalArgumentException.class, () -> ScenePresets.decode(root.toString()));
        object.addProperty("emission", 0.2);
        object.addProperty("kind", "UNSUPPORTED");
        assertThrows(IllegalArgumentException.class, () -> ScenePresets.decode(root.toString()));
        object.addProperty("kind", "SPHERE");
        object.addProperty("unknown", 1);
        assertThrows(IllegalArgumentException.class, () -> ScenePresets.decode(root.toString()));
    }

    @Test
    void presetsReplaceAtomicallyAndKeepPriorDataOnRejectedSave() throws IOException {
        ScenePresets presets = new ScenePresets(temporary.resolve("presets"));
        assertEquals(List.of(), presets.list());
        SceneDocument original = SceneDocument.empty(DIMENSION).withObject(shape("one"));
        presets.save("test_scene", original);
        assertEquals(original, presets.load("test_scene"));
        assertThrows(IllegalArgumentException.class, () -> presets.save("test_scene", null));
        assertEquals(original, presets.load("test_scene"));
        SceneDocument updated = original.withObject(shape("two"));
        presets.save("test_scene", updated);
        assertEquals(updated, presets.load("test_scene"));
        assertEquals(List.of("test_scene"), presets.list());
        Files.createDirectory(temporary.resolve("presets/blocked.json"));
        assertThrows(IOException.class, () -> presets.save("blocked", original));
        try (var files = Files.list(temporary.resolve("presets"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void presetsRejectPathsSymlinksAndOversizedInput() throws IOException {
        ScenePresets presets = new ScenePresets(temporary);
        SceneDocument empty = SceneDocument.empty(DIMENSION);
        for (String name : List.of("../outside", "UPPER", "a/b", "", "test.json")) {
            assertThrows(IllegalArgumentException.class, () -> presets.save(name, empty));
            assertThrows(IllegalArgumentException.class, () -> presets.load(name));
        }
        Path protectedFile = temporary.resolve("original.json");
        Files.writeString(protectedFile, "protected");
        Files.createSymbolicLink(temporary.resolve("linked.json"), protectedFile);
        assertThrows(IOException.class, () -> presets.load("linked"));
        assertThrows(IOException.class, () -> presets.save("linked", empty));
        assertEquals("protected", Files.readString(protectedFile));
        assertFalse(presets.list().contains("linked"));
        Files.writeString(temporary.resolve("large.json"), " ".repeat(ScenePresets.MAX_BYTES + 1));
        assertThrows(IOException.class, () -> presets.load("large"));
        assertThrows(IllegalArgumentException.class,
                () -> ScenePresets.decode(" ".repeat(ScenePresets.MAX_BYTES + 1)));
    }

    @Test
    void presetListingIsBoundedAndDeterministic() throws IOException {
        ScenePresets presets = new ScenePresets(temporary);
        for (int index = 130; index >= 0; index--) {
            Files.writeString(temporary.resolve(String.format(java.util.Locale.ROOT, "scene_%03d.json", index)), "{}");
        }
        Files.writeString(temporary.resolve("UPPER.json"), "{}");
        List<String> listed = presets.list();
        assertEquals(ScenePresets.MAX_LIST_ENTRIES, listed.size());
        assertEquals("scene_000", listed.getFirst());
        assertEquals("scene_127", listed.getLast());
        assertThrows(UnsupportedOperationException.class, listed::clear);
    }

    private static SceneObject shape(String id) {
        return SceneObject.create(id, SceneObject.Kind.SPHERE, ORIGIN);
    }

    private static JsonObject json(SceneDocument document) {
        return JsonParser.parseString(ScenePresets.encode(document)).getAsJsonObject();
    }
}
