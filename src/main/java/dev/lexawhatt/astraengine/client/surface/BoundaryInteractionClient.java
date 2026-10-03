package dev.lexawhatt.astraengine.client.surface;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.BoundaryInteractPayload;
import dev.lexawhatt.astraengine.surface.BoundaryCollision;
import dev.lexawhatt.astraengine.surface.BoundaryRaycast;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import dev.lexawhatt.astraengine.surface.EarthRelativeProjection;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.opengl.GL11;

/**
 * Connection-owned selection and ordinary attack/use input across cube chart boundaries. Selection reads
 * server observations only; all mutation and mining progress belong to the authoritative server. The owned
 * line buffer contains no block state and is released on disconnect. No client world or inventory is edited.
 */
public final class BoundaryInteractionClient implements AutoCloseable {
    private final Minecraft game = Minecraft.getInstance();
    private final EarthStateClient earth;
    private final EarthBoundaryClient boundary;
    private final BooleanSupplier controlsOwned;
    private Selection mining;
    private VertexBuffer lines;
    private int ticks;
    private int useAfter;
    private int sentAfter;

    /** Main client thread; dependencies have the same connection lifetime and do not transfer ownership. */
    public BoundaryInteractionClient(EarthStateClient earth, EarthBoundaryClient boundary, BooleanSupplier controlsOwned) {
        if (earth == null || boundary == null || controlsOwned == null) {
            throw new IllegalArgumentException("Boundary interaction requires connection and input owners");
        }
        this.earth = earth; this.boundary = boundary; this.controlsOwned = controlsOwned;
    }

    /** Consumes host clicks only when the closest observed block belongs to a neighboring canonical chart. */
    public void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isCanceled()) { return; }
        Selection selected = selection(1);
        if (selected == null) { return; }
        event.setCanceled(true); event.setSwingHand(false);
        if (event.isAttack()) {
            start(selected);
        } else if (event.isUseItem() && ticks >= useAfter) {
            stop();
            InteractionHand hand = game.player.getMainHandItem().isEmpty() && !game.player.getOffhandItem().isEmpty()
                    ? InteractionHand.OFF_HAND : event.getHand();
            send(BoundaryInteractPayload.Action.USE, selected, hand);
            game.player.swing(hand); useAfter = ticks + 4;
        }
    }

    /** Continues normal held mining and sends an explicit abort on release, UI opening, context change or looking away. */
    public void tick(ClientTickEvent.Post event) {
        ticks++;
        Selection selected = selection(1);
        if (game.player == null || !game.options.keyAttack.isDown() || selected == null) { stop(); return; }
        if (!sameTarget(mining, selected)) { start(selected); }
        else if (ticks >= sentAfter) {
            send(BoundaryInteractPayload.Action.KEEP, selected, InteractionHand.MAIN_HAND);
            game.player.swing(InteractionHand.MAIN_HAND); mining = selected; sentAfter = ticks + 2;
        }
    }

    private void start(Selection selected) {
        if (sameTarget(mining, selected)) { return; }
        stop();
        game.gameMode.stopDestroyBlock();
        send(BoundaryInteractPayload.Action.START, selected, InteractionHand.MAIN_HAND);
        game.player.swing(InteractionHand.MAIN_HAND); mining = selected; sentAfter = ticks + 2;
    }

    private void stop() {
        if (mining != null && game.getConnection() != null && game.player != null) {
            send(BoundaryInteractPayload.Action.ABORT, mining, InteractionHand.MAIN_HAND);
        }
        mining = null;
    }

    private static boolean sameTarget(Selection a, Selection b) {
        return a != null && b != null && a.hit.owner().equals(b.hit.owner()) && a.hit.position().equals(b.hit.position());
    }

    private static void send(BoundaryInteractPayload.Action action, Selection selected, InteractionHand hand) {
        PacketDistributor.sendToServer(new BoundaryInteractPayload(action, selected.hit.owner(), selected.hit.position(),
                hand, selected.revision));
    }

    private Selection selection(float partialTick) {
        if (game.level == null || game.player == null || game.gameMode == null || game.screen != null || game.isPaused()
                || controlsOwned.getAsBoolean() || game.getCameraEntity() != game.player || game.player.isSpectator()) { return null; }
        CubeStorageChart source = earth.cubeChart(game.level.dimension().location().toString()).orElse(null);
        EarthBoundarySnapshot snapshot = boundary.view().orElse(null);
        if (source == null || snapshot == null || !snapshot.complete()) { return null; }
        double reach = Math.min(BoundaryRaycast.MAX_REACH_METERS, game.player.blockInteractionRange());
        if (reach <= 0) { return null; }
        Vec3 eye = game.player.getEyePosition(partialTick);
        double margin = reach + 1;
        if (Math.abs(eye.x) < source.radiusMeters() - margin && Math.abs(eye.z) < source.radiusMeters() - margin
                && eye.y > source.minY() + margin && eye.y < source.minY() + source.height() - margin) { return null; }
        var hit = BoundaryRaycast.pick(source, eye, game.player.getViewVector(partialTick).normalize(), reach,
                owner -> BoundaryCollision.observations(game.level, owner), game.player).orElse(null);
        if (hit == null || hit.owner().equals(source)) { return null; }
        long section = SectionPos.asLong(hit.position());
        if (snapshot.sections().stream().noneMatch(value -> value.chart().equals(hit.owner())
                && value.section().asLong() == section)) { return null; }
        if (game.hitResult instanceof EntityHitResult entity && eye.distanceTo(entity.getLocation()) < hit.distanceMeters()) { return null; }
        return new Selection(source, hit, snapshot.revision());
    }

    /** Removes a local-world outline when the closer selected shape actually belongs to another chart. */
    public void highlight(RenderHighlightEvent.Block event) {
        if (selection(event.getDeltaTracker().getGameTimeDeltaPartialTick(false)) != null) { event.setCanceled(true); }
    }

    /** Draws the actual observed outline with the same projective chart mapping as neighboring block meshes. */
    public void render(RenderLevelStageEvent event) {
        if (RenderCompatibility.shadowPass() || game.options.hideGui) { return; }
        var stage = RenderCompatibility.shaderPackActive() ? RenderLevelStageEvent.Stage.AFTER_LEVEL
                : RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        if (event.getStage() != stage) { return; }
        var selected = selection(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        if (selected == null) { return; }
        var hit = selected.hit;
        var observed = BoundaryCollision.observations(game.level, hit.owner());
        var context = BoundaryCollision.context(game.player,
                selected.source.altitudeOriginMeters() - hit.owner().altitudeOriginMeters());
        var shape = observed.getBlockState(hit.position()).getShape(observed, hit.position(), context);
        if (shape.isEmpty()) { return; }
        Vec3 eye = event.getCamera().getPosition();
        var projection = EarthRelativeProjection.between(hit.owner(), new SpaceVector(hit.position().getX(),
                hit.position().getY(), hit.position().getZ()), selected.source, new SpaceVector(eye.x, eye.y, eye.z));
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        shape.forAllEdges((x0, y0, z0, x1, y1, z1) -> {
            var a = projection.position(new SpaceVector(x0, y0, z0));
            var b = projection.position(new SpaceVector(x1, y1, z1));
            var direction = b.subtract(a).normalized();
            buffer.addVertex((float) a.x(), (float) a.y(), (float) a.z()).setColor(0, 0, 0, 180)
                    .setNormal((float) direction.x(), (float) direction.y(), (float) direction.z());
            buffer.addVertex((float) b.x(), (float) b.y(), (float) b.z()).setColor(0, 0, 0, 180)
                    .setNormal((float) direction.x(), (float) direction.y(), (float) direction.z());
        });
        float lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        try (var state = new FullscreenPass()) {
            RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL11.GL_LEQUAL); RenderSystem.depthMask(false);
            RenderSystem.disableCull(); RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.lineWidth(2);
            if (lines == null) { lines = new VertexBuffer(VertexBuffer.Usage.DYNAMIC); }
            lines.bind(); lines.upload(buffer.buildOrThrow());
            lines.drawWithShader(event.getModelViewMatrix(), event.getProjectionMatrix(), GameRenderer.getRendertypeLinesShader());
        } finally { VertexBuffer.unbind(); RenderSystem.lineWidth(lineWidth); }
    }

    /** Disconnect retires input and GPU ownership; resource reload never changes authoritative observations. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) { mining = null; ticks = 0; useAfter = 0; sentAfter = 0; close(); }
    @Override public void close() { if (lines != null) { lines.close(); lines = null; } }
    private record Selection(CubeStorageChart source, BoundaryRaycast.Hit hit, long revision) { }
}
