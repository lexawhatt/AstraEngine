package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/**
 * Client-local editable full-screen GLSL pass. Explicit Apply compiles on the render thread;
 * failure retains the last linked program. File work runs on Minecraft's I/O pool and never
 * compiles automatically. This is trusted local GPU code, not a shader sandbox.
 */
public final class EditableShaderPass implements AutoCloseable {
    public static final int MAX_SOURCE_BYTES = 64 * 1024;
    private static final int MAX_DIAGNOSTIC_CHARS = 32 * 1024;
    private static final String VERTEX_SOURCE = """
            #version 150
            out vec2 texCoord;
            out vec2 clipPosition;
            void main() {
                vec2 positions[3] = vec2[3](vec2(-1, -1), vec2(3, -1), vec2(-1, 3));
                clipPosition = positions[gl_VertexID];
                texCoord = clipPosition * 0.5 + 0.5;
                gl_Position = vec4(clipPosition, 0, 1);
            }
            """;
    public static final String EXAMPLE_SOURCE = """
            #version 150
            uniform sampler2D SceneColor;
            uniform sampler2D SceneDepth;
            uniform float Time;
            uniform vec2 ScreenSize;
            in vec2 texCoord;
            out vec4 fragColor;

            void main() {
                vec4 scene = texture(SceneColor, texCoord);
                vec2 p = (texCoord - 0.5) * vec2(ScreenSize.x / ScreenSize.y, 1.0);
                float vignette = 1.0 - 0.22 * smoothstep(0.2, 0.8, length(p));
                float pulse = 0.015 * (0.5 + 0.5 * sin(Time));
                vec3 color = scene.rgb * vec3(0.94, 1.02, 1.10) * vignette;
                fragColor = vec4(color + vec3(0.0, pulse, pulse), scene.a);
            }
            """;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final Path directory = minecraft.gameDirectory.toPath().resolve("config/astraengine/shaders")
            .toAbsolutePath().normalize();
    private String draft = EXAMPLE_SOURCE;
    private String appliedSource = "";
    private String diagnostics = "";
    private String dimension;
    private Component status = text("ready");
    private int program;
    private int vertexArray;
    private int colorLocation;
    private int depthLocation;
    private int timeLocation;
    private int sizeLocation;
    private int inverseLocation;
    private int viewLocation;
    private RenderTarget scene;
    private boolean enabled;
    private boolean busy;
    private long generation;
    private long draftRevision;
    private long startedAt;

    public String draft() { return draft; }
    public String appliedSource() { return appliedSource; }
    public String diagnostics() { return diagnostics; }
    public Component status() { return status; }
    public boolean hasProgram() { return program != 0; }
    public boolean enabled() { return enabled; }
    public boolean busy() { return busy; }

    /** Stores bounded editor text without compiling it; the UTF-8 byte limit is checked on Apply/Save. */
    public void setDraft(String source) {
        if (source == null || source.length() > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("Shader draft must contain at most " + MAX_SOURCE_BYTES + " characters");
        }
        if (!draft.equals(source)) {
            draft = source;
            draftRevision++;
        }
    }

    /** Enables only a successfully linked program. Disabling releases the intermediate framebuffer. */
    public void setEnabled(boolean value) {
        RenderSystem.assertOnRenderThread();
        enabled = value && hasProgram();
        if (!enabled) { releaseScene(); }
    }

    /**
     * Compiles and atomically replaces the active program for the current dimension. Returns false
     * with driver diagnostics on validation/compilation/link failure; the previous effect survives.
     * The render thread performs driver compilation only after this explicit call.
     */
    public boolean apply(String source) {
        RenderSystem.assertOnRenderThread();
        int vertex = 0;
        int fragment = 0;
        int candidate = 0;
        try {
            requireSource(source);
            String version = source.stripLeading().lines().findFirst().orElse("");
            if (!version.equals("#version 150") && !version.equals("#version 150 core")) {
                throw new IllegalArgumentException("The first nonblank line must be #version 150 or #version 150 core");
            }
            if (minecraft.level == null) {
                throw new IllegalArgumentException("Join a world before applying a scene shader");
            }
            StringBuilder messages = new StringBuilder();
            vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX_SOURCE, "Vertex", messages);
            fragment = compile(GL20.GL_FRAGMENT_SHADER, source, "Fragment", messages);
            candidate = GL20.glCreateProgram();
            if (candidate == 0) { throw new IllegalArgumentException("The driver could not allocate a shader program"); }
            GL20.glAttachShader(candidate, vertex);
            GL20.glAttachShader(candidate, fragment);
            GL30.glBindFragDataLocation(candidate, 0, "fragColor");
            GL20.glLinkProgram(candidate);
            String log = GL20.glGetProgramInfoLog(candidate, MAX_DIAGNOSTIC_CHARS);
            if (!log.isBlank()) { messages.append("Link:\n").append(log).append('\n'); }
            if (GL20.glGetProgrami(candidate, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalArgumentException("Shader link failed.\n" + messages);
            }
            validateInterface(candidate);
            GL20.glDetachShader(candidate, vertex);
            GL20.glDetachShader(candidate, fragment);
            int previous = program;
            program = candidate;
            candidate = 0;
            colorLocation = GL20.glGetUniformLocation(program, "SceneColor");
            depthLocation = GL20.glGetUniformLocation(program, "SceneDepth");
            timeLocation = GL20.glGetUniformLocation(program, "Time");
            sizeLocation = GL20.glGetUniformLocation(program, "ScreenSize");
            inverseLocation = GL20.glGetUniformLocation(program, "InverseViewProjection");
            viewLocation = GL20.glGetUniformLocation(program, "ViewProjection");
            if (previous != 0) { GL20.glDeleteProgram(previous); }
            appliedSource = source;
            dimension = minecraft.level.dimension().location().toString();
            startedAt = System.nanoTime();
            enabled = true;
            diagnostics = limit(messages.isEmpty() ? "Compilation and linking succeeded." : messages.toString());
            status = text("applied");
            return true;
        } catch (IllegalArgumentException failure) {
            diagnostics = limit(failure.getMessage());
            status = text("failed");
            return false;
        } finally {
            if (candidate != 0) { GL20.glDeleteProgram(candidate); }
            if (fragment != 0) { GL20.glDeleteShader(fragment); }
            if (vertex != 0) { GL20.glDeleteShader(vertex); }
        }
    }

    private static int compile(int type, String source, String label, StringBuilder messages) {
        int shader = GL20.glCreateShader(type);
        if (shader == 0) { throw new IllegalArgumentException("The driver could not allocate the " + label + " shader"); }
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        String log = GL20.glGetShaderInfoLog(shader, MAX_DIAGNOSTIC_CHARS);
        if (!log.isBlank()) { messages.append(label).append(":\n").append(log).append('\n'); }
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            GL20.glDeleteShader(shader);
            throw new IllegalArgumentException(label + " compilation failed.\n" + messages);
        }
        return shader;
    }

    private static void validateInterface(int candidate) {
        if (GL30.glGetFragDataLocation(candidate, "fragColor") != 0) {
            throw new IllegalArgumentException("The fragment shader must write out vec4 fragColor");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            int uniforms = GL20.glGetProgrami(candidate, GL20.GL_ACTIVE_UNIFORMS);
            for (int index = 0; index < uniforms; index++) {
                String name = GL20.glGetActiveUniform(candidate, index, size, type);
                int arrayStart = name.indexOf('[');
                String baseName = arrayStart < 0 ? name : name.substring(0, arrayStart);
                int expected = switch (baseName) {
                    case "SceneColor", "SceneDepth" -> GL20.GL_SAMPLER_2D;
                    case "Time" -> GL11.GL_FLOAT;
                    case "ScreenSize" -> GL20.GL_FLOAT_VEC2;
                    case "InverseViewProjection", "ViewProjection" -> GL20.GL_FLOAT_MAT4;
                    default -> 0;
                };
                if (expected == 0) {
                    throw new IllegalArgumentException("Unsupported active uniform: " + name
                            + ". Use supplied uniforms or source constants");
                }
                if (arrayStart >= 0 || type.get(0) != expected || size.get(0) != 1) {
                    throw new IllegalArgumentException("Shader uniform has an incompatible type: " + name);
                }
            }
        }
    }

    /**
     * Runs at AFTER_LEVEL after engine composition, before hand and UI. Samplers use copied color
     * and OpenGL nonlinear depth; texCoord has a bottom-left origin. Matrices reconstruct camera-
     * relative world positions. Time is elapsed seconds since Apply, ScreenSize is framebuffer pixels.
     * Other dimensions retain their own normal rendering; returning resumes this local effect.
     */
    public void render(RenderLevelStageEvent event) {
        if (!enabled || program == 0 || minecraft.level == null
                || !minecraft.level.dimension().location().toString().equals(dimension)) {
            releaseScene();
            return;
        }
        RenderTarget main = minecraft.getMainRenderTarget();
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        try (var state = new FullscreenPass()) {
            ensureScene(main);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scene.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, scene.width, scene.height,
                    GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            main.bindWrite(true);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            GlStateManager._glUseProgram(program);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(scene.getColorTextureId());
            RenderSystem.activeTexture(GL13.GL_TEXTURE1);
            RenderSystem.bindTexture(scene.getDepthTextureId());
            GL20.glUniform1i(colorLocation, 0);
            GL20.glUniform1i(depthLocation, 1);
            GL20.glUniform1f(timeLocation, (float) ((System.nanoTime() - startedAt) / 1_000_000_000.0));
            GL20.glUniform2f(sizeLocation, main.width, main.height);
            if (inverseLocation >= 0 || viewLocation >= 0) {
                Matrix4f matrix = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var buffer = stack.mallocFloat(16);
                    GL20.glUniformMatrix4fv(viewLocation, false, matrix.get(buffer));
                    GL20.glUniformMatrix4fv(inverseLocation, false, matrix.invert().get(buffer));
                }
            }
            if (vertexArray == 0) { vertexArray = GL30.glGenVertexArrays(); }
            GL30.glBindVertexArray(vertexArray);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally {
            GL30.glBindVertexArray(previousVertexArray);
        }
    }

    private void ensureScene(RenderTarget main) {
        if (scene != null && scene.width == main.width && scene.height == main.height
                && scene.isStencilEnabled() == main.isStencilEnabled()) { return; }
        destroyScene();
        scene = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        if (main.isStencilEnabled()) { scene.enableStencil(); }
    }

    private void destroyScene() {
        if (scene != null) { scene.destroyBuffers(); scene = null; }
    }

    private void releaseScene() {
        if (scene != null) {
            try (var state = new FullscreenPass()) { destroyScene(); }
        }
    }

    /** Saves a bounded text snapshot asynchronously and atomically; saving never compiles. */
    public void save(String name) {
        if (busy) { return; }
        String snapshot = draft;
        long token = generation;
        busy = true;
        status = text("saving");
        CompletableFuture.runAsync(() -> {
            try { writeSource(name, snapshot); }
            catch (IOException | IllegalArgumentException failure) { throw new CompletionException(failure); }
        }, Util.ioPool()).whenComplete((ignored, failure) -> minecraft.execute(() -> {
            if (token != generation) { return; }
            busy = false;
            if (failure == null) { status = text("saved", name); }
            else { ioFailure(failure); }
        }));
    }

    /** Loads text asynchronously without compiling; edits made during the load win over its result. */
    public void load(String name) {
        if (busy) { return; }
        long token = generation;
        long revision = draftRevision;
        busy = true;
        status = text("loading");
        CompletableFuture.supplyAsync(() -> {
            try { return readSource(name); }
            catch (IOException | IllegalArgumentException failure) { throw new CompletionException(failure); }
        }, Util.ioPool()).whenComplete((loaded, failure) -> minecraft.execute(() -> {
            if (token != generation) { return; }
            busy = false;
            if (failure != null) { ioFailure(failure); }
            else if (revision != draftRevision) { status = text("stale"); }
            else { setDraft(loaded); status = text("loaded", name); }
        }));
    }

    private void ioFailure(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        diagnostics = limit(cause.getMessage());
        status = text("io_failed");
    }

    private Path file(String name) {
        if (name == null || !name.matches("[a-z0-9_-]{1,48}")) {
            throw new IllegalArgumentException("Shader name must contain 1-48 lowercase letters, digits, '_' or '-'");
        }
        return directory.resolve(name + ".fsh");
    }

    private void requireDirectory() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Shader directory must be a regular directory: " + directory);
        }
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) { throw new IOException("Shader symlinks are not supported: " + path.getFileName()); }
    }

    private void writeSource(String name, String source) throws IOException {
        requireSource(source);
        Path target = file(name);
        Files.createDirectories(directory);
        requireDirectory();
        rejectSymlink(target);
        Path temporary = Files.createTempFile(directory, ".astra-shader-", ".tmp");
        try {
            Files.writeString(temporary, source, StandardCharsets.UTF_8);
            rejectSymlink(target);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private String readSource(String name) throws IOException {
        Path target = file(name);
        requireDirectory();
        rejectSymlink(target);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Shader source is not a regular file: " + target.getFileName());
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_SOURCE_BYTES + 1);
        }
        if (bytes.length > MAX_SOURCE_BYTES) { throw new IOException("Shader exceeds " + MAX_SOURCE_BYTES + " bytes"); }
        String source = new String(bytes, StandardCharsets.UTF_8);
        requireSource(source);
        return source;
    }

    private static void requireSource(String source) {
        if (source == null || source.length() > MAX_SOURCE_BYTES
                || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("Shader source must contain at most " + MAX_SOURCE_BYTES + " UTF-8 bytes");
        }
    }

    private static String limit(String message) {
        String result = message == null ? "Unknown shader operation failure" : message;
        return result.length() <= MAX_DIAGNOSTIC_CHARS ? result : result.substring(0, MAX_DIAGNOSTIC_CHARS);
    }

    private static Component text(String key, Object... arguments) {
        return Component.translatable("astraengine.shader." + key, arguments);
    }

    /** Releases the GPU program and pending callbacks; keeps editable text across resource reloads. */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        generation++;
        busy = false;
        enabled = false;
        releaseScene();
        if (program != 0) { GL20.glDeleteProgram(program); program = 0; }
        if (vertexArray != 0) { GL30.glDeleteVertexArrays(vertexArray); vertexArray = 0; }
        appliedSource = "";
        dimension = null;
        status = text("released");
    }

    /** Ends the connection, releases owned GPU resources and clears unsaved session text. */
    public void resetSession() {
        close();
        draft = EXAMPLE_SOURCE;
        draftRevision++;
        diagnostics = "";
        status = text("ready");
    }
}
