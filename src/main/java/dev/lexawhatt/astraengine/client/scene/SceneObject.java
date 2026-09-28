package dev.lexawhatt.astraengine.client.scene;

import dev.lexawhatt.astraengine.client.lighting.LightVector;

/**
 * Immutable client presentation object, independent of server blocks and celestial state.
 * Position uses world blocks; Euler angles use degrees with local rotation Rz * Ry * Rx.
 * Scale contains positive half extents/radii. Rings and disks occupy the local XZ plane,
 * with normalized outer radius one and an inner-radius ratio. RGB components are in [0, 1].
 * All fields remain validated even when unused by the current kind. Null inputs are rejected.
 */
public record SceneObject(String id, Kind kind, LightVector position, LightVector rotation,
                          LightVector scale, LightVector color, float emission, float innerRadius,
                          float intensity, float range, float innerDegrees, float outerDegrees, boolean visible) {
    /** Available shader primitives and visual light types. */
    public enum Kind {
        SPHERE, BOX, RING, DISK, POINT, SPOT, DIRECTIONAL;

        /** Whether this kind contributes to the visual light budget rather than the shape budget. */
        public boolean isLight() {
            return this == POINT || this == SPOT || this == DIRECTIONAL;
        }
    }

    public SceneObject {
        if (id == null || !id.matches("[a-z0-9_-]{1,48}") || kind == null) {
            throw new IllegalArgumentException("Scene object needs a valid identifier and kind");
        }
        requireVector(position, -30_000_000, 30_000_000, "position");
        requireVector(rotation, -360, 360, "rotation degrees");
        requireVector(scale, 0.05, 256, "scale");
        requireVector(color, 0, 1, "color");
        requireRange(emission, 0, 8, "emission");
        requireRange(innerRadius, 0, 0.95f, "inner radius");
        requireRange(intensity, 0, 16, "intensity");
        requireRange(range, 0.1f, 256, "light range");
        if (!Float.isFinite(innerDegrees) || !Float.isFinite(outerDegrees)
                || innerDegrees < 0 || outerDegrees <= innerDegrees || outerDegrees >= 90) {
            throw new IllegalArgumentException("Outer cone must exceed inner cone and stay below 90 degrees");
        }
    }

    /** Creates a visible cyan object or light with unit scale and bounded editable defaults. */
    public static SceneObject create(String id, Kind kind, LightVector position) {
        return new SceneObject(id, kind, position, new LightVector(0, 0, 0), new LightVector(1, 1, 1),
                new LightVector(0.2, 0.8, 1), 0.2f, 0.55f, 4, 16, 15, 25, true);
    }

    private static void requireVector(LightVector value, double minimum, double maximum, String field) {
        if (value == null || value.x() < minimum || value.x() > maximum
                || value.y() < minimum || value.y() > maximum || value.z() < minimum || value.z() > maximum) {
            throw new IllegalArgumentException("Scene " + field + " must have components in ["
                    + minimum + ", " + maximum + "]");
        }
    }

    private static void requireRange(float value, float minimum, float maximum, String field) {
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException("Scene " + field + " must be finite and in ["
                    + minimum + ", " + maximum + "]");
        }
    }
}
