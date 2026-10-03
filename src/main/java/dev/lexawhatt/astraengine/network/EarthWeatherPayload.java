package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-to-client Overworld weather and existing host game time, shared by Earth ground and orbital views. */
public record EarthWeatherPayload(long gameTime, long dayTime, double seasonPhase,
                                  float rain, float thunder) implements CustomPacketPayload {
    public static final Type<EarthWeatherPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "earth_weather"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EarthWeatherPayload> CODEC = StreamCodec.ofMember(
            EarthWeatherPayload::write, EarthWeatherPayload::read);

    /** Rejects malformed data before replacing connection-owned presentation state. */
    public EarthWeatherPayload {
        if (gameTime < 0 || !Double.isFinite(seasonPhase) || seasonPhase < 0 || seasonPhase >= 1
                || !Float.isFinite(rain) || rain < 0 || rain > 1
                || !Float.isFinite(thunder) || thunder < 0 || thunder > 1) {
            throw new IllegalArgumentException("Invalid Earth weather snapshot");
        }
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(gameTime); buffer.writeLong(dayTime); buffer.writeDouble(seasonPhase);
        buffer.writeFloat(rain); buffer.writeFloat(thunder);
    }

    private static EarthWeatherPayload read(RegistryFriendlyByteBuf buffer) {
        return new EarthWeatherPayload(buffer.readLong(), buffer.readLong(), buffer.readDouble(), buffer.readFloat(), buffer.readFloat());
    }

    @Override public Type<EarthWeatherPayload> type() { return TYPE; }
}
