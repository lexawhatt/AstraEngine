package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/** Native verification of the shipped GPU meter, adaptation and display composition on known HDR inputs. */
public final class ExposureGpuVerification {
    private ExposureGpuVerification() { }

    /** Runs on the render thread after Cosmos shaders register; uses only independently owned attachments. */
    public static String verify(CosmosRenderer renderer) throws Exception {
        var pipeline = field(renderer, "bloom");
        var registered = field(pipeline, "exposureMeter");
        var meterShader = (ShaderInstance) field(registered, "meter");
        var adaptShader = (ShaderInstance) field(registered, "adapt");
        require(meterShader != null && adaptShader != null, "Exposure shaders are unavailable");
        var compose = (ShaderInstance) field(pipeline, "composite");
        try (var saved = new FullscreenPass(); var masks = create("CelestialBloomPipeline$ColorState");
             var foreignUnpack = new ForeignUnpack();
             var source = createTarget(); var result = createTarget();
             var meter = create("CelestialExposure")) {
            set(meter, "meter", meterShader);
            set(meter, "adapt", adaptShader);
            fill(source, 0.18f);
            int state = render(meter, source);
            require(state != 0, "Exposure meter allocation failed");
            foreignUnpack.verifyRestored();
            float[] neutral = read();
            near(neutral[0], 0, .015f, "Neutral18% radiance changed exposure");
            fill(source, .72f);
            tick(meter, source);
            float[] brighter = read();
            near(brighter[1], -2, .015f, "Fourfold radiance must request minus two stops");
            require(brighter[0] < 0 && brighter[0] > -2, "Adaptation snapped or moved in the wrong direction");
            for (int i = 0; i < 40; i++) { tick(meter, source); }
            near(read()[0], -2, .015f, "Exposure did not converge");
            state = render(meter, source);
            float automatic = compose(compose, source, result, state, 1, true);
            near(automatic, display(.72f / 4), .006f, "Metered display does not use one global exposure");
            float compensated = compose(compose, source, result, state, 2, true);
            near(compensated, display(.72f / 2), .006f, "Manual compensation was lost or applied twice");
            float manual = compose(compose, source, result, state, 1, false);
            near(manual, display(.72f), .006f, "Fixed-exposure mode still applied the meter");
            fill(source, 0);
            tick(meter, source);
            float[] dark = read();
            near(dark[1], 2, .001f, "Black frame must retain the bounded fourfold gain");
            require(dark[0] > -2 && dark[0] < 0, "Dark adaptation was unbounded or instant");
            meter.close();
            fill(source, 0.18f);
            render(meter, source);
            near(read()[0], 0, .015f, "Disposed history contaminated the next camera lifetime");
            meter.close();
            fillSplit(source);
            render(meter, source);
            // Equal areas at .18 and .72 have geometric mean .36, hence minus one stop.
            // Linear filtering at the single boundary introduces a small, bounded integration error.
            near(read()[0], -1, .025f, "Spatial luminance reduction biased equal-area radiance");
            foreignUnpack.verifyRestored();
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Exposure allocation or drawing left a GL error");
            return "GPU exposure: neutral=0EV; fourfold=-2EV; bounded adaptation; single composition; "
                    + "manual bypass; black<=+2EV; history disposal; mixed-field reduction; foreign unpack PBO PASS\n";
        }
    }

    private static void tick(Object meter, Object source) throws Exception {
        // Feed a deterministic100ms presentation interval. Simulation and the actual camera clock stay untouched.
        set(meter, "lastFrame", System.nanoTime() - 100_000_000L);
        render(meter, source);
    }

    private static float compose(ShaderInstance shader, Object source, Object output,
                                 int meter, float compensation, boolean automatic) throws Exception {
        invoke(output, "bind");
        shader.setSampler("SceneColor", (int) invoke(source, "texture"));
        shader.setSampler("BloomColor", (int) invoke(source, "texture"));
        shader.setSampler("ExposureState", meter);
        shader.safeGetUniform("BloomStrength").set(0.0f);
        shader.safeGetUniform("Exposure").set(compensation);
        shader.safeGetUniform("AutoExposure").set(automatic ? 1 : 0);
        FullscreenPass.draw(shader);
        return read()[0];
    }

    private static void fill(Object source, float value) throws Exception {
        invoke(source, "bind");
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[] {value, value, value, 1});
    }

    private static void fillSplit(Object source) throws Exception {
        boolean enabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] box = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
        try {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            fill(source, .18f);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(16, 0, 16, 32);
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[] {.72f, .72f, .72f, 1});
        } finally {
            GL11.glScissor(box[0], box[1], box[2], box[3]);
            if (enabled) { GL11.glEnable(GL11.GL_SCISSOR_TEST); }
            else { GL11.glDisable(GL11.GL_SCISSOR_TEST); }
        }
    }

    private static float[] read() {
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] parameters = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS,
                GL11.GL_PACK_SKIP_PIXELS, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES,
                GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST};
        int[] previous = new int[parameters.length];
        for (int i = 0; i < parameters.length; i++) { previous[i] = GL11.glGetInteger(parameters[i]); }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int i = 0; i < parameters.length; i++) { GL11.glPixelStorei(parameters[i], i == 0 ? 1 : 0); }
            FloatBuffer pixel = stack.mallocFloat(4);
            GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            return new float[] {pixel.get(0), pixel.get(1), pixel.get(2), pixel.get(3)};
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            for (int i = 0; i < parameters.length; i++) { GL11.glPixelStorei(parameters[i], previous[i]); }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
        }
    }

    /** A short foreign transfer buffer must not become an implicit texture-data source during allocation. */
    private static final class ForeignUnpack implements AutoCloseable {
        private final int previous = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        private final int buffer = GL15.glGenBuffers();

        ForeignUnpack() {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, 16L, GL15.GL_STREAM_DRAW);
        }

        void verifyRestored() {
            require(GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) == buffer,
                    "Exposure allocation did not restore the foreign unpack PBO");
        }

        @Override public void close() {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, previous);
            GL15.glDeleteBuffers(buffer);
        }
    }

    private static AutoCloseable create(String name) throws Exception {
        var constructor = Class.forName("dev.lexawhatt.astraengine.client.render." + name).getDeclaredConstructor();
        constructor.setAccessible(true);
        return (AutoCloseable) constructor.newInstance();
    }
    private static AutoCloseable createTarget() throws Exception {
        var constructor = Class.forName("dev.lexawhatt.astraengine.client.render.HdrColorTarget")
                .getDeclaredConstructor(int.class, int.class);
        constructor.setAccessible(true);
        return (AutoCloseable) constructor.newInstance(32, 32);
    }
    private static int render(Object meter, Object source) throws Exception {
        var method = meter.getClass().getDeclaredMethod("render", source.getClass());
        method.setAccessible(true);
        return (int) method.invoke(meter, source);
    }
    private static Object invoke(Object owner, String name) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(owner);
    }

    private static float display(float radiance) { return (float) Math.pow(radiance / (1 + radiance), 1 / 2.2); }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void near(float actual, float expected, float tolerance, String message) {
        require(Float.isFinite(actual) && Math.abs(actual - expected) <= tolerance, message + ": " + actual);
    }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
