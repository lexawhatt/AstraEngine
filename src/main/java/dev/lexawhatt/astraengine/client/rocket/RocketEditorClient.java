package dev.lexawhatt.astraengine.client.rocket;

import dev.lexawhatt.astraengine.network.RocketEditorCommandPayload;
import dev.lexawhatt.astraengine.network.RocketEditorStatePayload;
import dev.lexawhatt.astraengine.network.RocketEditorStateReceivedEvent;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import dev.lexawhatt.astraengine.rocket.RocketCatalog;
import dev.lexawhatt.astraengine.rocket.RocketPart;
import dev.lexawhatt.astraengine.rocket.RocketPlacement;
import dev.lexawhatt.astraengine.server.rocket.RocketAssemblyEntity;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Connection-owned construction draft. Server acknowledgements own saved revisions and deployment. */
public final class RocketEditorClient {
    private final Supplier<RocketCatalog> catalog;
    private final RocketAssemblyRenderer renderer = new RocketAssemblyRenderer();
    private final Deque<RocketBlueprint> undo = new ArrayDeque<>();
    private final Deque<RocketBlueprint> redo = new ArrayDeque<>();
    private RocketEditorStatePayload authority;
    private RocketBlueprint draft;
    private List<RocketRenderInstance> instances = List.of();
    private int selectedId = -1;
    private int editRevision;
    private int pendingTicks;
    private int deferredDeployTicks;
    private boolean deployAfterSave;
    private RocketEditorCommandPayload.Action pendingAction;
    private int deploymentRetries;
    private Component feedback = text("ready");

    /** Catalog is the frozen native mod-registration catalog; called only on the client thread. */
    public RocketEditorClient(Supplier<RocketCatalog> catalog) {
        this.catalog = java.util.Objects.requireNonNull(catalog);
    }

    public RocketCatalog catalog() { return catalog.get(); }
    public RocketAssemblyRenderer renderer() { return renderer; }
    public RocketBlueprint blueprint() { return draft; }
    public Component feedback() { return feedback; }
    public int revision() { return editRevision; }
    public boolean busy() { return pendingTicks > 0 || deferredDeployTicks > 0; }
    public boolean dirty() { return authority != null && !authority.blueprint().equals(draft); }
    public boolean canUndo() { return !busy() && !undo.isEmpty(); }
    public boolean canRedo() { return !busy() && !redo.isEmpty(); }
    public boolean hasWorldAssemblies() { return !instances.isEmpty(); }

    /** Current stable part identity, independent of its row/list index. */
    public RocketPart selected() {
        return draft == null ? null : draft.parts().stream().filter(part -> part.id() == selectedId).findFirst().orElse(null);
    }

    public int selectedIndex() {
        RocketPart part = selected();
        return part == null ? -1 : draft.parts().indexOf(part);
    }

    /** Accepts an explicit server OPEN or an acknowledgement for this exact still-open session. */
    public void receive(RocketEditorStateReceivedEvent event) {
        RocketEditorStatePayload state = event.payload();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(state.dimension())) { return; }
        if (state.status() == RocketEditorStatePayload.Status.OPEN) {
            authority = state;
            draft = state.blueprint();
            selectedId = draft.parts().isEmpty() ? -1 : draft.parts().getFirst().id();
            undo.clear(); redo.clear(); pendingTicks = 0; deferredDeployTicks = 0; deployAfterSave = false; editRevision++;
            pendingAction = null; deploymentRetries = 0;
            feedback = text("ready");
            minecraft.setScreen(new RocketEditorScreen(this));
            return;
        }
        if (authority == null || !authority.session().equals(state.session())) { return; }
        if (state.status() == RocketEditorStatePayload.Status.CLOSED) {
            close();
            if (minecraft.screen instanceof RocketEditorScreen) { minecraft.setScreen(null); }
            return;
        }
        if (state.revision() < authority.revision()) { return; }
        RocketEditorCommandPayload.Action acknowledged = pendingAction;
        pendingAction = null;
        authority = state;
        pendingTicks = 0;
        deferredDeployTicks = 0;
        feedback = text("status." + state.status().name().toLowerCase(Locale.ROOT));
        if (state.status() == RocketEditorStatePayload.Status.SAVED) {
            editRevision++;
            // Preserve edits made after a timeout; acknowledgements update only the saved baseline.
            if (deployAfterSave && !dirty()) { deferredDeployTicks = 6; }
            deployAfterSave = false;
        } else {
            deployAfterSave = false;
            // Client ticks can catch up faster than server ticks after a resize or loading stall.
            // Retry only the same already-saved deployment; a changed draft or revision stops the chain.
            if (state.status() == RocketEditorStatePayload.Status.RATE_LIMITED
                    && acknowledged == RocketEditorCommandPayload.Action.DEPLOY
                    && !dirty() && deploymentRetries < 3) {
                deferredDeployTicks = 10 * ++deploymentRetries;
                feedback = text("retry_deploy");
            }
        }
        if (state.status() == RocketEditorStatePayload.Status.CLOSED
                || state.status() == RocketEditorStatePayload.Status.DENIED) {
            close();
            if (minecraft.screen instanceof RocketEditorScreen) { minecraft.setScreen(null); }
        }
    }

    /** Validates the complete candidate before replacing the draft or history; never sends an implicit save. */
    public boolean commit(RocketBlueprint candidate) {
        if (busy() || authority == null) { return false; }
        try { catalog().validate(candidate); }
        catch (IllegalArgumentException invalid) { reject(invalid.getMessage()); return false; }
        if (candidate.equals(draft)) { return true; }
        undo.addLast(draft);
        if (undo.size() > 64) { undo.removeFirst(); }
        redo.clear(); draft = candidate; editRevision++;
        if (selected() == null) { selectedId = draft.parts().isEmpty() ? -1 : draft.parts().getFirst().id(); }
        feedback = text("unsaved");
        return true;
    }

    public void select(int id) { if (!busy()) { selectedId = id; editRevision++; } }

    public void attach(String definition, RocketPlacement.Attachment attachment, int symmetry) {
        if (busy() || draft == null) { return; }
        try {
            RocketBlueprint next = RocketPlacement.attach(catalog(), draft, selectedId, definition, attachment, symmetry);
            if (commit(next)) { select(next.parts().getLast().id()); }
        } catch (IllegalArgumentException invalid) { reject(invalid.getMessage()); }
    }

    public void removeSelected() {
        if (selected() != null) { commit(RocketPlacement.removeSubtree(draft, selectedId)); }
    }

    public void undo() {
        if (canUndo()) { redo.addLast(draft); draft = undo.removeLast(); normalizeSelection(); editRevision++; feedback = text("unsaved"); }
    }

    public void redo() {
        if (canRedo()) { undo.addLast(draft); draft = redo.removeLast(); normalizeSelection(); editRevision++; feedback = text("unsaved"); }
    }

    private void normalizeSelection() {
        if (selected() == null) { selectedId = draft.parts().isEmpty() ? -1 : draft.parts().getFirst().id(); }
    }

    public void reloadSaved() { if (!busy() && authority != null) { commit(authority.blueprint()); } }

    /** Deployment of a dirty design is sequenced after a successful revision-checked save acknowledgement. */
    public void save(boolean deploy) {
        if (busy() || authority == null) { return; }
        deploymentRetries = 0;
        deployAfterSave = deploy && dirty();
        if (dirty() || !deploy) { send(RocketEditorCommandPayload.Action.SAVE, draft); }
        else { send(RocketEditorCommandPayload.Action.DEPLOY, null); }
    }

    private void send(RocketEditorCommandPayload.Action action, RocketBlueprint blueprint) {
        PacketDistributor.sendToServer(new RocketEditorCommandPayload(authority.session(), authority.revision(), action, blueprint));
        pendingAction = action;
        pendingTicks = 100;
        feedback = text("pending");
    }

    public void reject(String message) { feedback = text("invalid", message); }

    /** Expires connection/dimension/range ownership; a late response cannot reopen a closed draft. */
    public void tick(ClientTickEvent.Post event) {
        if (authority == null) { return; }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || !minecraft.level.dimension().location().equals(authority.dimension())
                || minecraft.player.distanceToSqr(authority.position().getCenter()) > 64) {
            close();
            if (minecraft.screen instanceof RocketEditorScreen) { minecraft.setScreen(null); }
            return;
        }
        if (pendingTicks > 0 && --pendingTicks == 0) {
            // A late save is still reconciled by its revision; no speculative deploy follows a timeout.
            deployAfterSave = false; feedback = text("timeout");
        }
        if (deferredDeployTicks > 0 && --deferredDeployTicks == 0) {
            send(RocketEditorCommandPayload.Action.DEPLOY, null);
        }
    }

    /** Closes only the editor session; persisted designs/deployed assemblies stay server-owned. */
    public void close() {
        if (authority != null && Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new RocketEditorCommandPayload(authority.session(), authority.revision(),
                    RocketEditorCommandPayload.Action.CLOSE, null));
        }
        authority = null; draft = null; selectedId = -1; pendingAction = null; deploymentRetries = 0;
        pendingTicks = 0; deferredDeployTicks = 0; deployAfterSave = false; undo.clear(); redo.clear();
        renderer.releasePreview();
    }

    /** Extracts only loaded nearby immutable assembly snapshots, without retaining entities between frames. */
    public void collectWorld(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) { instances = List.of(); return; }
        var camera = event.getCamera().getPosition();
        instances = minecraft.level.getEntitiesOfClass(RocketAssemblyEntity.class, new AABB(camera, camera).inflate(96))
                .stream().sorted(Comparator.comparingDouble(entity -> entity.distanceToSqr(camera)))
                .limit(4).map(entity -> new RocketRenderInstance(entity.blueprint(), entity.position(), entity.getYRot(), -1))
                .toList();
    }

    public void renderWorld(RenderLevelStageEvent event) { renderer.renderWorld(event, catalog(), instances); }

    /** Clears connection-owned previews and GL attachments. No server save is deleted. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        authority = null; draft = null; selectedId = -1; pendingAction = null; deploymentRetries = 0; pendingTicks = 0; deferredDeployTicks = 0; deployAfterSave = false;
        undo.clear(); redo.clear(); instances = List.of();
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) { renderer.close(); }
        else { com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(renderer::close); }
    }

    static Component text(String key, Object... args) { return Component.translatable("astraengine.rocket_editor." + key, args); }
}
