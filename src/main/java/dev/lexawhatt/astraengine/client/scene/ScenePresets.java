package dev.lexawhatt.astraengine.client.scene;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.lexawhatt.astraengine.client.lighting.LightVector;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Local, versioned scene presets. Disk methods are synchronous and belong on an I/O worker;
 * callers must apply loaded snapshots on the client thread only while their session remains current.
 * Files are at most 1 MiB; names never contain paths. Final-file and preset-directory symlinks
 * are rejected. Atomic replacement is required: unsupported filesystems fail without a fallback.
 * Validation failures throw IllegalArgumentException; filesystem failures throw IOException.
 */
public final class ScenePresets {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_LIST_ENTRIES = 128;

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> DOCUMENT_FIELDS = Set.of("version", "dimension", "objects");
    private static final Set<String> OBJECT_FIELDS = Set.of("id", "kind", "position", "rotation", "scale", "color",
            "emission", "innerRadius", "intensity", "range", "innerDegrees", "outerDegrees", "visible");
    private final Path directory;

    /** Binds this store to a local directory without performing disk I/O. Null is rejected. */
    public ScenePresets(Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("Preset directory cannot be null");
        }
        this.directory = directory.toAbsolutePath().normalize();
    }

    /** Writes a complete validated snapshot, atomically replacing the named preset on success only. */
    public void save(String name, SceneDocument document) throws IOException {
        Path target = file(name);
        byte[] bytes = encode(document).getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(directory);
        requireDirectory();
        rejectSymlink(target);
        Path temporary = Files.createTempFile(directory, ".astra-scene-", ".tmp");
        try {
            Files.write(temporary, bytes);
            rejectSymlink(target);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    /** Reads and validates the full preset before returning; no live scene is mutated on failure. */
    public SceneDocument load(String name) throws IOException {
        Path target = file(name);
        requireDirectory();
        rejectSymlink(target);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Preset is not a regular file: " + target.getFileName());
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Scene preset exceeds " + MAX_BYTES + " bytes");
        }
        return decode(new String(bytes, StandardCharsets.UTF_8));
    }

    /** Returns at most 128 valid regular-file names in lexical order; a missing directory is empty. */
    public List<String> list() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        requireDirectory();
        TreeSet<String> names = new TreeSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.json")) {
            for (Path entry : entries) {
                String filename = entry.getFileName().toString();
                String name = filename.substring(0, filename.length() - 5);
                if (validName(name) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    names.add(name);
                    if (names.size() > MAX_LIST_ENTRIES) {
                        names.pollLast();
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    /** Produces version-one JSON. Null documents are rejected; output is bounded to the disk limit. */
    public static String encode(SceneDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("Scene document cannot be null");
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", SceneDocument.VERSION);
        root.addProperty("dimension", document.dimension());
        JsonArray objects = new JsonArray();
        for (SceneObject object : document.objects()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", object.id());
            value.addProperty("kind", object.kind().name());
            value.add("position", encodeVector(object.position()));
            value.add("rotation", encodeVector(object.rotation()));
            value.add("scale", encodeVector(object.scale()));
            value.add("color", encodeVector(object.color()));
            value.addProperty("emission", object.emission());
            value.addProperty("innerRadius", object.innerRadius());
            value.addProperty("intensity", object.intensity());
            value.addProperty("range", object.range());
            value.addProperty("innerDegrees", object.innerDegrees());
            value.addProperty("outerDegrees", object.outerDegrees());
            value.addProperty("visible", object.visible());
            objects.add(value);
        }
        root.add("objects", objects);
        String encoded = JSON.toJson(root) + "\n";
        requireSize(encoded);
        return encoded;
    }

    /** Parses strict typed JSON, rejecting missing/unknown/duplicate fields and unsupported versions. */
    public static SceneDocument decode(String json) {
        requireSize(json);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            Set<String> fields = new HashSet<>();
            String dimension = null;
            List<SceneObject> objects = null;
            reader.beginObject();
            while (reader.hasNext()) {
                String field = field(reader, fields, DOCUMENT_FIELDS);
                switch (field) {
                    case "version" -> {
                        if (number(reader) != SceneDocument.VERSION) {
                            throw new IllegalArgumentException("Unsupported scene preset version");
                        }
                    }
                    case "dimension" -> dimension = string(reader);
                    case "objects" -> objects = readObjects(reader);
                    default -> throw new IllegalArgumentException("Unknown scene field: " + field);
                }
            }
            reader.endObject();
            requireFields(fields, DOCUMENT_FIELDS);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Trailing content in scene preset");
            }
            return new SceneDocument(dimension, objects);
        } catch (IOException | IllegalStateException exception) {
            throw new IllegalArgumentException("Invalid scene preset JSON: " + exception.getMessage(), exception);
        }
    }

    private static List<SceneObject> readObjects(JsonReader reader) throws IOException {
        List<SceneObject> objects = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (objects.size() >= SceneDocument.MAX_OBJECTS) {
                throw new IllegalArgumentException("Scene exceeds object budget");
            }
            objects.add(readObject(reader));
        }
        reader.endArray();
        return objects;
    }

    private static SceneObject readObject(JsonReader reader) throws IOException {
        JsonObject values = new JsonObject();
        Set<String> fields = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String field = field(reader, fields, OBJECT_FIELDS);
            switch (field) {
                case "id", "kind" -> values.addProperty(field, string(reader));
                case "position", "rotation", "scale", "color" -> values.add(field, readVector(reader));
                case "visible" -> {
                    requireToken(reader, JsonToken.BOOLEAN);
                    values.addProperty(field, reader.nextBoolean());
                }
                default -> values.addProperty(field, number(reader));
            }
        }
        reader.endObject();
        requireFields(fields, OBJECT_FIELDS);
        SceneObject.Kind kind;
        try {
            kind = SceneObject.Kind.valueOf(values.get("kind").getAsString());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown scene object kind", exception);
        }
        return new SceneObject(values.get("id").getAsString(), kind,
                vector(values, "position"), vector(values, "rotation"), vector(values, "scale"), vector(values, "color"),
                values.get("emission").getAsFloat(), values.get("innerRadius").getAsFloat(),
                values.get("intensity").getAsFloat(), values.get("range").getAsFloat(),
                values.get("innerDegrees").getAsFloat(), values.get("outerDegrees").getAsFloat(),
                values.get("visible").getAsBoolean());
    }

    private static String field(JsonReader reader, Set<String> seen, Set<String> allowed) throws IOException {
        String field = reader.nextName();
        if (!allowed.contains(field) || !seen.add(field)) {
            throw new IllegalArgumentException("Unknown or duplicate scene field: " + field);
        }
        return field;
    }

    private static void requireFields(Set<String> fields, Set<String> required) {
        if (!fields.equals(required)) {
            Set<String> missing = new TreeSet<>(required);
            missing.removeAll(fields);
            throw new IllegalArgumentException("Missing scene fields: " + missing);
        }
    }

    private static JsonArray readVector(JsonReader reader) throws IOException {
        reader.beginArray();
        JsonArray values = new JsonArray();
        for (int index = 0; index < 3; index++) {
            values.add(number(reader));
        }
        reader.endArray();
        return values;
    }

    private static double number(JsonReader reader) throws IOException {
        requireToken(reader, JsonToken.NUMBER);
        double value = reader.nextDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Scene numbers must be finite");
        }
        return value;
    }

    private static String string(JsonReader reader) throws IOException {
        requireToken(reader, JsonToken.STRING);
        return reader.nextString();
    }

    private static void requireToken(JsonReader reader, JsonToken token) throws IOException {
        if (reader.peek() != token) {
            throw new IllegalArgumentException("Expected scene JSON " + token + " at " + reader.getPath());
        }
    }

    private static JsonArray encodeVector(LightVector vector) {
        JsonArray value = new JsonArray();
        value.add(vector.x());
        value.add(vector.y());
        value.add(vector.z());
        return value;
    }

    private static LightVector vector(JsonObject values, String field) {
        JsonArray value = values.getAsJsonArray(field);
        return new LightVector(value.get(0).getAsDouble(), value.get(1).getAsDouble(), value.get(2).getAsDouble());
    }

    private static void requireSize(String json) {
        if (json == null || json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Scene JSON must be non-null and at most " + MAX_BYTES + " bytes");
        }
    }

    private Path file(String name) {
        if (!validName(name)) {
            throw new IllegalArgumentException("Preset name must contain 1-48 lowercase letters, digits, '_' or '-'");
        }
        return directory.resolve(name + ".json");
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[a-z0-9_-]{1,48}");
    }

    private void requireDirectory() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Preset directory must be a regular directory: " + directory);
        }
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Scene preset symlinks are not supported: " + path.getFileName());
        }
    }
}
