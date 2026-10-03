package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/** Actual camera rays compare shared production transport with denser view quadrature; never ships. */
final class EarthCloudRayConvergenceVerification {
    private static final String NAME = "earth_cloud_ray_probe";
    private static final float[][] CLIPS = {{0, -.65f}, {0, -.25f}, {0, .05f}, {0, .105f},
            {-.5f, -.35f}, {.5f, -.35f}, {-.5f, .1f}, {.5f, .1f},
            {2 * 1239.5f / 1920 - 1, 1 - 2 * 800.5f / 1080},
            {2 * 1255.5f / 1920 - 1, 1 - 2 * 800.5f / 1080},
            {2 * 1425.5f / 1920 - 1, 1 - 2 * 900.5f / 1080},
            {2 * 1441.5f / 1920 - 1, 1 - 2 * 900.5f / 1080},
            {2 * 1608.5f / 1920 - 1, 1 - 2 * 1000.5f / 1080},
            {2 * 1624.5f / 1920 - 1, 1 - 2 * 1000.5f / 1080}};

    private EarthCloudRayConvergenceVerification() { }

    static String capture(CosmosRenderer renderer, Path directory) throws Exception {
        RenderSystem.assertOnRenderThread();
        var source = (ShaderInstance) field(renderer, "shader");
        int index = source.getUniform("AtmosphereBodyIndex").getIntBuffer().get(0);
        require(index >= 0 && source.getUniform("CloudPlanet").getFloatBuffer().get(3) > 0,
                "Cloud convergence requires actual canonical Earth");
        int[] pack = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS,
                GL11.GL_PACK_SKIP_PIXELS, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES,
                GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST};
        int[] previous = new int[pack.length];
        for (int i = 0; i < pack.length; i++) { previous[i] = GL11.glGetInteger(pack[i]); }
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int texture = 0, framebuffer = 0;
        var report = new StringBuilder("Cloud view convergence: original eight camera rays plus six seam-pair rays; fixed full-resolution pixel footprint.\n")
                .append("12 stratified vs128/256 uniform view cells; identical shared source, field, self-shadow budget and extinction.\n")
                .append("Only view-loop caps/bounds/allocation substituted in a verification ResourceProvider; no production mutation.\n")
                .append("Sea-level sphere clips diagnostic rays; no terrain occlusion or foreground air.\n")
                .append("First-cell fractions can describe a weak high wisp; weighted fractions describe absorbed view contribution, not radiance by type.\n")
                .append("steps,ray,clipX,clipY,R,G,B,transmission,stratus,cumulus,convection,firstTau,stepKm,occupiedSpanKm,meanSourceKm,firstAltitudeKm,")
                .append("weightedStratus,weightedCumulus,weightedConvection,typeTransmission,weightedStepKm,weightedSpanKm,occupiedCells,opacityWeight\n");
        try (var saved = new FullscreenPass(2); var masks = colorState(); var stack = MemoryStack.stackPush()) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int parameter : pack) { GL11.glPixelStorei(parameter, parameter == GL11.GL_PACK_ALIGNMENT ? 4 : 0); }
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            texture = TextureUtil.generateTextureId(); RenderSystem.bindTexture(texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 1, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, 0L);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE,
                    "Cloud ray diagnostic framebuffer incomplete");
            RenderSystem.viewport(0, 0, 1, 1);
            var pixel = stack.mallocFloat(4);
            for (int steps : new int[] {12, 128, 256}) {
                try (var shader = new ShaderInstance(provider(steps),
                        ResourceLocation.fromNamespaceAndPath("astraengine_verify", NAME + "_" + steps),
                        DefaultVertexFormat.POSITION)) {
                    bind(shader, source, renderer, index);
                    shader.safeGetUniform("ProbeSteps").set(steps);
                    for (int ray = 0; ray < CLIPS.length; ray++) {
                        shader.safeGetUniform("ProbeClip").set(CLIPS[ray][0], CLIPS[ray][1]);
                        report.append(String.format(Locale.ROOT, "%d,%d,%.4f,%.4f", steps, ray, CLIPS[ray][0], CLIPS[ray][1]));
                        for (int mode = 0; mode < 5; mode++) {
                            shader.safeGetUniform("ProbeMode").set(mode);
                            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer); RenderSystem.viewport(0, 0, 1, 1);
                            FullscreenPass.draw(shader);
                            pixel.clear(); GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
                            for (int channel = 0; channel < 4; channel++) {
                                require(Float.isFinite(pixel.get(channel)), "Cloud ray diagnostic produced a non-finite value");
                                report.append(String.format(Locale.ROOT, ",%.8g", pixel.get(channel)));
                            }
                        }
                        report.append('\n');
                    }
                }
            }
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Cloud ray diagnostic left a GL error");
        } finally {
            if (framebuffer != 0) { GL30.glDeleteFramebuffers(framebuffer); }
            if (texture != 0) { TextureUtil.releaseTextureId(texture); }
            for (int i = 0; i < pack.length; i++) { GL11.glPixelStorei(pack[i], previous[i]); }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        }
        Files.writeString(directory.resolve("cloud-ray-convergence.csv"), report);
        return "Fourteen-ray shared-source convergence saved at12 stratified/128 uniform/256 uniform cells; no dense-image render.\n";
    }

    private static ResourceProvider provider(int steps) throws Exception {
        var host = Minecraft.getInstance().getResourceManager();
        var includeId = ResourceLocation.fromNamespaceAndPath("astraengine", "shaders/include/earth_clouds.glsl");
        var include = host.getResourceOrThrow(includeId);
        String code;
        try (var stream = include.open()) { code = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
        if (steps > 12) {
            code = replaceOne(code, "int count = clamp(requestedSteps, 8, 48);", "int count = clamp(requestedSteps, 8, 256);");
            code = replaceOne(code, "if (observerAltitude > 24.0) { count = min(count, 12); }",
                    "if (observerAltitude > 24.0) { count = min(count, " + steps + "); }");
            code = replaceOne(code, "for (int i = 0; i < 48; i++)", "for (int i = 0; i < 256; i++)");
            code = replaceOne(code,
                    "int intervalCount = earthCloudViewIntervals(originKm, ray, limitKm, count, intervals, intervalSteps);",
                    "int intervalCount = 1; intervals[0] = earthCloudViewRange(originKm, ray, limitKm); intervalSteps[0] = count;"
                            + " if (intervals[0].y <= intervals[0].x) { intervalCount = 0; }");
        }
        byte[] modified = code.getBytes(StandardCharsets.UTF_8);
        var json = host.getResourceOrThrow(id("shaders/core/", NAME, ".json"));
        String metadata;
        try (var stream = json.open()) { metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
        byte[] definition = replaceOne(metadata, "\"fragment\": \"astraengine_verify:" + NAME + "\"",
                "\"fragment\": \"astraengine_verify:" + NAME + "_" + steps + "\"").getBytes(StandardCharsets.UTF_8);
        ResourceLocation jsonId = id("shaders/core/", NAME + "_" + steps, ".json");
        ResourceLocation fragmentId = id("shaders/core/", NAME + "_" + steps, ".fsh");
        var fragment = host.getResourceOrThrow(id("shaders/core/", NAME, ".fsh"));
        return location -> {
            if (location.equals(includeId)) { return Optional.of(new Resource(include.source(), () -> new ByteArrayInputStream(modified))); }
            if (location.equals(jsonId)) { return Optional.of(new Resource(json.source(), () -> new ByteArrayInputStream(definition))); }
            if (location.equals(fragmentId)) { return Optional.of(fragment); }
            return host.getResource(location);
        };
    }

    private static String replaceOne(String source, String previous, String next) {
        int offset = source.indexOf(previous);
        require(offset >= 0 && source.indexOf(previous, offset + previous.length()) < 0,
                "Cloud reference substitution must match exactly one view-loop token: " + previous);
        return source.substring(0, offset) + next + source.substring(offset + previous.length());
    }

    private static ResourceLocation id(String prefix, String name, String suffix) {
        return ResourceLocation.fromNamespaceAndPath("astraengine_verify", prefix + name + suffix);
    }

    private static void bind(ShaderInstance shader, ShaderInstance source, CosmosRenderer renderer, int index) throws Exception {
        shader.setSampler("CloudNoise", (int) field(field(renderer, "cloudNoise"), "texture"));
        shader.setSampler("EarthOpticalColumns", (int) field(field(renderer, "atmosphereOptics"), "texture"));
        shader.safeGetUniform("EarthOpticsReady").set(1);
        shader.safeGetUniform("EarthOpticsRadius").set(6371.0f);
        for (String name : new String[] {"CloudNoiseLayout", "EarthCloudParams", "CloudPlanet"}) {
            var v = source.getUniform(name).getFloatBuffer(); shader.safeGetUniform(name).set(v.get(0), v.get(1), v.get(2), v.get(3));
        }
        for (String name : new String[] {"CloudWind", "CloudLayer"}) {
            var v = source.getUniform(name).getFloatBuffer(); shader.safeGetUniform(name).set(v.get(0), v.get(1));
        }
        var sun = source.getUniform("EarthCloudSun").getFloatBuffer();
        shader.safeGetUniform("EarthCloudSun").set(sun.get(0), sun.get(1), sun.get(2));
        shader.safeGetUniform("ProbeInverseView").set(new Matrix4f().set(source.getUniform("InverseViewProjection").getFloatBuffer().duplicate().rewind()));
        shader.safeGetUniform("ProbeBodyRotation").set(new Matrix3f().set(source.getUniform("EarthInverseRotation").getFloatBuffer().duplicate().rewind()));
        shader.safeGetUniform("ProbeCalendar").set(source.getUniform("CalendarEarth").getIntBuffer().get(0));
        shader.safeGetUniform("ProbeAngles").set(source.getUniform("BodyLightTilt[" + index + "]").getFloatBuffer().get(3),
                source.getUniform("BodySpin[" + index + "]").getFloatBuffer().get(0));
        var window = Minecraft.getInstance().getWindow();
        shader.safeGetUniform("ProbeScreenSize").set((float) window.getWidth(), (float) window.getHeight());
    }

    private static AutoCloseable colorState() throws Exception {
        var constructor = Class.forName("dev.lexawhatt.astraengine.client.render.CelestialBloomPipeline$ColorState").getDeclaredConstructor();
        constructor.setAccessible(true); return (AutoCloseable) constructor.newInstance();
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
