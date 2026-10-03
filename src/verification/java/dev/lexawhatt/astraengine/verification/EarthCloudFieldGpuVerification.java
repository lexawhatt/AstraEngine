package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import dev.lexawhatt.astraengine.client.sky.CloudNoiseField;
import dev.lexawhatt.astraengine.client.sky.EarthCloudState;
import dev.lexawhatt.astraengine.client.sky.EarthCloudWeather;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.EarthAtmosphereOptics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.nio.file.Path;
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
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Actual GLSL regional weather versus the CPU field at fixed directions, including fronts and chart seams. */
final class EarthCloudFieldGpuVerification {
    private EarthCloudFieldGpuVerification() { }

    /** Isolated real cloud transport at the final camera; excludes terrain occlusion and foreground air. */
    static String captureTransport(CosmosRenderer renderer, Path output) throws Exception {
        var source = (ShaderInstance) field(renderer, "shader");
        int bodyIndex = source.getUniform("AtmosphereBodyIndex").getIntBuffer().get(0);
        require(bodyIndex >= 0 && source.getUniform("BodyGeography[" + bodyIndex + "]").getFloatBuffer().get(0) == 2,
                "Cloud transport diagnostic requires the selected Earth body");
        int width = Minecraft.getInstance().getWindow().getWidth();
        int height = Minecraft.getInstance().getWindow().getHeight();
        int[] pack = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS,
                GL11.GL_PACK_SKIP_PIXELS, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES,
                GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST};
        int[] previous = new int[pack.length];
        for (int i = 0; i < pack.length; i++) { previous[i] = GL11.glGetInteger(pack[i]); }
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try (var saved = new FullscreenPass(); var masks = create("CelestialBloomPipeline$ColorState")) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int value : pack) { GL11.glPixelStorei(value, value == GL11.GL_PACK_ALIGNMENT ? 4 : 0); }
            var constructor = Class.forName("dev.lexawhatt.astraengine.client.render.HdrColorTarget")
                    .getDeclaredConstructor(int.class, int.class);
            constructor.setAccessible(true);
            try (var target = (AutoCloseable) constructor.newInstance(width, height);
                 var shader = new ShaderInstance(Minecraft.getInstance().getResourceManager(),
                         ResourceLocation.fromNamespaceAndPath("astraengine_verify", "earth_cloud_field"),
                         DefaultVertexFormat.POSITION)) {
                shader.setSampler("CloudNoise", (int) field(field(renderer, "cloudNoise"), "texture"));
                shader.setSampler("EarthOpticalColumns", (int) field(field(renderer, "atmosphereOptics"), "texture"));
                shader.safeGetUniform("EarthOpticsReady").set(1);
                shader.safeGetUniform("EarthOpticsRadius").set(6371.0f);
                for (String name : new String[] {"CloudNoiseLayout", "EarthCloudParams", "CloudPlanet"}) {
                    var values = source.getUniform(name).getFloatBuffer();
                    shader.safeGetUniform(name).set(values.get(0), values.get(1), values.get(2), values.get(3));
                }
                for (String name : new String[] {"CloudWind", "CloudLayer"}) {
                    var values = source.getUniform(name).getFloatBuffer();
                    shader.safeGetUniform(name).set(values.get(0), values.get(1));
                }
                var sunlight = source.getUniform("EarthCloudSun").getFloatBuffer();
                shader.safeGetUniform("EarthCloudSun").set(sunlight.get(0), sunlight.get(1), sunlight.get(2));
                shader.safeGetUniform("ProbeInverseView").set(new Matrix4f()
                        .set(source.getUniform("InverseViewProjection").getFloatBuffer().duplicate().rewind()));
                shader.safeGetUniform("ProbeBodyRotation").set(new Matrix3f()
                        .set(source.getUniform("EarthInverseRotation").getFloatBuffer().duplicate().rewind()));
                shader.safeGetUniform("ProbeCalendar").set(source.getUniform("CalendarEarth").getIntBuffer().get(0));
                shader.safeGetUniform("ProbeAngles").set(
                        source.getUniform("BodyLightTilt[" + bodyIndex + "]").getFloatBuffer().get(3),
                        source.getUniform("BodySpin[" + bodyIndex + "]").getFloatBuffer().get(0));
                shader.safeGetUniform("ProbeScreenSize").set((float) width, (float) height);
                int detail = source.getUniform("Detail").getIntBuffer().get(0);
                shader.safeGetUniform("ProbeSteps").set(detail >= 5 ? 32 : detail >= 4 ? 24 : 16);
                var bind = target.getClass().getDeclaredMethod("bind"); bind.setAccessible(true);
                for (int mode : new int[] {4, 5}) {
                    shader.safeGetUniform("ProbeMode").set(mode);
                    bind.invoke(target); FullscreenPass.draw(shader);
                    RenderSystem.bindTexture((int) field(target, "texture"));
                    try (var image = new NativeImage(width, height, false)) {
                        image.downloadTexture(0, false); image.flipY();
                        image.writeToFile(output.resolve(mode == 4 ? "front-cloud-transmittance.png"
                                : "front-cloud-reflected-radiance-display1.png"));
                    }
                }
                require(GL11.glGetError() == GL11.GL_NO_ERROR, "Isolated cloud transport capture left a GL error");
            }
        } finally {
            for (int i = 0; i < pack.length; i++) { GL11.glPixelStorei(pack[i], previous[i]); }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
        }
        return "Front transport diagnostic: actual full-resolution camera, frozen weather, shared density/source; "
                + "transmission linear grayscale and reflected radiance at display exposure1; "
                + "sea-level sphere clipping only, no terrain occlusion/foreground air/exposure history.\n";
    }

    static String verify(CosmosRenderer renderer) throws Exception {
        var source = (ShaderInstance) field(renderer, "shader");
        var atlas = field(renderer, "cloudNoise");
        var noise = (CloudNoiseField) field(atlas, "field");
        int texture = (int) field(atlas, "texture");
        require(texture != 0, "Cloud field GPU probe requires the actual uploaded atlas");
        var params = source.getUniform("EarthCloudParams").getFloatBuffer();
        var wind = source.getUniform("CloudWind").getFloatBuffer();
        // Preview begins with Clouds OFF. This isolated sampler probes a fixed enabled coverage using
        // the same actual weather/time inputs, without changing the scene or its synchronized snapshot.
        var state = new EarthCloudState(.55f, params.get(1), params.get(2), 0, params.get(3), wind.get(0), wind.get(1));
        int[] pack = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS,
                GL11.GL_PACK_SKIP_PIXELS, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES,
                GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST};
        int[] previous = new int[pack.length];
        for (int i = 0; i < pack.length; i++) { previous[i] = GL11.glGetInteger(pack[i]); }
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        double maximumError = 0, maximumExpected = 0;
        try (var saved = new FullscreenPass(); var masks = create("CelestialBloomPipeline$ColorState")) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int value : pack) { GL11.glPixelStorei(value, value == GL11.GL_PACK_ALIGNMENT ? 4 : 0); }
            var constructor = Class.forName("dev.lexawhatt.astraengine.client.render.HdrColorTarget")
                    .getDeclaredConstructor(int.class, int.class);
            constructor.setAccessible(true);
            try (var target = (AutoCloseable) constructor.newInstance(1, 1);
                 var shader = new ShaderInstance(Minecraft.getInstance().getResourceManager(),
                         ResourceLocation.fromNamespaceAndPath("astraengine_verify", "earth_cloud_field"),
                         DefaultVertexFormat.POSITION)) {
                shader.setSampler("CloudNoise", texture);
                int opticsTexture = (int) field(field(renderer, "atmosphereOptics"), "texture");
                require(opticsTexture != 0, "Finite air probe requires the actual ready optical table");
                shader.setSampler("EarthOpticalColumns", opticsTexture);
                shader.safeGetUniform("EarthOpticsReady").set(1);
                shader.safeGetUniform("EarthOpticsRadius").set(6371.0f);
                shader.safeGetUniform("CloudNoiseLayout").set((float) CloudNoiseField.PERIOD, (float) CloudNoiseField.TILE_SIZE,
                        (float) CloudNoiseField.ATLAS_SIZE, (float) CloudNoiseField.TILES_PER_ROW);
                shader.safeGetUniform("EarthCloudParams").set(state.cover(), state.season(), state.rain(), state.incident());
                shader.safeGetUniform("CloudWind").set(state.windX(), state.windZ());
                shader.safeGetUniform("CloudPlanet").set(0.0f, 0.0f, 0.0f, 6371.0f);
                var bind = target.getClass().getDeclaredMethod("bind"); bind.setAccessible(true);
                List<SpaceVector> points = points();
                for (int i = 0; i < points.size(); i++) {
                    SpaceVector point = points.get(i);
                    // CPU receives the exact float coordinates uploaded to GLSL, not a slightly different direction.
                    point = new SpaceVector((float) point.x(), (float) point.y(), (float) point.z());
                    var expected = EarthCloudWeather.sample(noise, state, point);
                    double[] channels = {expected.stratus(), expected.cumulus(), expected.convection()};
                    shader.safeGetUniform("ProbePoint").set((float) point.x(), (float) point.y(), (float) point.z());
                    bind.invoke(target); FullscreenPass.draw(shader);
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
                    try (var stack = MemoryStack.stackPush()) {
                        var pixel = stack.mallocFloat(4);
                        GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
                        for (int j = 0; j < 3; j++) {
                            double error = Math.abs(pixel.get(j) - channels[j]);
                            maximumError = Math.max(maximumError, error);
                            maximumExpected = Math.max(maximumExpected, channels[j]);
                            // RG8 hardware bilinear interpolation and half-float output are intentionally bounded.
                            require(Float.isFinite(pixel.get(j)) && error < .015,
                                    "Cloud GPU/CPU field mismatch at direction " + i + ", channel " + j
                                            + ": gpu=" + pixel.get(j) + ", cpu=" + channels[j]
                                            + ", point=" + point + ", state=" + state);
                        }
                        require(Math.abs(pixel.get(3) - 1.35 / 1.354) < .001,
                                "Earth incident irradiance no longer preserves host E/pi calibration");
                    }
                }
                require(maximumExpected > .3, "Cloud field probe missed every active weather system");
                verifyFiniteAir(shader, target, bind);
                verifyScatteringCentroid(shader, target, bind);
                verifyFilteredMass(shader, target, bind);
                verifyMaterialResponse(shader, target, bind);
                verifyOccupiedSupport(shader, target, bind);
                int pruningCases = verifyActiveLayerPruning(shader, target, bind);
                verifyDiffuseSky(shader, target, bind);
                int intervalCases = verifyViewIntervals(shader);
                require(GL11.glGetError() == GL11.GL_NO_ERROR, "Cloud field GPU probe left a GL error");
                return String.format(Locale.ROOT,
                        "GPU cloud field: %d body directions, fronts/poles/seams, max absolute CPU error=%.6f; E/pi reference; finite air analytic/zero/linear source; cloud centroid quadrature tau0..100; 27 filtered-mass quadratures; shared material/white response; occupied-shell Cartesian oracle; %d eager/pruned cases; diffuse sky/high-low-noon Sun/zero-linear source; %d Cartesian strata/budget cases PASS%n",
                        points.size(), maximumError, pruningCases, intervalCases);
            }
        } finally {
            for (int i = 0; i < pack.length; i++) { GL11.glPixelStorei(pack[i], previous[i]); }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        }
    }

    private static int verifyViewIntervals(ShaderInstance shader) throws Exception {
        int texture = 0, framebuffer = 0, count = 0;
        try (var state = new FullscreenPass(2)) {
            texture = TextureUtil.generateTextureId();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0); RenderSystem.bindTexture(texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 1, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, 0L);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            framebuffer = GL30.glGenFramebuffers(); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE,
                    "Cloud interval oracle could not allocate a float32 target");
            shader.safeGetUniform("ProbeMode").set(15);
            shader.safeGetUniform("CloudLayer").set(.85f, 8.5f);
            // Origin altitude, direction radial/tangent components, distance limit. Grazing cases are
            // formed below from the actual uploaded origin and desired Cartesian impact radius.
            var cases = new ArrayList<float[]>();
            cases.add(new float[] {6471, -1, 0, 200});
            cases.add(new float[] {6371.3f, 1, 0, 100});
            cases.add(new float[] {6374, 1, 0, 100});
            cases.add(new float[] {6471, 1, 0, 200});
            cases.add(new float[] {6471, -1, 0, 94});
            cases.add(new float[] {6373, 0, 1, 600});
            for (double altitude : new double[] {1.5, .1, -.1, 4.5}) {
                double tangential = (6371 + altitude) / 6471;
                cases.add(new float[] {6471, (float) -Math.sqrt(1 - tangential * tangential), (float) tangential, 2400});
            }
            for (float[] ray : cases) {
                List<double[]> expected = referenceIntervals(ray);
                shader.safeGetUniform("ProbePoint").set(ray[0], 0f, 0f);
                shader.safeGetUniform("ProbeRay").set(ray[1], ray[2], 0f);
                shader.safeGetUniform("ProbeSource").set(ray[3]);
                for (int budget : new int[] {8, 12, 24, 48}) {
                    shader.safeGetUniform("ProbeSteps").set(budget);
                    int total = 0;
                    double previousEnd = -1;
                    for (int i = 0; i < 6; i++) {
                        shader.safeGetUniform("ProbeCalendar").set(i);
                        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer); RenderSystem.viewport(0, 0, 1, 1);
                        FullscreenPass.draw(shader); float[] actual = readPixel();
                        require(actual[3] == expected.size(), "Cloud strata count disagrees with Cartesian occupancy");
                        if (i >= expected.size()) {
                            require(actual[0] == 0 && actual[1] == 0 && actual[2] == 0,
                                    "Unused cloud interval retained data");
                            continue;
                        }
                        double[] interval = expected.get(i);
                        // Cartesian float positions lose sub-meter radius information near a tangent;
                        //20m bounds that amplified root error, while the ordinary radial cases use1m.
                        double tolerance = Math.abs(ray[1]) < .5 && ray[2] != 0 ? .020 : .001;
                        require(Float.isFinite(actual[0]) && Float.isFinite(actual[1])
                                        && actual[0] >= 0 && actual[1] > actual[0] && actual[0] >= previousEnd
                                        && Math.abs(actual[0] - interval[0]) < tolerance
                                        && Math.abs(actual[1] - interval[1]) < tolerance,
                                "Cloud strata boundaries differ from Cartesian reference at " + java.util.Arrays.toString(ray)
                                        + "/" + i + ": " + java.util.Arrays.toString(actual)
                                        + " expected=" + java.util.Arrays.toString(interval));
                        require(actual[2] >= 1 && actual[2] == Math.rint(actual[2]),
                                "A positive cloud interval has no whole quadrature cell");
                        total += (int) actual[2]; previousEnd = actual[1];
                    }
                    require(total == (expected.isEmpty() ? 0 : budget), "Stratified cloud quadrature changed the exact sample budget");
                    count++;
                }
            }
        } finally {
            if (framebuffer != 0) { GL30.glDeleteFramebuffers(framebuffer); }
            if (texture != 0) { TextureUtil.releaseTextureId(texture); }
        }
        return count;
    }

    private static List<double[]> referenceIntervals(float[] ray) {
        var intervals = new ArrayList<double[]>();
        int samples = 131072, previous = radialBand(ray, 0);
        double step = ray[3] / (double) samples, start = previous > 0 ? 0 : Double.NaN;
        for (int i = 1; i <= samples; i++) {
            double distance = step * i;
            int band = radialBand(ray, distance);
            if (band != previous) {
                double lo = distance - step, hi = distance;
                for (int refinement = 0; refinement < 40; refinement++) {
                    double middle = (lo + hi) * .5;
                    if (radialBand(ray, middle) == previous) { lo = middle; } else { hi = middle; }
                }
                double boundary = (lo + hi) * .5;
                if (previous > 0) { intervals.add(new double[] {start, boundary}); }
                if (band < 0) { return intervals; }
                start = band > 0 ? boundary : Double.NaN;
                previous = band;
            }
        }
        if (previous > 0) { intervals.add(new double[] {start, ray[3]}); }
        return intervals;
    }

    private static int radialBand(float[] ray, double distance) {
        double radius = Math.hypot(ray[0] + ray[1] * distance, ray[2] * distance);
        if (radius <= 6371) { return -1; }
        if (radius <= 6371f + .85f || radius >= 6371f + 8.5f) { return 0; }
        if (radius < 6371f + 1.85f) { return 1; }
        return radius < 6371f + 4.2f ? 2 : 3;
    }

    private static void verifyDiffuseSky(ShaderInstance shader, AutoCloseable target,
                                         java.lang.reflect.Method bind) throws Exception {
        var optics = EarthAtmosphereOptics.bake(6371, () -> false);
        for (float height : new float[] {.85f, 2.7f, 8.5f}) {
            float radius = 6371f + height;
            shader.safeGetUniform("ProbePoint").set(radius, 0.0f, 0.0f);
            for (float mu : new float[] {-1, .1f, .3989466f, 1}) {
                shader.safeGetUniform("ProbeRay").set(mu, (float) Math.sqrt(1 - (double) mu * mu), 0.0f);
                var sky = optics.skyIrradiance(radius - 6371.0, mu);
                var solar = optics.transmission(radius - 6371.0, mu);
                double[] diffuse = {sky.x(), sky.y(), sky.z()}, direct = {solar.x(), solar.y(), solar.z()};
                shader.safeGetUniform("ProbeMode").set(14);
                bind.invoke(target); FullscreenPass.draw(shader);
                float[] actual = readPixel();
                for (int c = 0; c < 3; c++) {
                    require(Float.isFinite(actual[c]) && Math.abs(actual[c] - direct[c]) < .0015,
                            "Actual solar spectral LUT differs at cloud altitude " + height + "/" + mu);
                }
                shader.safeGetUniform("ProbeMode").set(13);
                for (float source : new float[] {0, .25f, 1, 2}) {
                    shader.safeGetUniform("ProbeSource").set(source);
                    bind.invoke(target); FullscreenPass.draw(shader); actual = readPixel();
                    for (int c = 0; c < 3; c++) {
                        double expected = diffuse[c] * source * Math.PI * (1.35 / 1.354);
                        require(Float.isFinite(actual[c]) && Math.abs(actual[c] - expected) < .00015 + expected * .001,
                                "Diffuse sky atlas/source disagrees at " + height + "/" + mu + "/" + source);
                        if (source == 0 || mu == -1) {
                            require(actual[c] == 0, "Diffuse atmosphere fabricated unilluminated sky light");
                        }
                    }
                }
            }
        }
    }

    private static int verifyActiveLayerPruning(ShaderInstance shader, AutoCloseable target,
                                               java.lang.reflect.Method bind) throws Exception {
        List<SpaceVector> directions = points();
        int cases = 0;
        for (int location = 44; location < directions.size(); location++) {
            var normal = directions.get(location).normalized();
            var tangent = new SpaceVector(-normal.z(), 0, normal.x()).normalized();
            for (float height : new float[] {0, .85f, 1.3f, 1.4f, 2.5f, 5, 8.5f, 9}) {
                var point = normal.multiply(6371 + height);
                shader.safeGetUniform("ProbePoint").set((float) point.x(), (float) point.y(), (float) point.z());
                shader.safeGetUniform("ProbeAngles").set(height, 0.0f);
                for (int path = 0; path < 3; path++) {
                    float step = path == 0 ? .1f : path == 1 ? 4 : 100;
                    var ray = path == 2 ? tangent : normal.multiply(path == 0 ? 1 : -1);
                    shader.safeGetUniform("ProbeRay").set((float) ray.x(), (float) ray.y(), (float) ray.z());
                    double r = 6371 + height;
                    double low = path == 2 ? height : height - step * .5;
                    double high = path == 2 ? Math.hypot(r, step * .5) - 6371 : height + step * .5;
                    shader.safeGetUniform("ProbeHeights").set((float) low, (float) high);
                    shader.safeGetUniform("ProbeSource").set(step);
                    shader.safeGetUniform("ProbeFootprints").set(path == 0 ? 0f : .3f, path == 2 ? 50f : .3f);
                    for (int fine = 0; fine <= 1; fine++) {
                        shader.safeGetUniform("ProbeCalendar").set(fine);
                        for (int pair = 0; pair < 2; pair++) {
                            shader.safeGetUniform("ProbeMode").set(9 + pair * 2);
                            bind.invoke(target); FullscreenPass.draw(shader); float[] actual = readPixel();
                            shader.safeGetUniform("ProbeMode").set(10 + pair * 2);
                            bind.invoke(target); FullscreenPass.draw(shader); float[] expected = readPixel();
                            for (int c = 0; c < 4; c++) {
                                require(Float.isFinite(actual[c]) && Float.isFinite(expected[c]) && actual[c] == expected[c],
                                        "Active-layer pruning changed observable shader output: location=" + location
                                                + ", h=" + height + ", path=" + path + ", fine=" + fine
                                                + ", pair=" + pair + ", channel=" + c
                                                + ", optimized=" + actual[c] + ", eager=" + expected[c]);
                            }
                        }
                        cases++;
                    }
                }
            }
        }
        return cases;
    }

    private static void verifyOccupiedSupport(ShaderInstance shader, AutoCloseable target,
                                               java.lang.reflect.Method bind) throws Exception {
        shader.safeGetUniform("ProbeMode").set(8);
        // point altitude, ray X/Y, half step, desired offset, active deck mask, then the three tops.
        float[][] cases = {{2, -1, 0, 4, -3, 1, 1.25f, 3, 6},
                {1, 1, 0, 4, 3, 1, 1.6f, 3, 6},
                {1, 0, 1, 150, 120, 1, 1.6f, 3, 6},
                {.2f, 0, 1, 200, 5, 1, 1.25f, 3, 6},
                {4, -1, 0, 6, 5, 7, 1.8f, 4.2f, 8.5f}};
        float[] bases = {.85f, 1.3f, 1.4f};
        for (int caseIndex = 0; caseIndex < cases.length; caseIndex++) {
            float[] sample = cases[caseIndex];
            float point = 6371f + sample[0], halfStep = sample[3], desired = sample[4];
            int mask = (int) sample[5];
            shader.safeGetUniform("ProbePoint").set(point, 0.0f, 0.0f);
            shader.safeGetUniform("ProbeRay").set(sample[1], sample[2], 0.0f);
            shader.safeGetUniform("ProbeAngles").set(halfStep, desired);
            shader.safeGetUniform("ProbeMass").set((mask & 1) != 0 ? 1f : 0f,
                    (mask & 2) != 0 ? 1f : 0f, (mask & 4) != 0 ? 1f : 0f);
            shader.safeGetUniform("ProbeTops").set(sample[6], sample[7], sample[8]);
            double first = Double.POSITIVE_INFINITY, last = Double.NEGATIVE_INFINITY, longest = 0;
            double nearest = Double.POSITIVE_INFINITY, nearestOffset = 0;
            int intervals = 131072;
            double step = 2.0 * halfStep / intervals;
            for (int deck = 0; deck < 3; deck++) {
                if ((mask & 1 << deck) == 0) { continue; }
                // Boundary additions use the uploaded float representation. Occupancy itself is
                // independently classified by dense Cartesian radius, without any sphere-root formula.
                double inner = 6371f + bases[deck], outer = 6371f + sample[6 + deck];
                double run = 0;
                for (int i = 0; i < intervals; i++) {
                    double offset = -halfStep + (i + .5) * step;
                    double radius = Math.hypot(point + sample[1] * offset, sample[2] * offset);
                    if (radius > inner && radius < outer) {
                        first = Math.min(first, offset); last = Math.max(last, offset);
                        run += step; longest = Math.max(longest, run);
                        double distance = Math.abs(offset - desired);
                        if (distance < nearest) { nearest = distance; nearestOffset = offset; }
                    } else { run = 0; }
                }
            }
            require(Double.isFinite(first), "Occupied-support reference case missed its shell");
            double[] expected = {first, last, longest, nearestOffset};
            bind.invoke(target); FullscreenPass.draw(shader);
            float[] actual = readPixel();
            for (int channel = 0; channel < 4; channel++) {
                double tolerance = .001 + step * 2 + Math.abs(expected[channel]) * .0006;
                require(Float.isFinite(actual[channel]) && Math.abs(actual[channel] - expected[channel]) < tolerance,
                        "Occupied cloud shell disagrees with independent Cartesian sampling: case=" + caseIndex
                                + ", channel=" + channel + ", gpu=" + actual[channel] + ", expected=" + expected[channel]);
            }
        }
    }

    private static void verifyMaterialResponse(ShaderInstance shader, AutoCloseable target,
                                               java.lang.reflect.Method bind) throws Exception {
        shader.safeGetUniform("ProbeMode").set(7);
        double whitePower = Math.pow(.9, 2.2), whiteResponse = whitePower / (1 - whitePower);
        for (float display : new float[] {0, .5f, .9f, .95f, .995f}) {
            double power = Math.pow(Math.clamp((double) display, 0, .995), 2.2);
            double expected = Math.min(32, power / Math.max(.001, 1 - power));
            shader.safeGetUniform("ProbeSource").set(display);
            bind.invoke(target); FullscreenPass.draw(shader);
            float[] actual = readPixel();
            for (int channel = 0; channel < 3; channel++) {
                require(Float.isFinite(actual[channel]) && actual[channel] >= 0 && actual[channel] <= 32
                                && Math.abs(actual[channel] - expected) < .0003 + expected * .0006,
                        "Shared Earth material response disagrees with host display decoding at " + display);
            }
            require(Math.abs(actual[3] - whiteResponse) < .003,
                    "Cloud reference does not use the shared neutral .90 material response");
        }
    }

    private static void verifyFilteredMass(ShaderInstance shader, AutoCloseable target,
                                           java.lang.reflect.Method bind) throws Exception {
        shader.safeGetUniform("ProbeMode").set(6);
        for (float region : new float[] {0, .4f, .9f}) {
            for (float mean : new float[] {.4f, .5f, .6f}) {
                for (float variance : new float[] {0, .0001f, .01f}) {
                    // Integrate the original nonlinear coverage directly, without its GLSL antiderivative.
                    double spread = Math.sqrt(3 * (double) variance), sum = 0;
                    int intervals = 4096;
                    for (int i = 0; i <= intervals; i++) {
                        double field = mean - spread + 2 * spread * i / intervals;
                        double threshold = .58 - region * .20;
                        double value = Math.clamp((field - threshold + .12) / .24, 0, 1);
                        double weight = i == 0 || i == intervals ? 1 : (i & 1) == 0 ? 2 : 4;
                        sum += weight * value * value * (3 - 2 * value);
                    }
                    double expected = region * sum / (3 * intervals);
                    shader.safeGetUniform("ProbePoint").set(region, mean, variance);
                    bind.invoke(target); FullscreenPass.draw(shader);
                    float actual = readPixel()[0];
                    require(Float.isFinite(actual) && actual >= 0 && actual <= region
                                    && Math.abs(actual - expected) < .0005,
                            "Filtered cloud mass disagrees with independent uniform quadrature: region=" + region
                                    + ", mean=" + mean + ", variance=" + variance
                                    + ", gpu=" + actual + ", expected=" + expected);
                    if (region == 0) { require(actual == 0, "Cloud filtering invented mass outside regional support"); }
                }
            }
        }
    }

    private static void verifyScatteringCentroid(ShaderInstance shader, AutoCloseable target,
                                                 java.lang.reflect.Method bind) throws Exception {
        shader.safeGetUniform("ProbeMode").set(3);
        float previous = .5f;
        for (float opticalDepth : new float[] {0, .000001f, .001f, .05f, .1f, 1, 5, 20, 100}) {
            // Independent numerical first moment, deliberately not the GLSL closed-form expression.
            double mass = 0, firstMoment = 0;
            int intervals = 8192;
            for (int i = 0; i <= intervals; i++) {
                double distance = (double) i / intervals;
                double weight = i == 0 || i == intervals ? 1 : (i & 1) == 0 ? 2 : 4;
                double attenuation = Math.exp(-opticalDepth * distance);
                mass += weight * attenuation;
                firstMoment += weight * distance * attenuation;
            }
            double expected = firstMoment / mass;
            shader.safeGetUniform("ProbeSource").set(opticalDepth);
            bind.invoke(target); FullscreenPass.draw(shader);
            float actual = readPixel()[0];
            require(Float.isFinite(actual) && actual > 0 && actual <= .5f && actual <= previous,
                    "Cloud scattering centroid left its bounded monotone interval at tau=" + opticalDepth);
            require(Math.abs(actual - expected) < .0003,
                    "Cloud scattering centroid disagrees with independent attenuation quadrature at tau="
                            + opticalDepth + ": gpu=" + actual + ", expected=" + expected);
            previous = actual;
        }
    }

    private static void verifyFiniteAir(ShaderInstance shader, AutoCloseable target,
                                        java.lang.reflect.Method bind) throws Exception {
        // For a vertical view toward the zenith Sun, camera-to-sample and sample-to-space
        // extinction add to the same total column. This gives an independent closed form.
        double rayleighSegment = 8 * (Math.exp(-.5 / 8) - Math.exp(-3.0 / 8));
        double mieSegment = 1.2 * (Math.exp(-.5 / 1.2) - Math.exp(-3.0 / 1.2));
        double rayleighTotal = 8 * (Math.exp(-.5 / 8) - Math.exp(-80.0 / 8));
        double mieTotal = 1.2 * (Math.exp(-.5 / 1.2) - Math.exp(-80.0 / 1.2));
        double phaseR = 3.0 / (8 * Math.PI), g = .76;
        double phaseM = (1 - g * g) / (4 * Math.PI * Math.pow(1 + g * g - 2 * g, 1.5));
        double[] rayleigh = {.0058, .0135, .0331}, ozone = {.00065, .00188, .00008};
        double[] scatter = new double[3], transmission = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            double solar = Math.exp(-rayleigh[channel] * rayleighTotal - .0032 * mieTotal - ozone[channel] * 15);
            scatter[channel] = Math.PI * (1.35 / 1.354) * solar
                    * (rayleigh[channel] * rayleighSegment * phaseR + .0032 * mieSegment * .9 * phaseM);
            transmission[channel] = Math.exp(-rayleigh[channel] * rayleighSegment - .0032 * mieSegment);
        }
        shader.safeGetUniform("ProbeMode").set(1);
        for (float source : new float[] {0, .25f, 1, 2}) {
            shader.safeGetUniform("ProbeSource").set(source);
            bind.invoke(target); FullscreenPass.draw(shader);
            float[] pixel = readPixel();
            for (int channel = 0; channel < 3; channel++) {
                require(Float.isFinite(pixel[channel]) && Math.abs(pixel[channel] - scatter[channel] * source)
                                < .0005 + scatter[channel] * source * .03,
                        "Finite foreground scattering disagrees with its independent vertical closed form");
                if (source == 0) { require(pixel[channel] == 0, "Unilluminated foreground air fabricated light"); }
            }
            require(Math.abs(pixel[3] - transmission[0]) < .002, "Source brightness changed foreground extinction");
        }
        shader.safeGetUniform("ProbeMode").set(2);
        bind.invoke(target); FullscreenPass.draw(shader);
        float[] pixel = readPixel();
        for (int channel = 0; channel < 3; channel++) {
            require(pixel[channel] >= 0 && pixel[channel] <= 1 && Math.abs(pixel[channel] - transmission[channel]) < .002,
                    "Finite foreground transmission disagrees with analytic spectral columns");
        }
    }

    private static float[] readPixel() {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
        try (var stack = MemoryStack.stackPush()) {
            var pixel = stack.mallocFloat(4);
            GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            return new float[] {pixel.get(0), pixel.get(1), pixel.get(2), pixel.get(3)};
        }
    }

    private static List<SpaceVector> points() {
        var result = new ArrayList<SpaceVector>();
        for (int i = 0; i < 48; i++) {
            double y = 1 - 2 * (i + .5) / 48, angle = i * Math.PI * (3 - Math.sqrt(5));
            double horizontal = Math.sqrt(1 - y * y);
            result.add(new SpaceVector(horizontal * Math.cos(angle), y, horizontal * Math.sin(angle)).multiply(6373));
        }
        double[][] angles = {{48,-28},{45,-30},{55,-15},{-43,52},{54,120},{-50,-132},{40,-110},{17,154},
                {0,179.999},{0,-179.999},{89.999,0},{-89.999,0},{35.2643897,45},{35.2643897,135},
                {-35.2643897,-45},{-35.2643897,-135}};
        for (double[] angle : angles) {
            double lat = Math.toRadians(angle[0]), lon = Math.toRadians(angle[1]);
            result.add(new SpaceVector(Math.cos(lat) * Math.cos(lon), Math.sin(lat), Math.cos(lat) * Math.sin(lon)).multiply(6373));
        }
        return result;
    }

    private static AutoCloseable create(String name) throws Exception {
        var constructor = Class.forName("dev.lexawhatt.astraengine.client.render." + name).getDeclaredConstructor();
        constructor.setAccessible(true); return (AutoCloseable) constructor.newInstance();
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
