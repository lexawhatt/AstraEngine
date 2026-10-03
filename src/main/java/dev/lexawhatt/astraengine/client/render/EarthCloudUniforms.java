package dev.lexawhatt.astraengine.client.render;

import dev.lexawhatt.astraengine.client.sky.CloudNoiseField;
import dev.lexawhatt.astraengine.client.sky.EarthCloudState;
import dev.lexawhatt.astraengine.client.sky.SkyStateClient;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Quaternionf;

/** Stateless extraction and binding shared by the three explicit cloud resource owners. */
final class EarthCloudUniforms {
    private EarthCloudUniforms() { }

    static EarthCloudState sample(ClientLevel level, float partial, RenderOptions options,
                                  SkyStateClient sky, float incident) {
        var game = Minecraft.getInstance();
        float cover = game.options.getCloudsType() == CloudStatus.OFF ? 0 : options.cloudCoverSetting();
        double fraction = level.tickRateManager().runsNormally() && !game.isPaused() ? Math.clamp(partial, 0, 1) : 0;
        return EarthCloudState.sample(sky.earthGameTime(level), fraction, cover,
                sky.earthSeasonPhase(), sky.earthRain(), sky.earthThunder(), incident);
    }

    static void bind(ShaderInstance shader, EarthCloudState state, SpaceVector bodyKm, double radiusKm,
                     FlightOrientation localToBody, SpaceVector bodySun) {
        shader.safeGetUniform("EarthCloudParams").set(state.cover(), state.season(), state.rain(), state.incident());
        shader.safeGetUniform("CloudWind").set(state.windX(), state.windZ());
        shader.safeGetUniform("CloudLayer").set((float) EarthCloudState.BASE_KM, (float) EarthCloudState.TOP_KM);
        shader.safeGetUniform("CloudNoiseLayout").set((float) CloudNoiseField.PERIOD, (float) CloudNoiseField.TILE_SIZE,
                (float) CloudNoiseField.ATLAS_SIZE, (float) CloudNoiseField.TILES_PER_ROW);
        shader.safeGetUniform("CloudPlanet").set((float) bodyKm.x(), (float) bodyKm.y(), (float) bodyKm.z(), (float) radiusKm);
        shader.safeGetUniform("CloudLocalToBody").set(new Matrix3f().rotation(new Quaternionf(
                (float) localToBody.x(), (float) localToBody.y(), (float) localToBody.z(), (float) localToBody.w())));
        shader.safeGetUniform("EarthCloudSun").set((float) bodySun.x(), (float) bodySun.y(), (float) bodySun.z());
    }
}
