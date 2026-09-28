package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.scene.SceneDocument;
import dev.lexawhatt.astraengine.client.scene.SceneObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Draws bounded, opaque analytic shapes without blocks or entities. All methods run on the
 * render thread. Minecraft owns shaders; this instance owns only its copied framebuffer.
 */
public final class SceneShapeRenderer implements AutoCloseable {
    private ShaderInstance shader;
    private RenderTarget scene;

    /** Reloads the program and releases attachments; a failed shader load disables this pass. */
    public void registerShaders(RegisterShadersEvent event) {
        close();
        shader = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "shapes"),
                    DefaultVertexFormat.POSITION), loaded -> shader = loaded);
        } catch (IOException exception) {
            AstraEngine.LOGGER.error("Could not load AstraEngine scene shapes; shape rendering remains disabled", exception);
        }
    }

    /**
     * Renders at AFTER_BLOCK_ENTITIES before opaque lighting and returns the submitted shape count.
     * Positions use world blocks; rotation is Euler degrees composed Rz * Ry * Rx. The first
     * 16 visible non-light objects are selected. Their depth participates in later world rendering
     * and lighting. Emission is a display-color boost, not an HDR or light-emitting material.
     * Empty scenes release their intermediate target. The caller owns dimension/session selection.
     */
    public int render(RenderLevelStageEvent event, List<SceneObject> objects) {
        Objects.requireNonNull(event, "Render event is required");
        Objects.requireNonNull(objects, "Scene objects are required");
        List<SceneObject> shapes = new ArrayList<>(SceneDocument.MAX_SHAPES);
        for (SceneObject object : objects) {
            Objects.requireNonNull(object, "Scene objects cannot contain null");
            if (object.visible() && !object.kind().isLight()) {
                shapes.add(object);
                if (shapes.size() == SceneDocument.MAX_SHAPES) { break; }
            }
        }
        if (shader == null || shapes.isEmpty()) {
            close();
            return 0;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        try (var state = new FullscreenPass()) {
            ensureTarget(main);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scene.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, scene.width, scene.height,
                    GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            main.bindWrite(true);
            shader.setSampler("SceneColor", scene.getColorTextureId());
            shader.setSampler("SceneDepth", scene.getDepthTextureId());
            Matrix4f viewProjection = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
            shader.safeGetUniform("InverseViewProjection").set(new Matrix4f(viewProjection).invert());
            shader.safeGetUniform("ViewProjection").set(viewProjection);
            shader.safeGetUniform("ShapeCount").set(shapes.size());
            var camera = event.getCamera().getPosition();
            for (int i = 0; i < shapes.size(); i++) {
                SceneObject object = shapes.get(i);
                shader.safeGetUniform("ShapePositionKind[" + i + "]").set(
                        (float) (object.position().x() - camera.x),
                        (float) (object.position().y() - camera.y),
                        (float) (object.position().z() - camera.z), shapeCode(object.kind()));
                shader.safeGetUniform("ShapeScaleInner[" + i + "]").set((float) object.scale().x(),
                        (float) object.scale().y(), (float) object.scale().z(), object.innerRadius());
                shader.safeGetUniform("ShapeColorEmission[" + i + "]").set((float) object.color().x(),
                        (float) object.color().y(), (float) object.color().z(), object.emission());
                Matrix3f inverseRotation = new Matrix3f().rotationZYX(
                        (float) Math.toRadians(object.rotation().z()),
                        (float) Math.toRadians(object.rotation().y()),
                        (float) Math.toRadians(object.rotation().x())).transpose();
                shader.safeGetUniform("ShapeInverseRotation[" + i + "]").set(inverseRotation);
            }
            FullscreenPass.draw(shader, true);
        }
        return shapes.size();
    }

    private static float shapeCode(SceneObject.Kind kind) {
        return switch (kind) {
            case SPHERE -> 0;
            case BOX -> 1;
            case RING -> 2;
            case DISK -> 3;
            default -> throw new IllegalArgumentException("A visual light has no shape intersection: " + kind);
        };
    }

    private void ensureTarget(RenderTarget main) {
        if (scene != null && scene.width == main.width && scene.height == main.height
                && scene.isStencilEnabled() == main.isStencilEnabled()) { return; }
        releaseTarget();
        scene = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        if (main.isStencilEnabled()) { scene.enableStencil(); }
    }

    private void releaseTarget() {
        if (scene != null) {
            scene.destroyBuffers();
            scene = null;
        }
    }

    /** Releases owned GPU attachments on reload, deactivation or logout; repeated calls are safe. */
    @Override
    public void close() {
        if (scene != null) {
            try (var state = new FullscreenPass()) {
                releaseTarget();
            }
        }
    }
}
