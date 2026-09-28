package dev.lexawhatt.astraengine.client.lighting;

import java.util.Objects;

/**
 * One immutable visual light. Positions/ranges use blocks; colors are visual RGB coefficients in [0, 4].
 * Direction points toward a directional source or outward from a spot source.
 * Cone angles are degrees. This never changes Minecraft's server light levels.
 */
public record SceneLight(String id, Kind kind, LightVector position, LightVector direction,
                         LightVector color, float intensity, float range, float innerDegrees,
                         float outerDegrees, boolean contactShadows) {
    public enum Kind { DIRECTIONAL, POINT, SPOT }
    private static final LightVector ZERO = new LightVector(0, 0, 0);
    private static final LightVector FORWARD = new LightVector(0, 0, 1);

    public SceneLight {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(color, "color");
        direction = Objects.requireNonNull(direction, "direction").normalized();
        if (!id.matches("[a-z0-9_.:-]{1,96}") || !Float.isFinite(intensity) || intensity < 0 || intensity > 16
                || !Float.isFinite(range) || range <= 0 || range > 256
                || !Float.isFinite(innerDegrees) || !Float.isFinite(outerDegrees)
                || innerDegrees < 0 || outerDegrees <= innerDegrees || outerDegrees >= 90
                || color.x() < 0 || color.y() < 0 || color.z() < 0
                || color.x() > 4 || color.y() > 4 || color.z() > 4
                || Math.abs(position.x()) > 30_000_000 || Math.abs(position.y()) > 30_000_000
                || Math.abs(position.z()) > 30_000_000) {
            throw new IllegalArgumentException("Invalid visual light: " + id);
        }
    }

    /** Creates a distant light whose direction points from the scene toward the source. */
    public static SceneLight directional(String id, LightVector direction, LightVector color, float intensity) {
        return new SceneLight(id, Kind.DIRECTIONAL, ZERO, direction, color, intensity, 256, 0, 1, true);
    }

    /** Creates a finite point source; range is the zero-contribution distance in blocks. */
    public static SceneLight point(String id, LightVector position, LightVector color, float intensity, float range) {
        return new SceneLight(id, Kind.POINT, position, FORWARD, color, intensity, range, 0, 1, true);
    }

    /** Smooth cone contribution at a direction cosine, matching the shader's convention. */
    public double coneWeight(double directionCosine) {
        if (kind != Kind.SPOT) { return 1; }
        double inner = Math.cos(Math.toRadians(innerDegrees));
        double outer = Math.cos(Math.toRadians(outerDegrees));
        double t = Math.clamp((directionCosine - outer) / (inner - outer), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
