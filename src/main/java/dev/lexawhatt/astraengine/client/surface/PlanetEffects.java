package dev.lexawhatt.astraengine.client.surface;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.sky.SkyIllumination;
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

/** Generic solid-body sky/lightmap ownership; block emission and host survival/spawn rules remain unchanged. */
public final class PlanetEffects extends DimensionSpecialEffects {
    private final PlanetSkyState state;
    private SpaceVector frameLight = SpaceVector.ZERO;

    public PlanetEffects(PlanetSkyState state) {
        super(Float.NaN, false, SkyType.NONE, false, false);
        if (state == null) { throw new IllegalArgumentException("Planet effects require synchronized sky observations"); }
        this.state = state;
    }

    @Override public Vec3 getBrightnessDependentFogColor(Vec3 color, float brightness) {
        var sample = observation();
        if (sample != null && sample.chart().profile().mars()) {
            var fog = dev.lexawhatt.astraengine.surface.MarsAtmosphere.fog(sample.sunDirection().y(), 0,
                    sample.atmosphereDensity(), sample.incident());
            return new Vec3(fog.x(), fog.y(), fog.z());
        }
        return sample == null ? Vec3.ZERO : new Vec3(sample.lightColor().x() * sample.atmosphereDensity() * .38,
                sample.lightColor().y() * sample.atmosphereDensity() * .48,
                sample.lightColor().z() * sample.atmosphereDensity() * .65);
    }
    @Override public boolean isFoggyAt(int x, int y) { return false; }
    @Override public float[] getSunriseColor(float time, float partialTick) { return null; }
    @Override public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelView,
            Camera camera, Matrix4f projection, boolean foggy, Runnable setupFog) { return true; }
    @Override public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack stack,
            double x, double y, double z, Matrix4f modelView, Matrix4f projection) { return true; }

    /** Atmospheric haze follows height and the same local sunlight used by the surface and orbital view. */
    public void fogColor(ViewportEvent.ComputeFogColor event) {
        if (!eligible(event.getCamera())) { return; }
        var sample = observation();
        if (sample == null) { return; }
        var look = event.getCamera().getLookVector();
        var sun = sample.sunDirection();
        double cosine = sun.x() * look.x + sun.y() * look.y + sun.z() * look.z;
        var fog = sample.chart().profile().mars()
                ? dev.lexawhatt.astraengine.surface.MarsAtmosphere.fog(sun.y(), cosine, sample.atmosphereDensity(), sample.incident())
                : SkyIllumination.fog(sun.y(), cosine,
                0, 0, (float) sample.incident(), 0, 0).multiply(sample.atmosphereDensity());
        event.setRed((float) fog.x()); event.setGreen((float) fog.y()); event.setBlue((float) fog.z());
    }

    @Override public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken,
            float blockLightRedFlicker, float skyLight, int pixelX, int pixelY, Vector3f colors) {
        if (RenderCompatibility.shaderPackActive() || RenderCompatibility.shadowPass()) { return; }
        if (pixelX == 0 && pixelY == 0) {
            var sample = observation(); frameLight = sample == null ? SpaceVector.ZERO : sample.lightColor();
        }
        float access = LightTexture.getBrightness(level.dimensionType(), pixelY);
        float hostRG = skyDarken * .65f + .35f;
        float darken = Minecraft.getInstance().gameRenderer.getDarkenWorldAmount(partialTicks);
        colors.add((float) (access * frameLight.x() - skyLight * hostRG) * .96f * (1 - darken * .3f),
                (float) (access * frameLight.y() - skyLight * hostRG) * .96f * (1 - darken * .4f),
                (float) (access * frameLight.z() - skyLight) * .96f * (1 - darken * .4f));
        colors.set(Math.max(0, colors.x), Math.max(0, colors.y), Math.max(0, colors.z));
    }

    private static boolean eligible(Camera camera) {
        return !RenderCompatibility.shaderPackActive() && !RenderCompatibility.shadowPass()
                && camera.getFluidInCamera() == FogType.NONE && !(camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS)));
    }
    private PlanetSkyState.Observation observation() {
        var game = Minecraft.getInstance();
        return state.sample(game.level, game.gameRenderer.getMainCamera().getPosition());
    }
}
