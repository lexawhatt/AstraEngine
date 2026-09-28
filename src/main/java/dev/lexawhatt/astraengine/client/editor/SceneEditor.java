package dev.lexawhatt.astraengine.client.editor;

import dev.lexawhatt.astraengine.client.lighting.CollectSceneLightsEvent;
import dev.lexawhatt.astraengine.client.lighting.LightVector;
import dev.lexawhatt.astraengine.client.lighting.SceneLight;
import dev.lexawhatt.astraengine.client.render.RenderOptions;
import dev.lexawhatt.astraengine.client.render.EditableShaderPass;
import dev.lexawhatt.astraengine.client.scene.SceneDocument;
import dev.lexawhatt.astraengine.client.scene.SceneHistory;
import dev.lexawhatt.astraengine.client.scene.SceneObject;
import dev.lexawhatt.astraengine.client.scene.ScenePresets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Client-thread scene draft and input owner. Disconnect invalidates pending UI callbacks and clears the draft. */
public final class SceneEditor {
    private final Minecraft minecraft = Minecraft.getInstance();
    private final KeyMapping openKey = new KeyMapping("key.astraengine.editor", GLFW.GLFW_KEY_F7, "key.categories.astraengine");
    private final ScenePresets presets;
    private final RenderOptions options;
    private final EditableShaderPass shaders = new EditableShaderPass();
    private SceneHistory history;
    private String selected;
    private boolean preview = true;
    private boolean openRequested;
    private boolean busy;
    private long generation;
    private long listRequest;
    private int revision;
    private List<String> presetNames = List.of();
    private Component status = text("ready");

    /** Creates the client service; disk access occurs only on explicit preset operations. */
    public SceneEditor(RenderOptions options) {
        this.options = options;
        presets = new ScenePresets(minecraft.gameDirectory.toPath().resolve("config/astraengine/scenes"));
    }

    public void registerKeys(RegisterKeyMappingsEvent event) { event.register(openKey); }

    /** Keeps chat/hotbar overlays out of the inspector without changing the player's hide-GUI option. */
    public void hideHud(RenderGuiEvent.Pre event) {
        if (minecraft.screen instanceof SceneEditorScreen || minecraft.screen instanceof ShaderEditorScreen) {
            event.setCanceled(true);
        }
    }

    public void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("astra-editor").executes(context -> {
            openRequested = true;
            return 1;
        }));
    }

    /** Opens after the chat command has closed its own screen, preserving normal mouse focus handling. */
    public void tick(ClientTickEvent.Post event) {
        while (openKey.consumeClick()) {
            if (minecraft.screen == null) { openRequested = true; }
        }
        if (openRequested && minecraft.screen == null) {
            openRequested = false;
            if (minecraft.level != null && minecraft.player != null) {
                if (history == null) { history = new SceneHistory(SceneDocument.empty(dimension())); }
                minecraft.setScreen(new SceneEditorScreen(this));
                refreshPresets();
            }
        }
    }

    /** A saved snapshot may finish writing after disconnect; its callback cannot attach to the next connection. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        shaders.resetSession();
        generation++;
        history = null;
        selected = null;
        busy = false;
        preview = true;
        openRequested = false;
        presetNames = List.of();
        status = text("ready");
        revision++;
    }

    public SceneDocument document() { return history == null ? null : history.current(); }
    public boolean busy() { return busy; }
    public boolean preview() { return preview; }
    public Component status() { return status; }
    public int revision() { return revision; }
    public RenderOptions options() { return options; }
    public EditableShaderPass shaders() { return shaders; }
    public List<String> presetNames() { return presetNames; }
    public boolean canUndo() { return history != null && history.canUndo() && !busy; }
    public boolean canRedo() { return history != null && history.canRedo() && !busy; }
    public boolean inDimension() { return document() != null && document().dimension().equals(dimension()); }

    /** Immutable render snapshot; dimension mismatch suppresses presentation without deleting the draft. */
    public List<SceneObject> visibleObjects() {
        return preview && inDimension() ? document().objects() : List.of();
    }

    public SceneObject selected() {
        return document() == null ? null : document().objects().stream().filter(object -> object.id().equals(selected)).findFirst().orElse(null);
    }

    public void select(String id) { selected = id; revision++; }
    public void togglePreview() { preview = !preview; revision++; }

    public void newScene() {
        if (busy || minecraft.level == null) { return; }
        if (inDimension()) { history.commit(SceneDocument.empty(dimension())); }
        else { history = new SceneHistory(SceneDocument.empty(dimension())); }
        selected = null;
        changed();
    }

    public void create(SceneObject.Kind kind) {
        if (busy || !inDimension()) { return; }
        var camera = minecraft.gameRenderer.getMainCamera();
        var point = camera.getPosition();
        var direction = camera.getLookVector();
        try {
            SceneObject object = SceneObject.create(nextId(kind), kind,
                    new LightVector(point.x + direction.x() * 6, point.y + direction.y() * 6, point.z + direction.z() * 6));
            apply(object);
        } catch (IllegalArgumentException invalid) { error(invalid); }
    }

    public void apply(SceneObject object) {
        if (busy) { return; }
        try {
            history.commit(document().withObject(object));
            selected = object.id();
            changed();
        } catch (IllegalArgumentException invalid) { error(invalid); }
    }

    public void duplicate() {
        SceneObject object = selected();
        if (busy || object == null) { return; }
        try {
            apply(new SceneObject(nextId(object.kind()), object.kind(),
                    new LightVector(object.position().x() + 1, object.position().y(), object.position().z()),
                    object.rotation(), object.scale(), object.color(), object.emission(), object.innerRadius(),
                    object.intensity(), object.range(), object.innerDegrees(), object.outerDegrees(), object.visible()));
        } catch (IllegalArgumentException invalid) { error(invalid); }
    }

    public void delete() {
        if (busy || selected() == null) { return; }
        history.commit(document().withoutObject(selected));
        selected = null;
        changed();
    }

    public void undo() { if (canUndo()) { history.undo(); changed(); } }
    public void redo() { if (canRedo()) { history.redo(); changed(); } }

    public void save(String name) {
        if (busy || document() == null) { return; }
        SceneDocument snapshot = document();
        long token = startIo();
        CompletableFuture.runAsync(() -> {
            try { presets.save(name, snapshot); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, Util.ioPool()).whenComplete((ignored, failure) -> minecraft.execute(() -> {
            if (token != generation) { return; }
            finishIo(failure, text("saved", name));
            if (failure == null) { refreshPresets(); }
        }));
    }

    public void load(String name) {
        if (busy || document() == null) { return; }
        long token = startIo();
        CompletableFuture.supplyAsync(() -> {
            try { return presets.load(name); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, Util.ioPool()).whenComplete((loaded, failure) -> minecraft.execute(() -> {
            if (token != generation) { return; }
            if (failure == null && !loaded.dimension().equals(dimension())) {
                finishIo(new IllegalArgumentException("Preset belongs to " + loaded.dimension()), null);
                return;
            }
            if (failure == null) {
                if (inDimension()) { history.commit(loaded); }
                else { history = new SceneHistory(loaded); }
                selected = loaded.objects().isEmpty() ? null : loaded.objects().getFirst().id();
            }
            finishIo(failure, text("loaded", name));
        }));
    }

    private void refreshPresets() {
        long token = generation;
        long request = ++listRequest;
        CompletableFuture.supplyAsync(() -> {
            try { return presets.list(); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, Util.ioPool()).whenComplete((names, failure) -> minecraft.execute(() -> {
            if (token != generation || request != listRequest) { return; }
            if (failure == null) { presetNames = names; }
            else { error(failure); }
        }));
    }

    private long startIo() { busy = true; status = text("working"); revision++; return generation; }

    private void finishIo(Throwable failure, Component success) {
        busy = false;
        if (failure == null) { status = success; revision++; }
        else { error(failure); }
    }

    public void error(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        status = text("error", cause.getMessage());
        revision++;
    }

    private void changed() { status = text("draft"); revision++; }
    private String dimension() { return minecraft.level == null ? "" : minecraft.level.dimension().location().toString(); }

    private String nextId(SceneObject.Kind kind) {
        String prefix = kind.name().toLowerCase(Locale.ROOT) + "_";
        for (int index = 1; index <= SceneDocument.MAX_OBJECTS + 1; index++) {
            String candidate = prefix + index;
            if (document().objects().stream().noneMatch(object -> object.id().equals(candidate))) { return candidate; }
        }
        throw new IllegalStateException("No scene object identifier available");
    }

    /** Scene lights join the same visibility/quality budget as mod-supplied lights. */
    public void collectLights(CollectSceneLightsEvent event) {
        for (SceneObject object : visibleObjects()) {
            if (!object.visible() || !object.kind().isLight()) { continue; }
            LightVector rotation = object.rotation();
            Vector3f direction = new Matrix3f().rotateZ((float) Math.toRadians(rotation.z()))
                    .rotateY((float) Math.toRadians(rotation.y())).rotateX((float) Math.toRadians(rotation.x()))
                    .transform(new Vector3f(0, 0, 1));
            event.collector().add(new SceneLight("astraeditor:" + object.id(), SceneLight.Kind.valueOf(object.kind().name()),
                    object.position(), new LightVector(direction.x, direction.y, direction.z()), object.color(),
                    object.intensity(), object.range(), object.innerDegrees(), object.outerDegrees(), true));
        }
    }

    static Component text(String key, Object... arguments) { return Component.translatable("astraengine.editor." + key, arguments); }
}
