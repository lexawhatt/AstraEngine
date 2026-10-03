package dev.lexawhatt.astraengine.client.surface;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.EarthBoundarySection;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import dev.lexawhatt.astraengine.surface.EarthRelativeProjection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;

/** Connection-owned neighboring block meshes. One cancellable worker; all GPU installation/disposal stays on the render thread. */
public final class EarthBoundaryRenderer implements AutoCloseable {
    private final EarthStateClient earth;
    private final EarthBoundaryClient boundary;
    private ShaderInstance shader;
    private Pending pending;
    private EarthBoundarySnapshot installed;
    private List<Part> parts = List.of();
    private long failedRevision;
    private long draws;
    private long readyRevision;
    private long previewReadyRevision;

    /** Requires the same connection owners used by the geographic sky and server boundary observations. */
    public EarthBoundaryRenderer(EarthStateClient earth, EarthBoundaryClient boundary) {
        if (earth == null || boundary == null) { throw new IllegalArgumentException("Boundary mesh requires connection owners"); }
        this.earth = earth; this.boundary = boundary;
    }

    /** Host owns the registered shader; this renderer owns only buffers and pending immutable mesh results. */
    public void registerShaders(RegisterShadersEvent event) {
        close(); shader = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "earth_boundary"), DefaultVertexFormat.BLOCK),
                    value -> shader = value);
        } catch (IOException failure) { AstraEngine.LOGGER.error("Could not load Earth boundary material", failure); }
    }

    /** Native terrain stages use host depth. With an Iris pack, observations compose after pack finalization. */
    public void render(RenderLevelStageEvent event) {
        if (RenderCompatibility.shadowPass()) { return; }
        boolean packed = RenderCompatibility.shaderPackActive();
        boolean opaque = event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS;
        boolean transparent = event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        if (packed ? event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL : !opaque && !transparent) { return; }
        var game = Minecraft.getInstance();
        var view = boundary.view().orElse(null);
        if (game.level == null || shader == null) { clearGeometry(); return; }
        if (view == null) {
            var preview = boundary.preparation().orElse(null);
            if (preview == null) { clearGeometry(); return; }
            update(preview);
            if (preview.complete() && sameContents(installed, preview) && previewReadyRevision != preview.revision()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new dev.lexawhatt.astraengine.network.SpaceBoundaryReadyPayload(preview.revision()));
                previewReadyRevision = preview.revision();
            }
            return;
        }
        var chart = earth.cubeChart(game.level.dimension().location().toString()).orElse(null);
        if (chart == null) { clearGeometry(); return; }
        update(view);
        if (view.complete() && sameContents(installed, view) && readyRevision != view.revision()) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new dev.lexawhatt.astraengine.network.BoundaryReadyPayload(view.revision()));
            readyRevision = view.revision();
        }
        if (parts.isEmpty()) { return; }
        var eye = event.getCamera().getPosition();
        var camera = new SpaceVector(eye.x, eye.y, eye.z);
        int previousAtlas = RenderSystem.getShaderTexture(0), previousLight = RenderSystem.getShaderTexture(2);
        try (var state = new FullscreenPass()) {
            game.gameRenderer.lightTexture().turnOnLightLayer();
            shader.setSampler("Sampler2", RenderSystem.getShaderTexture(2));
            RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL11.GL_LEQUAL); RenderSystem.disableCull();
            for (Part part : parts) {
                if (part.section.chart().equals(chart) && game.levelRenderer.isSectionCompiled(part.section.section().origin())
                        || !packed && part.translucent != transparent) { continue; }
                var origin = part.section.section().origin();
                var projection = EarthRelativeProjection.between(part.section.chart(),
                        new SpaceVector(origin.getX(), origin.getY(), origin.getZ()), chart, camera);
                vector("RelativeX", projection.xNumerator()); vector("RelativeZ", projection.zNumerator());
                vector("RelativeDenominator", projection.denominator());
                shader.safeGetUniform("RelativeY").set((float) projection.yOffset());
                // drawWithShader refreshes reserved Sampler0 from RenderSystem before applying the program.
                RenderSystem.setShaderTexture(0, part.atlas);
                if (part.translucent) { RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.depthMask(false); }
                else { RenderSystem.disableBlend(); RenderSystem.depthMask(true); }
                part.vertices.bind(); part.vertices.drawWithShader(event.getModelViewMatrix(), event.getProjectionMatrix(), shader);
                VertexBuffer.unbind(); draws++;
            }
        } finally {
            RenderSystem.setShaderTexture(0, previousAtlas);
            RenderSystem.setShaderTexture(2, previousLight);
        }
    }

    private void update(EarthBoundarySnapshot view) {
        if (pending != null && pending.future.isDone()) {
            var done = pending; pending = null;
            try (var baked = done.future.join()) {
                if (!done.cancelled.get() && sameContents(baked.snapshot, view)) { install(baked); }
            } catch (CancellationException ignored) {
                // A detached worker is responsible for disposing any eventual result.
            } catch (RuntimeException failure) {
                failedRevision = done.revision;
                AstraEngine.LOGGER.error("Could not prepare Earth boundary geometry; retaining the last valid observation", failure);
            }
        }
        if (pending != null || failedRevision == view.revision() || sameContents(installed, view)) { return; }
        var game = Minecraft.getInstance();
        var registry = game.level.registryAccess().registryOrThrow(Registries.BIOME);
        var biomes = new HashMap<Integer, net.minecraft.world.level.biome.Biome>();
        for (var section : view.sections()) {
            for (int i = 0; i < EarthBoundarySection.BIOME_COUNT; i++) {
                int id = section.biome(i); biomes.putIfAbsent(id, registry.byId(id));
            }
        }
        float[] shades = new float[Direction.values().length];
        for (var direction : Direction.values()) { shades[direction.ordinal()] = game.level.getShade(direction, true); }
        var models = game.getBlockRenderer(); var cancelled = new AtomicBoolean();
        var chests = new BoundaryChestModels(game);
        pending = new Pending(view.revision(), cancelled, CompletableFuture.supplyAsync(
                () -> EarthBoundaryMesh.bake(view, models, chests, biomes, shades, cancelled::get), Util.backgroundExecutor()));
    }

    private void install(EarthBoundaryMesh baked) {
        var replacement = new ArrayList<Part>();
        try {
            for (var part : baked.parts) {
                var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                replacement.add(new Part(part.section(), part.translucent(), part.atlas(), buffer));
                buffer.bind(); buffer.upload(part.data()); VertexBuffer.unbind();
            }
        } catch (RuntimeException failure) { replacement.forEach(value -> value.vertices.close()); throw failure; }
        parts.forEach(value -> value.vertices.close()); parts = List.copyOf(replacement); installed = baked.snapshot;
    }

    private static boolean sameContents(EarthBoundarySnapshot first, EarthBoundarySnapshot second) {
        if (first == null || second == null || first.sections().size() != second.sections().size()) { return false; }
        for (int i = 0; i < first.sections().size(); i++) {
            if (!first.sections().get(i).sameContents(second.sections().get(i))) { return false; }
        }
        return true;
    }
    private void vector(String name, SpaceVector value) { shader.safeGetUniform(name).set((float) value.x(), (float) value.y(), (float) value.z()); }
    private void clearGeometry() {
        if (pending != null) {
            var retired = pending; pending = null; retired.cancelled.set(true);
            retired.future.thenAccept(EarthBoundaryMesh::close);
        }
        parts.forEach(value -> value.vertices.close()); parts = List.of(); installed = null;
    }
    /** Logout retires only this connection's temporary observations. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) { close(); }
    /** Reload/logout disposal; registered shader ownership remains with Minecraft. */
    @Override public void close() { RenderSystem.assertOnRenderThread(); clearGeometry(); failedRevision = 0; readyRevision = 0; previewReadyRevision = 0; }
    private record Pending(long revision, AtomicBoolean cancelled, CompletableFuture<EarthBoundaryMesh> future) {}
    private record Part(EarthBoundarySection section, boolean translucent, ResourceLocation atlas, VertexBuffer vertices) {}
}
