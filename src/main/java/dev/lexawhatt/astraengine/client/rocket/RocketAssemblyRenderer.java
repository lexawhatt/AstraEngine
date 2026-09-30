package dev.lexawhatt.astraengine.client.rocket;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import dev.lexawhatt.astraengine.rocket.RocketBounds;
import dev.lexawhatt.astraengine.rocket.RocketCatalog;
import dev.lexawhatt.astraengine.rocket.RocketGeometry;
import dev.lexawhatt.astraengine.rocket.RocketPart;
import dev.lexawhatt.astraengine.rocket.RocketPlacement;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * One analytic solid renderer for editor previews and deployed assemblies. Render-thread only;
 * no part is a raster mesh. Minecraft owns registered programs, while this instance owns two
 * bounded color/depth targets. The caller supplies the connection's immutable definition catalog.
 */
public final class RocketAssemblyRenderer implements AutoCloseable {
    private static final int MAX_ASSEMBLIES = 4;
    private static final int MAX_PREVIEW_PIXELS = 1024;
    private static final double WORLD_RANGE_BLOCKS = 96;
    private ShaderInstance shader;
    private RenderTarget preview;
    private RenderTarget worldCopy;
    private int worldAssemblyCount;
    private int worldPartCount;

    /** Reload registration releases attachments and leaves program disposal to Minecraft. */
    public void registerShaders(RegisterShadersEvent event) {
        close();
        shader = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "rocket_preview"),
                    DefaultVertexFormat.POSITION), loaded -> shader = loaded);
        } catch (IOException exception) {
            AstraEngine.LOGGER.error("Could not load the analytic rocket renderer", exception);
        }
    }

    /** Whether Minecraft has supplied the registered rocket shader for this resource generation. */
    public boolean ready() { return shader != null; }

    /** Number of assemblies submitted in the latest world pass, bounded to four. */
    public int worldAssemblyCount() { return worldAssemblyCount; }

    /** Number of parts submitted in the latest world pass, bounded to 128. */
    public int worldPartCount() { return worldPartCount; }

    /**
     * Draws an opaque, axis-aligned rectangle in GUI coordinates after flushing pending GUI work.
     * Orbit angles are degrees; positive pitch looks down from above and zoom is a distance
     * multiplier in [0.25,4]. Selection is a blueprint list index or -1. Returns false when the
     * shader is unavailable. The target's longest edge is capped at 1024 physical pixels.
     */
    public boolean renderPreview(GuiGraphics graphics, int x, int y, int width, int height,
            RocketCatalog catalog, RocketBlueprint blueprint, int selectedPartIndex,
            double yawDegrees, double pitchDegrees, double zoom) {
        requireInputs(catalog, blueprint, selectedPartIndex);
        if (graphics == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Rocket preview requires a GUI context and positive dimensions");
        }
        PreviewCamera camera = previewCamera(blueprint, width, height, yawDegrees, pitchDegrees, zoom);
        if (shader == null) { return false; }
        graphics.flush();
        var game = Minecraft.getInstance();
        var window = game.getWindow();
        double scale = Math.min(window.getGuiScale(), (double) MAX_PREVIEW_PIXELS / Math.max(width, height));
        int pixelsWide = Math.max(1, (int) Math.ceil(width * scale));
        int pixelsHigh = Math.max(1, (int) Math.ceil(height * scale));
        RenderTarget main = game.getMainRenderTarget();
        try (var state = new RocketPassState()) {
            ensurePreview(pixelsWide, pixelsHigh);
            preview.bindWrite(true);
            // PreviewMode never samples these textures, but valid distinct textures avoid feedback bindings.
            shader.setSampler("SceneColor", main.getColorTextureId());
            shader.setSampler("SceneDepth", main.getDepthTextureId());
            shader.safeGetUniform("PreviewMode").set(1);
            uploadMatrices(camera.viewProjection());
            uploadParts(catalog, blueprint, camera.eye().scale(-1), 0, selectedPartIndex);
            RocketBounds bounds = camera.bounds();
            Vec3 center = center(bounds).subtract(camera.eye());
            shader.safeGetUniform("AssemblyCenter").set((float) center.x, (float) center.y, (float) center.z);
            shader.safeGetUniform("AssemblyRadius").set((float) camera.radius());
            shader.safeGetUniform("GridPlane").set((float) -camera.eye().x,
                    (float) (bounds.min().y() - camera.eye().y), (float) -camera.eye().z, 1.0f);
            uploadMarkers(blueprint, selectedPartIndex, camera.eye());
            FullscreenPass.draw(shader, true);
            state.restoreScissor();
            double guiScale = window.getGuiScale();
            int left = (int) Math.round(x * guiScale);
            int right = (int) Math.round((x + width) * guiScale);
            int bottom = main.height - (int) Math.round((y + height) * guiScale);
            int top = main.height - (int) Math.round(y * guiScale);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, preview.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, preview.width, preview.height, left, bottom, right, top,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        }
        return true;
    }

    /**
     * Selects the closest conservative collision box under a preview pixel using the exact same
     * orbit projection as renderPreview. Coordinates are GUI units; returns a list index or -1.
     * This performs no GL work and does not mutate the blueprint or authoritative hitboxes.
     */
    public int pickPart(int x, int y, int width, int height, RocketCatalog catalog, RocketBlueprint blueprint,
            double mouseX, double mouseY, double yawDegrees, double pitchDegrees, double zoom) {
        requireInputs(catalog, blueprint, -1);
        if (width <= 0 || height <= 0 || !Double.isFinite(mouseX) || !Double.isFinite(mouseY)) {
            throw new IllegalArgumentException("Rocket picking requires finite coordinates and positive dimensions");
        }
        if (mouseX < x || mouseY < y || mouseX >= x + width || mouseY >= y + height) { return -1; }
        PreviewCamera camera = previewCamera(blueprint, width, height, yawDegrees, pitchDegrees, zoom);
        Matrix4f inverse = new Matrix4f(camera.viewProjection()).invert();
        float clipX = (float) ((mouseX - x) / width * 2 - 1);
        float clipY = (float) (1 - (mouseY - y) / height * 2);
        Vector3f near = inverse.transformProject(new Vector3f(clipX, clipY, -1));
        Vector3f far = inverse.transformProject(new Vector3f(clipX, clipY, 1));
        Vec3 origin = camera.eye().add(near.x, near.y, near.z);
        Vec3 direction = new Vec3(far.x - near.x, far.y - near.y, far.z - near.z).normalize();
        int selected = -1;
        double nearest = Double.POSITIVE_INFINITY;
        for (int index = 0; index < blueprint.parts().size(); index++) {
            for (RocketBounds box : RocketGeometry.collisionBoxes(catalog, blueprint.parts().get(index))) {
                double distance = intersectBox(origin, direction, box);
                if (distance >= 0 && distance < nearest) {
                    nearest = distance;
                    selected = index;
                }
            }
        }
        return selected;
    }

    /**
     * Draws at AFTER_BLOCK_ENTITIES before opaque world lighting. Up to four nearest instances
     * within 96 blocks use one reusable copied target. Camera-relative subtraction uses doubles;
     * exact analytic intersections replace color and depth only in conservative projected bounds.
     */
    public int renderWorld(RenderLevelStageEvent event, RocketCatalog catalog, List<RocketRenderInstance> instances) {
        if (event == null || catalog == null || instances == null || instances.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("Rocket world rendering requires an event, catalog and instance snapshots");
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) { return 0; }
        worldAssemblyCount = 0;
        worldPartCount = 0;
        Vec3 camera = event.getCamera().getPosition();
        List<RocketRenderInstance> selected = instances.stream()
                .filter(instance -> instance.worldOrigin().distanceToSqr(camera) <= WORLD_RANGE_BLOCKS * WORLD_RANGE_BLOCKS)
                .sorted(Comparator.comparingDouble(instance -> instance.worldOrigin().distanceToSqr(camera)))
                .limit(MAX_ASSEMBLIES).toList();
        if (shader == null || selected.isEmpty()) {
            releaseWorld();
            return 0;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        Matrix4f matrix = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        try (var state = new RocketPassState()) {
            ensureWorld(main);
            shader.safeGetUniform("PreviewMode").set(0);
            shader.safeGetUniform("MarkerCount").set(0);
            shader.setSampler("SceneColor", worldCopy.getColorTextureId());
            shader.setSampler("SceneDepth", worldCopy.getDepthTextureId());
            uploadMatrices(matrix);
            for (RocketRenderInstance instance : selected) {
                if (instance.blueprint().parts().isEmpty()) { continue; }
                requireInputs(catalog, instance.blueprint(), instance.selectedPartIndex());
                Vec3 origin = instance.worldOrigin().subtract(camera);
                int[] rectangle = projectedBounds(RocketGeometry.bounds(instance.blueprint()), origin,
                        instance.yawDegrees(), matrix, main.width, main.height);
                if (rectangle == null) { continue; }
                int left = rectangle[0], bottom = rectangle[1], right = rectangle[2], top = rectangle[3];
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, worldCopy.frameBufferId);
                GL30.glBlitFramebuffer(left, bottom, right, top, left, bottom, right, top,
                        GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
                main.bindWrite(true);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(left, bottom, right - left, top - bottom);
                uploadParts(catalog, instance.blueprint(), origin, instance.yawDegrees(), instance.selectedPartIndex());
                FullscreenPass.draw(shader, true);
                worldAssemblyCount++;
                worldPartCount += instance.blueprint().parts().size();
            }
        }
        return worldPartCount;
    }

    private void uploadMatrices(Matrix4f matrix) {
        shader.safeGetUniform("ViewProjection").set(matrix);
        shader.safeGetUniform("InverseViewProjection").set(new Matrix4f(matrix).invert());
    }

    private void uploadParts(RocketCatalog catalog, RocketBlueprint blueprint, Vec3 origin,
            float assemblyYawDegrees, int selected) {
        shader.safeGetUniform("PartCount").set(blueprint.parts().size());
        shader.safeGetUniform("SelectedPart").set(selected);
        double assemblyAngle = Math.toRadians(assemblyYawDegrees);
        for (int index = 0; index < blueprint.parts().size(); index++) {
            RocketPart part = blueprint.parts().get(index);
            var visual = catalog.requireDefinition(part.definitionId()).visual();
            Vec3 position = rotate(part.position(), assemblyAngle).add(origin);
            double angle = assemblyAngle + part.yawQuarterTurns() * Math.PI / 2;
            int kind = switch (visual.geometry()) {
                case CYLINDER -> 0;
                case FRUSTUM -> 1;
                case BOX -> 2;
            };
            int material = switch (visual.material()) {
                case PLAIN -> 0;
                case PANEL -> 1;
                case WINDOW -> 2;
                case TANK -> 3;
                case NOZZLE -> 4;
                case SOLAR -> 5;
                case RADIATOR -> 6;
            };
            shader.safeGetUniform("PartPositionKind[" + index + "]").set(
                    (float) position.x, (float) position.y, (float) position.z, (float) kind);
            shader.safeGetUniform("PartSizeMaterial[" + index + "]").set((float) part.size().x() * 0.5f,
                    (float) part.size().y() * 0.5f, (float) part.size().z() * 0.5f, (float) material);
            shader.safeGetUniform("PartRotationRadii[" + index + "]").set((float) Math.cos(angle),
                    (float) Math.sin(angle), (float) visual.bottomRadiusRatio(), (float) visual.topRadiusRatio());
            shader.safeGetUniform("PartColor[" + index + "]").set((float) visual.color().x(),
                    (float) visual.color().y(), (float) visual.color().z());
        }
    }

    private void uploadMarkers(RocketBlueprint blueprint, int selected, Vec3 camera) {
        if (selected < 0) {
            shader.safeGetUniform("MarkerCount").set(0);
            return;
        }
        var points = RocketPlacement.attachmentPoints(blueprint.parts().get(selected));
        int count = Math.min(6, points.size());
        shader.safeGetUniform("MarkerCount").set(count);
        for (int index = 0; index < count; index++) {
            var point = points.get(index);
            SpaceVector position = point.position().add(point.normal().multiply(0.025));
            shader.safeGetUniform("MarkerPosition[" + index + "]").set((float) (position.x() - camera.x),
                    (float) (position.y() - camera.y), (float) (position.z() - camera.z));
        }
    }

    private static PreviewCamera previewCamera(RocketBlueprint blueprint, int width, int height,
            double yaw, double pitch, double zoom) {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch) || !Double.isFinite(zoom)
                || zoom < 0.25 || zoom > 4) {
            throw new IllegalArgumentException("Preview orbit must be finite with zoom in [0.25,4]");
        }
        RocketBounds bounds = blueprint.parts().isEmpty()
                ? new RocketBounds(new SpaceVector(-0.5, 0, -0.5), new SpaceVector(0.5, 2, 0.5))
                : RocketGeometry.bounds(blueprint);
        Vec3 center = center(bounds);
        double radius = Math.max(1, bounds.max().subtract(bounds.min()).length() * 0.5);
        double aspect = (double) width / height;
        double halfFov = Math.atan(Math.tan(Math.toRadians(23)) * Math.min(1, aspect));
        double distance = radius / Math.sin(halfFov) * 1.12 * zoom;
        double yawRadians = Math.toRadians(yaw % 360);
        double pitchRadians = Math.toRadians(Math.clamp(pitch, -85, 85));
        Vec3 offset = new Vec3(Math.sin(yawRadians) * Math.cos(pitchRadians), Math.sin(pitchRadians),
                Math.cos(yawRadians) * Math.cos(pitchRadians)).scale(distance);
        Vec3 eye = center.add(offset);
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, (float) -offset.x, (float) -offset.y,
                (float) -offset.z, 0, 1, 0);
        Matrix4f matrix = new Matrix4f().perspective((float) Math.toRadians(46), (float) aspect,
                0.03f, (float) Math.max(256, distance + radius * 8)).mul(view);
        return new PreviewCamera(eye, matrix, bounds, radius);
    }

    private static Vec3 center(RocketBounds bounds) {
        SpaceVector center = bounds.min().add(bounds.max()).multiply(0.5);
        return new Vec3(center.x(), center.y(), center.z());
    }

    private static Vec3 rotate(SpaceVector point, double yaw) {
        double c = Math.cos(yaw), s = Math.sin(yaw);
        return new Vec3(c * point.x() + s * point.z(), point.y(), -s * point.x() + c * point.z());
    }

    private static int[] projectedBounds(RocketBounds bounds, Vec3 origin, float yaw,
            Matrix4f matrix, int width, int height) {
        double minX = width, minY = height, maxX = 0, maxY = 0;
        for (int corner = 0; corner < 8; corner++) {
            SpaceVector point = new SpaceVector((corner & 1) == 0 ? bounds.min().x() : bounds.max().x(),
                    (corner & 2) == 0 ? bounds.min().y() : bounds.max().y(),
                    (corner & 4) == 0 ? bounds.min().z() : bounds.max().z());
            Vec3 relative = rotate(point, Math.toRadians(yaw)).add(origin);
            Vector4f clip = matrix.transform(new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1));
            if (clip.w <= 1e-5) { return new int[] {0, 0, width, height}; }
            double x = (clip.x / clip.w * 0.5 + 0.5) * width;
            double y = (clip.y / clip.w * 0.5 + 0.5) * height;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        int left = Math.clamp((int) Math.floor(minX) - 2, 0, width);
        int bottom = Math.clamp((int) Math.floor(minY) - 2, 0, height);
        int right = Math.clamp((int) Math.ceil(maxX) + 2, 0, width);
        int top = Math.clamp((int) Math.ceil(maxY) + 2, 0, height);
        return right > left && top > bottom ? new int[] {left, bottom, right, top} : null;
    }

    private static double intersectBox(Vec3 origin, Vec3 direction, RocketBounds box) {
        double enter = 0, leave = Double.POSITIVE_INFINITY;
        double[] origins = {origin.x, origin.y, origin.z};
        double[] directions = {direction.x, direction.y, direction.z};
        double[] low = {box.min().x(), box.min().y(), box.min().z()};
        double[] high = {box.max().x(), box.max().y(), box.max().z()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(directions[axis]) < 1e-12) {
                if (origins[axis] < low[axis] || origins[axis] > high[axis]) { return -1; }
            } else {
                double a = (low[axis] - origins[axis]) / directions[axis];
                double b = (high[axis] - origins[axis]) / directions[axis];
                enter = Math.max(enter, Math.min(a, b));
                leave = Math.min(leave, Math.max(a, b));
            }
        }
        return leave >= enter ? enter : -1;
    }

    private static void requireInputs(RocketCatalog catalog, RocketBlueprint blueprint, int selected) {
        if (catalog == null || blueprint == null || blueprint.parts().size() > 32 || selected < -1
                || selected >= blueprint.parts().size()) {
            throw new IllegalArgumentException("Rocket rendering requires a bounded blueprint, catalog and valid selection");
        }
        for (RocketPart part : blueprint.parts()) { catalog.requireDefinition(part.definitionId()); }
    }

    private void ensurePreview(int width, int height) {
        if (preview != null && preview.width == width && preview.height == height) { return; }
        if (preview != null) { preview.destroyBuffers(); }
        preview = new TextureTarget(width, height, true, Minecraft.ON_OSX);
    }

    private void ensureWorld(RenderTarget main) {
        if (worldCopy != null && worldCopy.width == main.width && worldCopy.height == main.height
                && worldCopy.isStencilEnabled() == main.isStencilEnabled()) { return; }
        if (worldCopy != null) { worldCopy.destroyBuffers(); }
        worldCopy = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        if (main.isStencilEnabled()) { worldCopy.enableStencil(); }
    }

    /** Releases only editor attachments when its screen closes; world rendering remains available. */
    public void releasePreview() {
        if (preview == null) { return; }
        try (var state = new RocketPassState()) {
            preview.destroyBuffers();
            preview = null;
        }
    }

    private void releaseWorld() {
        if (worldCopy == null) { return; }
        try (var state = new RocketPassState()) {
            worldCopy.destroyBuffers();
            worldCopy = null;
        }
    }

    /** Releases owned targets on logout/reload/shutdown. Registered shader disposal belongs to Minecraft. */
    @Override
    public void close() {
        releasePreview();
        releaseWorld();
        worldAssemblyCount = 0;
        worldPartCount = 0;
    }

    private record PreviewCamera(Vec3 eye, Matrix4f viewProjection, RocketBounds bounds, double radius) {
    }

    private static final class RocketPassState implements AutoCloseable {
        private final FullscreenPass full = new FullscreenPass();
        private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final int[] rectangle = new int[4];

        private RocketPassState() {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, rectangle);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }

        private void restoreScissor() {
            GL11.glScissor(rectangle[0], rectangle[1], rectangle[2], rectangle[3]);
            if (scissor) { GL11.glEnable(GL11.GL_SCISSOR_TEST); }
            else { GL11.glDisable(GL11.GL_SCISSOR_TEST); }
        }

        @Override
        public void close() {
            restoreScissor();
            full.close();
        }
    }
}
