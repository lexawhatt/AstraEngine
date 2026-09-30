package dev.lexawhatt.astraengine.client.surface;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.sky.SkyIllumination;
import dev.lexawhatt.astraengine.client.solar.SolarStateClient;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Planet-fixed surface sky ownership and visual skylight; host block light and saved terrain remain authoritative. */
public final class SurfaceEffects extends DimensionSpecialEffects {
    private final SurfaceStateClient state;
    private final SolarStateClient solar;
    private final boolean atmosphere;
    private SpaceVector frameLight = SpaceVector.ZERO;

    /** A registered fixed-dimension effects instance; snapshot services are connection-scoped. */
    public SurfaceEffects(SurfaceStateClient state, SolarStateClient solar, boolean atmosphere) {
        super(Float.NaN, false, SkyType.NONE, false, false);
        this.state = state;
        this.solar = solar;
        this.atmosphere = atmosphere;
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 color, float brightness) {
        if (!atmosphere) { return Vec3.ZERO; }
        SpaceVector light = skyLight(Minecraft.getInstance().level);
        return new Vec3(light.x() * 0.38, light.y() * 0.48, light.z() * 0.65);
    }

    @Override
    public boolean isFoggyAt(int x, int y) { return false; }

    @Override
    public float[] getSunriseColor(float time, float partialTick) { return null; }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelView,
                             Camera camera, Matrix4f projection, boolean foggy, Runnable setupFog) {
        // The stage-owned cosmos renderer supplies the sky, including the safe late Iris path.
        return true;
    }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack stack,
            double x, double y, double z, Matrix4f modelView, Matrix4f projection) { return true; }

    /** Matches the native world's far-distance haze to its planet-fixed sunlight, preserving host visibility effects. */
    public void fogColor(ViewportEvent.ComputeFogColor event) {
        if (!atmosphere || RenderCompatibility.shaderPackActive() || RenderCompatibility.shadowPass()) { return; }
        Minecraft game = Minecraft.getInstance();
        var definition = state.definition(game.level);
        Camera camera = event.getCamera();
        if (definition == null || !definition.bodyId().equals("earth") || camera.getFluidInCamera() != FogType.NONE
                || camera.getEntity() instanceof LivingEntity living && (living.hasEffect(MobEffects.BLINDNESS)
                || living.hasEffect(MobEffects.DARKNESS) || living.hasEffect(MobEffects.NIGHT_VISION))) { return; }
        double ticks = state.clockTicks();
        var frame = definition.frame(CosmosGenerator.sol(), ticks / 20, ticks);
        Vec3 position = camera.getPosition();
        SpaceVector localSun = definition.patch().toLocalDirection(position.x, position.z,
                frame.toBodyDirection(frame.centerMeters().multiply(-1).normalized()));
        var look = camera.getLookVector();
        var visual = solar.visual();
        SpaceVector fog = SkyIllumination.fog(localSun.y(), localSun.x() * look.x + localSun.y() * look.y
                + localSun.z() * look.z, 0, 0, visual.luminosity(), visual.flash(), 0);
        double darken = game.gameRenderer.getDarkenWorldAmount((float) event.getPartialTick());
        double incident = Math.clamp(visual.luminosity() + visual.flash() * 0.6, 0, 1);
        event.setRed((float) (fog.x() * incident * (1 - darken * 0.3)));
        event.setGreen((float) (fog.y() * incident * (1 - darken * 0.4)));
        event.setBlue((float) (fog.z() * incident * (1 - darken * 0.4)));
    }

    @Override
    public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken,
            float blockLightRedFlicker, float skyLight, int pixelX, int pixelY, Vector3f colors) {
        if (RenderCompatibility.shaderPackActive() || RenderCompatibility.shadowPass()
                || state.definition(level) == null) { return; }
        if (pixelX == 0 && pixelY == 0) { frameLight = skyLight(level); }
        float skyAccess = LightTexture.getBrightness(level.dimensionType(), pixelY);
        float hostRG = skyDarken * 0.65f + 0.35f;
        float darken = Minecraft.getInstance().gameRenderer.getDarkenWorldAmount(partialTicks);
        colors.add((float) (skyAccess * frameLight.x() - skyLight * hostRG) * 0.96f * (1 - darken * 0.3f),
                (float) (skyAccess * frameLight.y() - skyLight * hostRG) * 0.96f * (1 - darken * 0.4f),
                (float) (skyAccess * frameLight.z() - skyLight) * 0.96f * (1 - darken * 0.4f));
        colors.set(Math.max(0, colors.x), Math.max(0, colors.y), Math.max(0, colors.z));
    }

    private SpaceVector skyLight(ClientLevel level) {
        var definition = state.definition(level);
        if (definition == null) { return SpaceVector.ZERO; }
        var system = CosmosGenerator.sol();
        double ticks = state.clockTicks();
        var frame = definition.frame(system, ticks / 20, ticks);
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        SpaceVector up = frame.toSystemDirection(definition.patch().normal(camera.x, camera.z));
        SpaceVector sunlight = frame.centerMeters().multiply(-1).normalized();
        double height = up.dot(sunlight);
        float incident = solar.visual().luminosity();
        if (atmosphere) { return SkyIllumination.skyLight(height, 0, 0, incident); }
        double direct = Math.clamp(height * 4 + 0.015, 0, 1) * incident;
        return new SpaceVector(direct, direct, direct);
    }
}
