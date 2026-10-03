package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthSurfacePalette;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/** Actual shared GLSL filtering versus canonical CPU material corners; verification resources never ship. */
final class EarthMaterialGpuVerification {
    // Binary32 forward-error model for two paths with at most seven mixes (three bilinear,
    // four inter-level), each lowered to at most three rounded operations. Forty-eight
    // roundings also allow six shared coefficient roundings. The absolute caps below are
    // stricter than this model and remain acceptance gates, not an implementation guarantee.
    private static final double FLOAT_UNIT_ROUNDOFF = 0x1.0p-24;
    private static final double BLEND_ERROR_FACTOR = 48 * FLOAT_UNIT_ROUNDOFF / (1 - 48 * FLOAT_UNIT_ROUNDOFF);
    private static final double[] FIELD_ERROR_CAPS = {0.001, 0.0002, 0.000002, 0.000002};
    private static final double MATERIAL_ERROR_CAP = 0.000002;

    private EarthMaterialGpuVerification() { }

    static String verify() throws Exception {
        RenderSystem.assertOnRenderThread();
        int[] parameters = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS,
                GL11.GL_PACK_SKIP_PIXELS, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES,
                GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST, GL11.GL_UNPACK_ALIGNMENT,
                GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS,
                GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_UNPACK_SKIP_IMAGES,
                GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST};
        int[] previous = new int[parameters.length];
        for (int i = 0; i < parameters.length; i++) { previous[i] = GL11.glGetInteger(parameters[i]); }
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int source = 0, output = 0, framebuffer = 0;
        int[] maps = new int[5];
        double maximumMaterialError = 0, maximumFieldError = 0;
        try (var state = new FullscreenPass(6); var masks = colorState(); var stack = MemoryStack.stackPush();
                var shader = new ShaderInstance(Minecraft.getInstance().getResourceManager(),
                        ResourceLocation.fromNamespaceAndPath("astraengine_verify", "earth_material"),
                        DefaultVertexFormat.POSITION)) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int parameter : parameters) {
                GL11.glPixelStorei(parameter,
                        parameter == GL11.GL_PACK_ALIGNMENT || parameter == GL11.GL_UNPACK_ALIGNMENT ? 4 : 0);
            }
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            source = texture(); output = texture();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 1, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, 0L);
            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, output, 0);
            require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE,
                    "Material probe framebuffer is incomplete");
            RenderSystem.viewport(0, 0, 1, 1);
            var palette = EarthSurfacePalette.DEFAULT;
            double[][] inputMagnitude = new double[2][4];
            for (int i = 0; i < palette.colors().size(); i++) {
                var color = palette.colors().get(i);
                inputMagnitude[1][0] = Math.max(inputMagnitude[1][0], Math.abs((float) color.x()));
                inputMagnitude[1][1] = Math.max(inputMagnitude[1][1], Math.abs((float) color.y()));
                inputMagnitude[1][2] = Math.max(inputMagnitude[1][2], Math.abs((float) color.z()));
                inputMagnitude[1][3] = 1;
                shader.safeGetUniform("EarthSurfaceColors[" + i + "]").set(
                        (float) color.x(), (float) color.y(), (float) color.z(), i < 2 ? 1.0f : 0.0f);
            }
            shader.setSampler("ProbeData", source);
            float[][][] cases = {
                    {{100, 26, .29f, 0}, {100, 26, .65f, 0}, {100, 26, .65f, 0}, {100, 26, .29f, 0}},
                    {{-100, -1, .5f, 0}, {-100, 1, .5f, 0}, {-100, 1, .5f, 0}, {-100, -1, .5f, 0}},
                    {{100, 14, .6f, .2f}, {3100, 1, .4f, .8f}, {2600, -1, .5f, .4f}, {-900, 20, .5f, .1f}}
            };
            var upload = stack.mallocFloat(16);
            var pixel = stack.mallocFloat(4);
            int comparisons = 0;
            for (float[][] corners : cases) {
                upload.clear();
                double[][] colors = new double[4][4];
                for (int i = 0; i < 4; i++) {
                    upload.put(corners[i]);
                    var sample = new ContinentalTerrain.Sample(corners[i][0], corners[i][1], corners[i][2], .1, corners[i][3]);
                    var color = palette.color(sample);
                    colors[i] = new double[] {(float) color.x(), (float) color.y(), (float) color.z(),
                            EarthSurfacePalette.liquid(sample) ? 1 : 0};
                }
                upload.flip(); RenderSystem.bindTexture(source);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 2, 2, 0, GL11.GL_RGBA, GL11.GL_FLOAT, upload);
                for (float x : new float[] {0, .25f, .5f, .75f, 1}) {
                    for (float y : new float[] {0, .5f, 1}) {
                        shader.safeGetUniform("ProbeUv").set((x + .5f) / 2, (y + .5f) / 2);
                        for (int material = 0; material < 2; material++) {
                            shader.safeGetUniform("ProbeMaterial").set(material);
                            FullscreenPass.draw(shader);
                            GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
                            for (int channel = 0; channel < 4; channel++) {
                                double expected = 0;
                                for (int i = 0; i < 4; i++) {
                                    double weight = ((i & 1) == 0 ? 1 - x : x) * (i < 2 ? 1 - y : y);
                                    expected += weight * (material == 0 ? corners[i][channel] : colors[i][channel]);
                                }
                                double error = Math.abs(expected - pixel.get(channel));
                                if (material == 0) { maximumFieldError = Math.max(maximumFieldError, error); }
                                else { maximumMaterialError = Math.max(maximumMaterialError, error); }
                                require(Float.isFinite(pixel.get(channel)) && error < (material == 0 ? .0003 : .000002),
                                        "Material GPU filtering changed canonical corners/height: mode=" + material
                                                + " uv=" + x + "," + y + " channel=" + channel
                                                + " expected=" + expected + " actual=" + pixel.get(channel));
                            }
                            comparisons++;
                        }
                    }
                }
            }
            RenderSystem.activeTexture(GL13.GL_TEXTURE0); RenderSystem.bindTexture(output);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 2, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, 0L);
            RenderSystem.viewport(0, 0, 2, 1);
            String[] mapNames = {"ContinentalGlobe", "ContinentalTile0", "ContinentalTile1",
                    "ContinentalTile2", "ContinentalTile3"};
            var mapValues = stack.mallocFloat(8 * 8 * 4);
            for (int level = 0; level < maps.length; level++) {
                maps[level] = texture(); mapValues.clear();
                for (int z = 0; z < 8; z++) {
                    for (int x = 0; x < 8; x++) {
                        // Every level and axis is distinct; wrong weight/order cannot hide behind equal textures.
                        mapValues.put(-900 + level * 913 + x * 251 - z * 109);
                        mapValues.put(-6 + level * 5 + x * 3 - z);
                        mapValues.put((level * 3 + x * 5 + z * 7) % 17 / 16.0f);
                        mapValues.put((x + z + level) % 7 == 0 ? -1 : .4f);
                    }
                }
                mapValues.flip();
                for (int i = 0; i < mapValues.limit(); i++) {
                    int channel = i % 4;
                    inputMagnitude[0][channel] = Math.max(inputMagnitude[0][channel], Math.abs(mapValues.get(i)));
                }
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 8, 8, 0,
                        GL11.GL_RGBA, GL11.GL_FLOAT, mapValues);
                shader.setSampler(mapNames[level], maps[level]);
            }
            String lazyReport = verifyLazySelection(shader, inputMagnitude);
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Material GPU probe left a GL error");
            return String.format(Locale.ROOT,
                    "GPU material filter: %d RGBA comparisons, desert/jungle and ice/liquid corners; "
                            + "max material error=%.9f, unchanged field error=%.9f; "
                            + "%s%n",
                    comparisons, maximumMaterialError, maximumFieldError, lazyReport);
        } finally {
            if (framebuffer != 0) { GL30.glDeleteFramebuffers(framebuffer); }
            if (source != 0) { TextureUtil.releaseTextureId(source); }
            if (output != 0) { TextureUtil.releaseTextureId(output); }
            for (int map : maps) { if (map != 0) { TextureUtil.releaseTextureId(map); } }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            for (int i = 0; i < parameters.length; i++) { GL11.glPixelStorei(parameters[i], previous[i]); }
        }
    }

    private static String verifyLazySelection(ShaderInstance shader, double[][] inputMagnitude) {
        int comparisons = 0, exactComparisons = 0;
        double[][] maximumError = new double[2][4];
        double maximumBoundFraction = 0;
        shader.safeGetUniform("ProbeMode").set(3);
        // Include overlapping and deliberately non-nested weights; the optimization cannot assume
        // the production spacing ratios when discarding a coarser contribution.
        float[][] spacings = {{4, 32, 256, 1024}, {4, 5, 6, 7}, {9, 4, 11, 6}};
        SpaceVector[][] frames = {
                {new SpaceVector(1, 0, 0), new SpaceVector(0, 0, 1), new SpaceVector(0, 1, 0)},
                {new SpaceVector(0, 1, 0), new SpaceVector(1, 0, 0), new SpaceVector(0, 0, 1)}
        };
        try (var stack = MemoryStack.stackPush()) {
            var pixels = stack.mallocFloat(8);
            for (SpaceVector[] frame : frames) {
                vector(shader, "ContinentalUp", frame[0]);
                vector(shader, "ContinentalEast", frame[1]);
                vector(shader, "ContinentalSouth", frame[2]);
                for (float[] spacing : spacings) {
                    shader.safeGetUniform("ContinentalSpacing").set(spacing[0], spacing[1], spacing[2], spacing[3]);
                    List<SpaceVector> directions = new ArrayList<>();
                    directions.add(frame[0]); directions.add(frame[0].multiply(-1));
                    directions.add(frame[1]); directions.add(new SpaceVector(0, -1, 0));
                    for (float level : spacing) {
                        for (double ratio : new double[] {.7999, .8, .8001, .9, .9999, 1, 1.0001}) {
                            double distance = level * 256.0 * ratio;
                            for (int axis = 1; axis <= 2; axis++) {
                                for (double sign : new double[] {-1, 1}) {
                                    directions.add(frame[0].multiply(6_371_000)
                                            .add(frame[axis].multiply(distance * sign)).normalized());
                                }
                            }
                        }
                    }
                    for (SpaceVector direction : directions) {
                        vector(shader, "ProbeDirection", direction);
                        // Saved readiness alternatives must still use the eager fallback/globe behavior.
                        for (int state = 0; state < 3; state++) {
                            shader.safeGetUniform("ContinentalReady").set(state == 0 ? 0 : 1);
                            shader.safeGetUniform("ContinentalTilesReady").set(state == 2 ? 1 : 0);
                            for (int material = 0; material < 2; material++) {
                                shader.safeGetUniform("ProbeMaterial").set(material);
                                FullscreenPass.draw(shader);
                                GL11.glReadPixels(0, 0, 2, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
                                boolean exact = true;
                                for (int channel = 0; channel < 4; channel++) {
                                    float lazy = pixels.get(channel), eager = pixels.get(channel + 4);
                                    double error = Math.abs((double) lazy - eager);
                                    double cap = material == 0 ? FIELD_ERROR_CAPS[channel] : MATERIAL_ERROR_CAP;
                                    double bound = Math.min(cap, BLEND_ERROR_FACTOR * inputMagnitude[material][channel]);
                                    // GLSL mix(a,b,1) is mathematically b, but a legal float lowering
                                    // a + (b-a) may lose a low bit through endpoint cancellation.
                                    // Preserve the independent eager source; never mask non-finite data,
                                    // alter its weights, or permit physically meaningful height/color drift.
                                    require(Float.isFinite(lazy) && Float.isFinite(eager)
                                                    && (state != 2 ? lazy == eager : error == 0 || error < bound),
                                            "Lazy continental selection exceeded eager numerical bound: state=" + state
                                                    + " material=" + material + " direction=" + direction
                                                    + " channel=" + channel + " lazy=" + lazy + " eager=" + eager
                                                    + " error=" + error + " bound=" + bound);
                                    exact &= lazy == eager;
                                    maximumError[material][channel] = Math.max(maximumError[material][channel], error);
                                    if (bound > 0) { maximumBoundFraction = Math.max(maximumBoundFraction, error / bound); }
                                }
                                comparisons++;
                                if (exact) { exactComparisons++; }
                            }
                        }
                    }
                }
            }
        }
        return String.format(Locale.ROOT,
                "%d lazy/eager RGBA pairs (%d exact-value equal), bounded binary32 equivalence across "
                        + "tile fades/non-nested weights/poles/rear/fallback; field max=[%.9f m,%.9f C,%.9f,%.9f], "
                        + "material max=[%.9f,%.9f,%.9f,%.9f], max bound fraction=%.6f; "
                        + "height<1mm/material<2e-6 and exact unready/globe-only PASS",
                comparisons, exactComparisons, maximumError[0][0], maximumError[0][1], maximumError[0][2],
                maximumError[0][3], maximumError[1][0], maximumError[1][1], maximumError[1][2], maximumError[1][3],
                maximumBoundFraction);
    }

    private static void vector(ShaderInstance shader, String name, SpaceVector value) {
        shader.safeGetUniform(name).set((float) value.x(), (float) value.y(), (float) value.z());
    }

    private static int texture() {
        int texture = TextureUtil.generateTextureId(); RenderSystem.bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return texture;
    }

    private static AutoCloseable colorState() throws Exception {
        var constructor = Class.forName("dev.lexawhatt.astraengine.client.render.CelestialBloomPipeline$ColorState")
                .getDeclaredConstructor();
        constructor.setAccessible(true); return (AutoCloseable) constructor.newInstance();
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
