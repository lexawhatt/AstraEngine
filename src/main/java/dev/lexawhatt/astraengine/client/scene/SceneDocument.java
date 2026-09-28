package dev.lexawhatt.astraengine.client.scene;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable, dimension-scoped local visual scene. It never owns server state or world objects.
 * Identifiers are unique and GPU budgets include hidden objects; collections are defensively copied.
 */
public record SceneDocument(String dimension, List<SceneObject> objects) {
    public static final int VERSION = 1;
    public static final int MAX_SHAPES = 16;
    public static final int MAX_LIGHTS = 16;
    public static final int MAX_OBJECTS = MAX_SHAPES + MAX_LIGHTS;

    public SceneDocument {
        if (dimension == null || dimension.length() > 256
                || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Scene dimension must be a namespaced resource identifier");
        }
        if (objects == null || objects.size() > MAX_OBJECTS) {
            throw new IllegalArgumentException("Scene must contain at most " + MAX_OBJECTS + " objects");
        }
        int shapes = 0;
        int lights = 0;
        Set<String> identifiers = new HashSet<>();
        for (SceneObject object : objects) {
            if (object == null || !identifiers.add(object.id())) {
                throw new IllegalArgumentException("Scene objects must be non-null with unique identifiers");
            }
            if (object.kind().isLight()) {
                lights++;
            } else {
                shapes++;
            }
        }
        if (shapes > MAX_SHAPES || lights > MAX_LIGHTS) {
            throw new IllegalArgumentException("Scene supports at most " + MAX_SHAPES + " shapes and "
                    + MAX_LIGHTS + " lights");
        }
        objects = List.copyOf(objects);
    }

    /** Creates an empty scene bound to the supplied dimension identifier. */
    public static SceneDocument empty(String dimension) {
        return new SceneDocument(dimension, List.of());
    }

    /** Adds or replaces by identifier, preserving the order of existing objects; validates the complete result. */
    public SceneDocument withObject(SceneObject object) {
        if (object == null) {
            throw new IllegalArgumentException("Scene object cannot be null");
        }
        List<SceneObject> updated = new ArrayList<>(objects);
        for (int index = 0; index < updated.size(); index++) {
            if (updated.get(index).id().equals(object.id())) {
                updated.set(index, object);
                return new SceneDocument(dimension, updated);
            }
        }
        updated.add(object);
        return new SceneDocument(dimension, updated);
    }

    /** Removes the object with this identifier; a missing identifier leaves the scene unchanged. */
    public SceneDocument withoutObject(String id) {
        if (id == null) {
            throw new IllegalArgumentException("Scene object identifier cannot be null");
        }
        List<SceneObject> updated = objects.stream().filter(object -> !object.id().equals(id)).toList();
        return updated.size() == objects.size() ? this : new SceneDocument(dimension, updated);
    }
}
