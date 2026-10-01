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
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Opt-in calibration world's physical ocean/limb background. Render-thread owner; borrows no DH attachments
 * and never replaces its programs. Drawn before ordinary/DH terrain. Active packs retain full ownership.
 * Minecraft owns the registered shader; no private targets or world references survive a frame.
 */
public final class HorizonRenderer {
    // The saved top water block is at Y=63. Source water beneath air has an 8/9-block visual height.
    // Keep the permanent patch's integer sea reference and its physical radius unchanged.
    private static final double OCEAN_SURFACE_OFFSET_METERS = -1.0 / 9.0;
    private static final SpaceVector DAY_WATER = new SpaceVector(0.025, 0.20, 0.36);
    private static final SpaceVector NIGHT_WATER = new SpaceVector(0.006, 0.013, 0.027);
    private static final SpaceVector DAY_MARINE_FOG = new SpaceVector(0.070, 0.240, 0.405);
    private static final SpaceVector NIGHT_MARINE_FOG = new SpaceVector(0.004, 0.010, 0.019);
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
        double height = position.y - HorizonScene.SEA_Y - OCEAN_SURFACE_OFFSET_METERS;
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
            shader.safeGetUniform("OceanSurfaceOffset").set((float) OCEAN_SURFACE_OFFSET_METERS);
            shader.safeGetUniform("FlatComparison").set(flat ? 1 : 0);
            shader.safeGetUniform("ShowTowers").set(towers ? 1 : 0);
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            float angle = Minecraft.getInstance().level.getSunAngle(partial);
            shader.safeGetUniform("SunDirection").set(-(float) Math.sin(angle), (float) Math.cos(angle), 0.0f);
            SpaceVector water = marineColor(NIGHT_WATER, DAY_WATER, partial);
            SpaceVector fog = marineColor(NIGHT_MARINE_FOG, DAY_MARINE_FOG, partial);
            shader.safeGetUniform("MarineWaterColor").set((float) water.x(), (float) water.y(), (float) water.z());
            shader.safeGetUniform("MarineFogColor").set((float) fog.x(), (float) fog.y(), (float) fog.z());
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
        return new Diagnostics(draws, active(), HorizonScene.RADIUS_METERS, altitude,
                altitude - OCEAN_SURFACE_OFFSET_METERS);
    }

    /**
     * Shares the analytic ocean's marine haze with host terrain fog in this calibration view only.
     * DH's default world-fog-color mode consumes the host value without an Astra DH dependency or option change.
     * Preserves host fluid/status-effect handling and leaves fog distances and all opaque geometry untouched.
     */
    public void fogColor(ViewportEvent.ComputeFogColor event) {
        if (!active() || event.getCamera().getFluidInCamera() != FogType.NONE
                || event.getCamera().getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS)
                    || living.hasEffect(MobEffects.NIGHT_VISION))) { return; }
        SpaceVector fog = marineColor(NIGHT_MARINE_FOG, DAY_MARINE_FOG, (float) event.getPartialTick());
        event.setRed((float) fog.x());
        event.setGreen((float) fog.y());
        event.setBlue((float) fog.z());
    }

    private static SpaceVector marineColor(SpaceVector night, SpaceVector day, float partialTick) {
        Minecraft game = Minecraft.getInstance();
        double sunHeight = Math.cos(game.level.getSunAngle(partialTick));
        double daylight = Math.clamp((sunHeight + 0.12) / 0.30, 0.0, 1.0);
        daylight = daylight * daylight * (3 - 2 * daylight);
        double darken = game.gameRenderer.getDarkenWorldAmount(partialTick);
        return new SpaceVector((night.x() + (day.x() - night.x()) * daylight) * (1 - darken * 0.3),
                (night.y() + (day.y() - night.y()) * daylight) * (1 - darken * 0.4),
                (night.z() + (day.z() - night.z()) * daylight) * (1 - darken * 0.4));
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

    /**
     * Immutable presentation observation. Eye altitude retains the nominal patch-sea reference;
     * ocean eye altitude is measured above the source-water visual surface, 1/9 meter below that reference.
     */
    public record Diagnostics(long draws, boolean active, double radiusMeters, double eyeAltitudeMeters,
                              double oceanEyeAltitudeMeters) {
        /** Visual water radius; canonical body/patch radius remains radiusMeters. */
        public double oceanRadiusMeters() { return radiusMeters + OCEAN_SURFACE_OFFSET_METERS; }
    }
}
