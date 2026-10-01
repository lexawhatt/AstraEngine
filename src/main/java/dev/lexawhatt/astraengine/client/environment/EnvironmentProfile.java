package dev.lexawhatt.astraengine.client.environment;

import com.google.gson.JsonObject;
import dev.lexawhatt.astraengine.client.lighting.LightVector;

/** Resource-defined presentation, independent of server system state and biome/gameplay light. */
public record EnvironmentProfile(boolean planetary, float ambient, float sunStrength, float exposure,
                                 float bloom, float caveFloor, boolean rings, float ringTilt) {
    public static final EnvironmentProfile NEUTRAL = new EnvironmentProfile(false, 1, 0, 1, 0, 1, false, 0.5f);
    public static final EnvironmentProfile SPACE = new EnvironmentProfile(false, 1, 1.4f, 1, 0.3f, 1, false, 0.5f);
    public static final EnvironmentProfile PLANET = new EnvironmentProfile(true, 0.72f, 1.8f, 1, 0.28f, 0.015f, true, 0.5f);

    public EnvironmentProfile {
        bounded(ambient, 0, 2, "ambient");
        bounded(sunStrength, 0, 8, "sun_strength");
        bounded(exposure, 0.1f, 4, "exposure");
        bounded(bloom, 0, 2, "bloom");
        bounded(caveFloor, 0, 1, "cave_floor");
        bounded(ringTilt, 0.05f, 1, "ring_tilt");
    }

    /** Parses one version-one resource. Missing values use the corresponding built-in profile defaults. */
    public static EnvironmentProfile parse(JsonObject json) {
        if (!json.has("version") || !json.get("version").isJsonPrimitive()
                || !json.getAsJsonPrimitive("version").isNumber() || json.get("version").getAsDouble() != 1) {
            throw new IllegalArgumentException("Unsupported rendering profile version");
        }
        boolean planetary = flag(json, "planetary", false);
        EnvironmentProfile defaults = planetary ? PLANET : SPACE;
        return new EnvironmentProfile(planetary, value(json, "ambient", defaults.ambient),
                value(json, "sun_strength", defaults.sunStrength), value(json, "exposure", defaults.exposure),
                value(json, "bloom", defaults.bloom), value(json, "cave_floor", defaults.caveFloor),
                flag(json, "rings", defaults.rings),
                value(json, "ring_tilt", defaults.ringTilt));
    }

    /** Minecraft-style visual day: sunrise at 0, noon 6000, sunset 12000, midnight 18000. */
    public LightVector sunDirection(double dayTicks) {
        if (!Double.isFinite(dayTicks)) { throw new IllegalArgumentException("Day time must be finite"); }
        if (!planetary) { return new LightVector(0.25, 0.22, 1).normalized(); }
        double angle = (dayTicks % 24000) * Math.PI * 2 / 24000;
        return new LightVector(Math.cos(angle), Math.sin(angle), 0.35).normalized();
    }

    private static float value(JsonObject json, String key, float fallback) {
        if (!json.has(key)) { return fallback; }
        if (!json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("Expected numeric profile field: " + key);
        }
        return json.get(key).getAsFloat();
    }

    private static boolean flag(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) { return fallback; }
        if (!json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException("Expected boolean profile field: " + key);
        }
        return json.get(key).getAsBoolean();
    }

    private static void bounded(float value, float min, float max, String name) {
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("Invalid profile value: " + name);
        }
    }
}
