package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.systems.RenderSystem;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeBufferRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.neoforged.fml.ModList;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;

/** Optional DH 7.2 API instrumentation confined to disposable native verification profiles. */
final class DistantTerrainProbe implements AutoCloseable {
    private static final String API_OWNER = "AstraEngine native verification";
    private static final int MAX_DEPTH_PIXELS = 8_388_608;
    private final List<Runnable> restorations = new ArrayList<>();
    private final BufferListener listener = new BufferListener();
    private IDhApiConfigValue<Integer> radius;
    private IDhApiConfigValue<EDhApiRendererMode> renderer;
    private boolean configured;
    private boolean bound;
    private boolean closed;
    private long bufferRenders;
    private String lastDimension = "none";
    private String lastPass = "none";

    /** Checks optional mod presence without initializing any DH API state. */
    static boolean available() { return ModList.get().isLoaded("distanthorizons"); }

    /** Construct on the client thread only in a profile that explicitly includes the original DH artifact. */
    DistantTerrainProbe() {
        if (!available()) { throw new IllegalStateException("Distant Horizons is absent from the verification profile"); }
    }

    /**
     * Returns false while DH's delayed API is unavailable; otherwise enables real chunk generation and 32-chunk LODs.
     * Rejected overrides fail the fixture. Every changed API override is restored by close, including partial setup.
     */
    boolean configure() {
        requireOpen();
        if (configured) { return true; }
        if (DhApi.Delayed.configs == null || DhApi.Delayed.renderProxy == null) { return false; }
        if (DhApi.getApiMajorVersion() != 7 || DhApi.getApiMinorVersion() < 2) {
            throw new IllegalStateException("Distant terrain verification requires DH API 7.2, found " + apiVersion());
        }
        try {
            radius = DhApi.Delayed.configs.graphics().chunkRenderDistance();
            renderer = DhApi.Delayed.configs.graphics().renderingMode();
            override(radius, 32, "chunk radius");
            override(renderer, EDhApiRendererMode.DEFAULT, "renderer mode");
            // DH's rough surface shortcut need not match a custom ChunkGenerator. Exercise the real generator.
            override(DhApi.Delayed.configs.worldGenerator().GeneratorPlan(), EDhApiGeneratorPlan.CHUNKS_ONLY,
                    "generator plan");
            DhApi.events.bind(DhApiBeforeBufferRenderEvent.class, listener);
            bound = true;
            configured = true;
            return true;
        } catch (RuntimeException failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Uploaded-buffer callbacks, not a count of GPU draw calls or proof that fragments reached the screen. */
    long bufferRenders() { return bufferRenders; }

    /** Starts a fresh observation window after the test has reached its actual target dimension. */
    void resetCounters() {
        requireConfigured();
        bufferRenders = 0;
        lastDimension = "none";
        lastPass = "none";
    }

    /** Changes only this fixture's API override; no debug rhombus or substitute LOD geometry is enabled. */
    void renderEnabled(boolean enabled) {
        requireConfigured();
        set(renderer, enabled ? EDhApiRendererMode.DEFAULT : EDhApiRendererMode.DISABLED, "renderer mode");
    }

    /** Restricts native tests to explicit regression or recorded user-profile radii, in Minecraft chunks. */
    void radiusChunks(int chunks) {
        requireConfigured();
        if (chunks != 32 && chunks != 64 && chunks != 256) {
            throw new IllegalArgumentException("Probe radius must be 32, 64 or 256 chunks");
        }
        set(radius, chunks, "chunk radius");
    }

    /**
     * Render thread, capture/report points only: depth readback is synchronous and unsuitable for per-frame polling.
     * Texture allocation and callback counts alone do not establish visible LOD coverage; retain on/off screenshots.
     */
    String description() {
        requireOpen();
        if (!configured) { return "DH API pending"; }
        RenderSystem.assertOnRenderThread();
        return String.format(Locale.ROOT, "DH %s / API %s / radius=%d / renderer=%s / generator=%s"
                        + " / bufferCallbacks=%d / lastDimension=%s / lastPass=%s / depth=%s",
                DhApi.getModVersion(), apiVersion(), radius.getValue(), renderer.getValue(),
                DhApi.Delayed.configs.worldGenerator().GeneratorPlan().getValue(), bufferRenders,
                lastDimension, lastPass, depthCoverage());
    }

    private String depthCoverage() {
        var proxy = DhApi.Delayed.renderProxy;
        var result = proxy.getDhDepthTextureGlId();
        if (!result.success || result.payload == null || result.payload <= 0) {
            return "unavailable(" + result.message + ")";
        }
        int texture = result.payload;
        if (!GL11.glIsTexture(texture)) { throw new IllegalStateException("DH returned a non-texture depth ID " + texture); }
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] parameters = { GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS,
                GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_SWAP_BYTES, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES };
        int[] previous = new int[parameters.length];
        for (int i = 0; i < parameters.length; i++) { previous[i] = GL11.glGetInteger(parameters[i]); }
        FloatBuffer depth = null;
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            long count = (long) width * height;
            if (width <= 0 || height <= 0 || count > MAX_DEPTH_PIXELS) {
                return "readback_skipped(" + width + "x" + height + ")";
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int parameter : parameters) { GL11.glPixelStorei(parameter, parameter == GL11.GL_PACK_ALIGNMENT ? 4 : 0); }
            depth = MemoryUtil.memAllocFloat((int) count);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            float clear = proxy.getDepthDirection().farDepth;
            int covered = 0;
            for (int i = 0; i < count; i++) {
                float value = depth.get(i);
                if (!Float.isFinite(value) || value < 0 || value > 1) {
                    throw new IllegalStateException("DH depth readback contains invalid normalized depth at " + i);
                }
                if (value != clear) { covered++; }
            }
            return String.format(Locale.ROOT, "%dx%d:covered=%d/%d(%.5f),direction=%s,range=%s",
                    width, height, covered, count, (double) covered / count,
                    proxy.getDepthDirection(), proxy.getDepthRange());
        } finally {
            if (depth != null) { MemoryUtil.memFree(depth); }
            for (int i = 0; i < parameters.length; i++) { GL11.glPixelStorei(parameters[i], previous[i]); }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
    }

    private <T> void override(IDhApiConfigValue<T> option, T value, String name) {
        T previous = option.getApiValue();
        restorations.add(() -> set(option, previous, name + " restore"));
        set(option, value, name);
    }

    private static <T> void set(IDhApiConfigValue<T> option, T value, String name) {
        if (!option.setValue(value, API_OWNER)) { throw new IllegalStateException("DH rejected " + name + " = " + value); }
        if (!java.util.Objects.equals(value, option.getApiValue())) {
            throw new IllegalStateException("DH did not retain requested API override for " + name);
        }
    }

    private static String apiVersion() {
        return DhApi.getApiMajorVersion() + "." + DhApi.getApiMinorVersion() + "." + DhApi.getApiPatchVersion();
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("Distant terrain probe has already closed"); }
    }

    private void requireConfigured() {
        requireOpen();
        if (!configured) { throw new IllegalStateException("Distant terrain probe is not configured"); }
    }

    /** Removes the event observer and restores all prior API override values; never deletes world or DH cache data. */
    @Override
    public void close() {
        if (closed) { return; }
        RuntimeException failure = null;
        if (bound) {
            try {
                if (!DhApi.events.unbind(DhApiBeforeBufferRenderEvent.class, BufferListener.class)) {
                    throw new IllegalStateException("DH did not remove the terrain verification callback");
                }
            } catch (RuntimeException problem) { failure = problem; }
            bound = false;
        }
        for (int i = restorations.size() - 1; i >= 0; i--) {
            try { restorations.get(i).run(); }
            catch (RuntimeException problem) {
                if (failure == null) { failure = problem; } else { failure.addSuppressed(problem); }
            }
        }
        restorations.clear();
        configured = false;
        closed = true;
        if (failure != null) { throw failure; }
    }

    private final class BufferListener extends DhApiBeforeBufferRenderEvent {
        @Override
        public void beforeRender(DhApiEventParam<EventParam> event) {
            bufferRenders++;
            lastPass = String.valueOf(event.value.renderPass);
            if (event.value.clientLevelWrapper != null) {
                lastDimension = event.value.clientLevelWrapper.getDimensionName();
            }
        }
    }
}
