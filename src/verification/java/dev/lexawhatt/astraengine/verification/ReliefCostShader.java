package dev.lexawhatt.astraengine.verification;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;

/**
 * Verification-owned shader only: the same compiled program selects full, relief-bypass or background-only.
 * No public option, production resource or source state is changed. Caller owns the fixed-pose/timer scenario.
 */
final class ReliefCostShader extends ShaderInstance {
    enum Mode { REGISTERED, FULL, NOMINAL_SURFACE, BACKGROUND_ONLY }

    private final Field rendererShader;
    private Mode mode = Mode.FULL;
    private final String sourceHashes;

    ReliefCostShader() throws IOException, ReflectiveOperationException {
        this(findRendererShader(), provider());
    }

    private ReliefCostShader(Field rendererShader, Generated generated) throws IOException {
        super(generated.provider(), ResourceLocation.fromNamespaceAndPath("astraengine_verify", "relief_cost"),
                DefaultVertexFormat.POSITION);
        this.rendererShader = rendererShader;
        sourceHashes = generated.hashes();
    }

    String sourceHashes() { return sourceHashes; }

    private static Generated provider() throws IOException {
        var host = Minecraft.getInstance().getResourceManager();
        var fragment = host.getResourceOrThrow(ResourceLocation.fromNamespaceAndPath("astraengine", "shaders/core/cosmos.fsh"));
        var metadata = host.getResourceOrThrow(ResourceLocation.fromNamespaceAndPath("astraengine", "shaders/core/cosmos.json"));
        String original, originalJson;
        try (var input = fragment.open()) { original = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        try (var input = metadata.open()) { originalJson = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        String source = replaceOne(original, "#version 150", "#version 150\nuniform int VerificationReliefMode;");
        String signature = "float tilt, float spin, float pixelAngle, inout vec3 normal) {";
        source = replaceOne(source, signature, signature + "\n    if (VerificationReliefMode == 1) { return nominalHit; }");
        JsonObject json = JsonParser.parseString(originalJson).getAsJsonObject();
        if (!json.get("fragment").getAsString().equals("astraengine:cosmos")) {
            throw new IllegalStateException("Unexpected registered Cosmos fragment identity");
        }
        json.addProperty("fragment", "astraengine_verify:relief_cost");
        var uniform = new JsonObject(); uniform.addProperty("name", "VerificationReliefMode");
        uniform.addProperty("type", "int"); uniform.addProperty("count", 1);
        var values = new JsonArray(); values.add(0); uniform.add("values", values);
        for (var entry : json.getAsJsonArray("uniforms")) {
            if (entry.getAsJsonObject().get("name").getAsString().equals("VerificationReliefMode")) {
                throw new IllegalStateException("Production unexpectedly already contains the diagnostic uniform");
            }
        }
        json.getAsJsonArray("uniforms").add(uniform);
        byte[] code = source.getBytes(StandardCharsets.UTF_8), definition = json.toString().getBytes(StandardCharsets.UTF_8);
        ResourceLocation jsonId = ResourceLocation.fromNamespaceAndPath("astraengine_verify", "shaders/core/relief_cost.json");
        ResourceLocation fragmentId = ResourceLocation.fromNamespaceAndPath("astraengine_verify", "shaders/core/relief_cost.fsh");
        ResourceProvider provider = id -> {
            if (id.equals(jsonId)) { return Optional.of(new Resource(metadata.source(), () -> new ByteArrayInputStream(definition))); }
            if (id.equals(fragmentId)) { return Optional.of(new Resource(fragment.source(), () -> new ByteArrayInputStream(code))); }
            return host.getResource(id);
        };
        return new Generated(provider, "currentCosmosFshSha256=" + hash(original) + " currentCosmosJsonSha256=" + hash(originalJson));
    }

    private static String replaceOne(String source, String old, String replacement) {
        int at = source.indexOf(old);
        if (at < 0 || source.indexOf(old, at + old.length()) >= 0) {
            throw new IllegalStateException("Diagnostic substitution requires one exact source token: " + old);
        }
        return source.substring(0, at) + replacement + source.substring(at + old.length());
    }

    private static String hash(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is required", exception); }
    }

    private record Generated(ResourceProvider provider, String hashes) { }

    private static Field findRendererShader() throws ReflectiveOperationException {
        Field field = CosmosRenderer.class.getDeclaredField("shader");
        field.setAccessible(true);
        return field;
    }

    /** Borrow only around the actual renderer call; its registered Minecraft shader is always restored. */
    void render(CosmosRenderer renderer, Mode replacement, Runnable draw) throws IllegalAccessException {
        if (renderer == null || replacement == null || draw == null) {
            throw new IllegalArgumentException("A renderer, diagnostic mode and fixed draw are required");
        }
        if (replacement == Mode.REGISTERED) { draw.run(); return; }
        Object original = rendererShader.get(renderer);
        mode = replacement;
        rendererShader.set(renderer, this);
        try { draw.run(); }
        finally { rendererShader.set(renderer, original); }
    }

    @Override
    public void apply() {
        // Renderer extraction has already populated all normal uniforms; override only the intended
        // private diagnostic immediately before GPU upload. Every next draw repopulates BodyCount.
        safeGetUniform("VerificationReliefMode").set(mode == Mode.NOMINAL_SURFACE ? 1 : 0);
        if (mode == Mode.BACKGROUND_ONLY) { safeGetUniform("BodyCount").set(0); }
        super.apply();
    }
}
