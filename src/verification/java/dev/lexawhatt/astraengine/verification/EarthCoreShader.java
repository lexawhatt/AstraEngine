package dev.lexawhatt.astraengine.verification;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;

/** Owned reference/diagnostic programs; never disposes the registered renderer program. */
final class EarthCoreShader extends ShaderInstance {
    enum Mode { ORIGINAL, DISABLED, ENABLED, PREDICATE, REGISTERED }
    enum State { NORMAL, NO_CONTINENTAL, NO_BODIES, LENS, GROUND, UNMAPPED, OFF_EARTH, INVALID_RADIUS }
    private final Field rendererShader;
    private final boolean original;
    private final String sourceHashes;
    private Mode mode;
    private State state = State.NORMAL;

    EarthCoreShader(boolean original) throws IOException, ReflectiveOperationException {
        this(original, shaderField(), provider(original));
    }
    private EarthCoreShader(boolean original, Field field, Generated generated) throws IOException {
        super(generated.provider(), id(original ? "earth_core_original" : "earth_core_probe"), DefaultVertexFormat.POSITION);
        this.original = original; rendererShader = field; sourceHashes = generated.hashes();
    }
    String sourceHashes() { return sourceHashes; }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("astraengine_verify", path); }
    private static Field shaderField() throws ReflectiveOperationException {
        var field = CosmosRenderer.class.getDeclaredField("shader"); field.setAccessible(true); return field;
    }
    private static String read(ResourceProvider provider, ResourceLocation id) throws IOException {
        try (var stream = provider.getResourceOrThrow(id).open()) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private static Generated provider(boolean original) throws IOException {
        var host = Minecraft.getInstance().getResourceManager();
        var fragmentId = ResourceLocation.fromNamespaceAndPath("astraengine", "shaders/core/cosmos.fsh");
        var metadataId = ResourceLocation.fromNamespaceAndPath("astraengine", "shaders/core/cosmos.json");
        var galaxyId = ResourceLocation.fromNamespaceAndPath("astraengine", "shaders/include/galaxy.glsl");
        String current = read(host, fragmentId), jsonText = read(host, metadataId), galaxy = read(host, galaxyId);
        String baseline = read(host, id("earth_core_original/cosmos.fsh"));
        String baselineGalaxy = read(host, id("earth_core_original/galaxy.glsl"));
        require(hash(baseline).equals("0c62967d45ced9039f5dbd7db4f226201a0c2fcb347dd3fc462f11ecf93b3192")
                && hash(baselineGalaxy).equals("cdb78627b8e82f3d8d9a289c08f6005d55a32d549e015ee8a4437227d0b985ea"), "Original source anchor changed");
        int start = current.indexOf("// Conservative opaque Earth core:"), end = current.indexOf("void main()", start);
        require(start >= 0 && end > start, "Expected bounded guard block");
        String reversed = current.substring(0, start) + current.substring(end);
        reversed = replace(reversed, "vec3 universe(vec3 ray, float pixelAngle, vec3 derivativeStars) {\n    return galacticSky(ray, pixelAngle, derivativeStars);", "vec3 universe(vec3 ray, float pixelAngle) {\n    return galacticSky(ray, pixelAngle);");
        String guarded = "    // Keep the sole background derivative site unconditional, even under opaque Earth.\n    // The expensive galaxy volumes/population have no implicit derivatives or texture sampling.\n    vec3 derivativeStars = stars(sourceRay, 370.0, 0.012, 0.07);\n    vec3 color = earthOpaqueCore(ray, pixelAngle) ? vec3(0.0) : universe(sourceRay, pixelAngle, derivativeStars);";
        reversed = replace(reversed, guarded, "    // Compute background derivatives before divergent body paths.\n    vec3 color = universe(sourceRay, pixelAngle);");
        String galaxyReversed = replace(galaxy, "vec3 galacticSky(vec3 worldRay, float pixelAngle, vec3 derivativeStars)", "vec3 galacticSky(vec3 worldRay, float pixelAngle)");
        galaxyReversed = replace(galaxyReversed, "color += derivativeStars;", "color += stars(worldRay, 370.0, 0.012, 0.07);");
        galaxyReversed = replace(galaxyReversed, "\n// Non-Cosmos callers retain the original unguarded background contract.\nvec3 galacticSky(vec3 worldRay, float pixelAngle) {\n    return galacticSky(worldRay, pixelAngle, stars(worldRay, 370.0, 0.012, 0.07));\n}\n", "");
        require(reversed.equals(baseline) && galaxyReversed.equals(baselineGalaxy), "Current source differs from anchor beyond declared guard patch");
        String source = baseline;
        String name = original ? "earth_core_original" : "earth_core_probe";
        var json = JsonParser.parseString(jsonText).getAsJsonObject(); json.addProperty("fragment", "astraengine_verify:" + name);
        if (!original) {
            source = replace(current, "#version 150", "#version 150\nuniform int VerificationGuardEnabled;\nuniform int VerificationPredicate;\nvec2 VerificationEarthGeometry = vec2(-1.0);\nbool VerificationCore = false;");
            source = replace(source, "vec3 color = earthOpaqueCore(ray, pixelAngle) ?", "VerificationCore = earthOpaqueCore(ray, pixelAngle);\n    vec3 color = (VerificationGuardEnabled != 0 && VerificationCore) ?");
            String marker = "    float atmosphereRadius = radius * (1.0 + parameters.y * 0.035);";
            source = replace(source, marker, "    if (BodyGeography[index].x > 1.5 && BodyGeography[index].x < 2.5 && kind == 3) { VerificationEarthGeometry = vec2(hit, discCoverage); }\n" + marker);
            int close = source.lastIndexOf('}');
            source = source.substring(0, close) + "    if (VerificationPredicate != 0) { fragColor = vec4(VerificationCore ? 1.0 : 0.0, VerificationEarthGeometry.x > 0.0 ? 1.0 : 0.0, VerificationEarthGeometry.y == 1.0 ? 1.0 : 0.0, max(0.0, VerificationEarthGeometry.y)); }\n" + source.substring(close);
            addUniform(json, "VerificationGuardEnabled"); addUniform(json, "VerificationPredicate");
        }
        var fragment = host.getResourceOrThrow(fragmentId); var metadata = host.getResourceOrThrow(metadataId);
        byte[] code = source.getBytes(StandardCharsets.UTF_8), definition = json.toString().getBytes(StandardCharsets.UTF_8);
        byte[] oldGalaxy = baselineGalaxy.getBytes(StandardCharsets.UTF_8);
        ResourceProvider provider = location -> {
            if (location.equals(id("shaders/core/" + name + ".fsh"))) { return Optional.of(new Resource(fragment.source(), () -> new ByteArrayInputStream(code))); }
            if (location.equals(id("shaders/core/" + name + ".json"))) { return Optional.of(new Resource(metadata.source(), () -> new ByteArrayInputStream(definition))); }
            if (original && location.equals(galaxyId)) { return Optional.of(new Resource(fragment.source(), () -> new ByteArrayInputStream(oldGalaxy))); }
            return host.getResource(location);
        };
        return new Generated(provider, "original=" + hash(baseline) + " current=" + hash(current) + " galaxy=" + hash(galaxy));
    }
    private static void addUniform(JsonObject json, String name) {
        var uniform = new JsonObject(); uniform.addProperty("name", name); uniform.addProperty("type", "int"); uniform.addProperty("count", 1);
        var values = new JsonArray(); values.add(0); uniform.add("values", values); json.getAsJsonArray("uniforms").add(uniform);
    }
    private static String replace(String source, String old, String replacement) {
        int at = source.indexOf(old); require(at >= 0 && source.indexOf(old, at + old.length()) < 0, "Expected unique source token: " + old);
        return source.substring(0, at) + replacement + source.substring(at + old.length());
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    void render(CosmosRenderer renderer, Mode mode, State state, Runnable draw) throws IllegalAccessException {
        if (mode == Mode.REGISTERED) { draw.run(); return; }
        Object borrowed = rendererShader.get(renderer); this.mode = mode; this.state = state; rendererShader.set(renderer, this);
        try { draw.run(); } finally { rendererShader.set(renderer, borrowed); }
    }
    @Override public void apply() {
        if (!original) {
            safeGetUniform("VerificationGuardEnabled").set(mode == Mode.ENABLED ? 1 : 0);
            safeGetUniform("VerificationPredicate").set(mode == Mode.PREDICATE ? 1 : 0);
        }
        switch (state) {
            case NO_CONTINENTAL -> safeGetUniform("ContinentalEarth").set(0);
            case NO_BODIES -> safeGetUniform("BodyCount").set(0);
            case LENS -> safeGetUniform("LensIndex").set(0);
            case GROUND -> safeGetUniform("SurfaceHorizon").set(0f, 1f, 0f, 1f);
            case UNMAPPED, OFF_EARTH, INVALID_RADIUS -> {
                for (int i = 0; i < 12; i++) {
                    var uniform = getUniform("BodyGeography[" + i + "]");
                    if (uniform == null) { continue; }
                    var buffer = uniform.getFloatBuffer();
                    float kind = buffer.get(0), altitude = buffer.get(1), radius = buffer.get(2), ready = buffer.get(3);
                    if (kind > 1.5 && kind < 2.5) {
                        if (state == State.UNMAPPED) { ready = 0; }
                        if (state == State.OFF_EARTH) { kind = 1; }
                        if (state == State.INVALID_RADIUS) { radius = 6370999; }
                        uniform.set(kind, altitude, radius, ready);
                    }
                }
            }
            default -> { }
        }
        super.apply();
    }
    private static void require(boolean value, String message) { if (!value) { throw new IllegalStateException(message); } }
    private record Generated(ResourceProvider provider, String hashes) { }
}
