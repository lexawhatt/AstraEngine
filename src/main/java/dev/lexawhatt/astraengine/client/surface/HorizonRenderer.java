package dev.lexawhatt.astraengine.client.surface;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.HorizonScene;
import dev.lexawhatt.astraengine.surface.PlanetaryHorizon;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Opt-in calibration world's physical ocean/limb background. Render-thread owner; borrows no DH attachments
 * and never replaces its programs. Drawn before ordinary/DH terrain. Active packs retain full ownership.
 * Minecraft owns the registered shader; no private targets or world references survive a frame.
 */
public final class HorizonRenderer {
    private ShaderInstance shader;
    private boolean enabled = true;
    private boolean towers = true;
    private boolean flat;
    private long draws;
    private double altitude;

    /** Host reload replaces/disposes programs; session controls survive resource reload. */
    public void registerShaders(RegisterShadersEvent event) {
        shader = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "horizon"), DefaultVertexFormat.POSITION),
                    loaded -> shader = loaded);
        } catch (IOException failure) {
            AstraEngine.LOGGER.error("Could not load horizon calibration shader; retaining host sky", failure);
        }
    }

    /** Whether the current main view can safely use this pass. Called only on the client render thread. */
    public boolean active() {
        Minecraft game = Minecraft.getInstance();
        if (!enabled || shader == null || game.level == null || RenderCompatibility.shaderPackActive()
                || RenderCompatibility.shadowPass()
                || !game.level.dimension().location().toString().equals(HorizonScene.DIMENSION_ID)) { return false; }
        var camera = game.gameRenderer.getMainCamera();
        var position = camera.getPosition();
        double height = position.y - HorizonScene.SEA_Y;
        return Double.isFinite(height) && height >= 0.01 && height <= 10_000_000
                && HorizonScene.PATCH.contains(position.x, position.z)
                && camera.getFluidInCamera() == FogType.NONE
                && !(camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS)));
    }

    /** Draws the stable sphere and fixed measuring targets at AFTER_SKY without writing host depth. */
    public void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || !active()) { return; }
        var position = event.getCamera().getPosition();
        SpaceVector camera = new SpaceVector(position.x, position.y, position.z);
        altitude = camera.y() - HorizonScene.SEA_Y;
        var patch = HorizonScene.PATCH;
        try (var state = new FullscreenPass()) {
            shader.safeGetUniform("InverseViewProjection").set(new Matrix4f(event.getProjectionMatrix())
                    .mul(event.getModelViewMatrix()).invert());
            shader.safeGetUniform("HorizonRadius").set((float) HorizonScene.RADIUS_METERS);
            shader.safeGetUniform("EyeAltitude").set((float) altitude);
            shader.safeGetUniform("FlatComparison").set(flat ? 1 : 0);
            shader.safeGetUniform("ShowTowers").set(towers ? 1 : 0);
            float angle = Minecraft.getInstance().level.getSunAngle(event.getPartialTick().getGameTimeDeltaPartialTick(false));
            shader.safeGetUniform("SunDirection").set(-(float) Math.sin(angle), (float) Math.cos(angle), 0.0f);
            for (int i = 0; i < 3; i++) {
                SpaceVector center = HorizonScene.towerCenter(i);
                SpaceVector relative = flat ? center.subtract(camera) : PlanetaryHorizon.project(patch, camera, center);
                shader.safeGetUniform("TowerCenter[" + i + "]").set((float) relative.x(),
                        (float) relative.y(), (float) relative.z());
                Matrix3f inverseBasis = new Matrix3f();
                if (!flat) {
                    var towerOrientation = patch.orientationAt(center.x(), center.z());
                    var orientation = patch.toLocalOrientation(camera.x(), camera.z(), towerOrientation);
                    inverseBasis.rotation(new org.joml.Quaternionf((float) orientation.x(), (float) orientation.y(),
                            (float) orientation.z(), (float) orientation.w())).transpose();
                }
                shader.safeGetUniform("TowerInverseBasis[" + i + "]").set(inverseBasis);
            }
            FullscreenPass.draw(shader);
            draws++;
        }
    }

    /** Session-local diagnostics; no server mutation, GPU readback or frame-time claim. */
    public Diagnostics diagnostics() {
        RenderSystem.assertOnRenderThread();
        return new Diagnostics(draws, active(), HorizonScene.RADIUS_METERS, altitude);
    }

    /** Client-only comparison control. Does not change stored world geometry or DH settings. */
    public void setEnabled(boolean value) { RenderSystem.assertOnRenderThread(); enabled = value; }
    /** Shows/hides fixed visual calibration targets; they never acquire block hitboxes. */
    public void setTowers(boolean value) { RenderSystem.assertOnRenderThread(); towers = value; }
    /** Enables an explicitly flat diagnostic comparison without changing physical radius or navigation. */
    public void setFlatComparison(boolean value) { RenderSystem.assertOnRenderThread(); flat = value; }

    /** Connection end resets controls and counters, never the host-owned shader. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        enabled = true; towers = true; flat = false; draws = 0; altitude = 0;
    }

    /** Registers local diagnostic controls; the scene itself requires entering the calibration world. */
    public void registerCommands(RegisterClientCommandsEvent event) {
        var root = Commands.literal("astra-render").then(Commands.literal("horizon")
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("enabled").then(Commands.argument("value", BoolArgumentType.bool()).executes(context -> {
                    setEnabled(BoolArgumentType.getBool(context, "value")); return status(context.getSource());
                })))
                .then(Commands.literal("towers").then(Commands.argument("value", BoolArgumentType.bool()).executes(context -> {
                    setTowers(BoolArgumentType.getBool(context, "value")); return status(context.getSource());
                })))
                .then(Commands.literal("flat-comparison").then(Commands.argument("value", BoolArgumentType.bool()).executes(context -> {
                    setFlatComparison(BoolArgumentType.getBool(context, "value")); return status(context.getSource());
                }))));
        event.getDispatcher().register(root);
    }

    private int status(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("astraengine.horizon.status", enabled, active(),
                towers, flat), false);
        return 1;
    }

    /** Immutable presentation observation, not authoritative geographic state. */
    public record Diagnostics(long draws, boolean active, double radiusMeters, double eyeAltitudeMeters) { }
}
