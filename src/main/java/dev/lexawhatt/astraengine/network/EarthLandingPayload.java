package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Own-flight landing proposal in unit, body-fixed Earth coordinates, captured from the displayed planet.
 * The epoch must match the current server navigation owner. No client height, chart or world is trusted;
 * the server checks range and visibility against saved geography before preparing actual collision data.
 */
public record EarthLandingPayload(SpaceVector normal, long navigationEpoch) implements CustomPacketPayload {
    public static final Type<EarthLandingPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            AstraEngine.MOD_ID, "earth_landing"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EarthLandingPayload> CODEC = StreamCodec.ofMember(
            EarthLandingPayload::write, EarthLandingPayload::read);

    public EarthLandingPayload {
        if (normal == null || Math.abs(normal.length() - 1) > 1e-6 || navigationEpoch < 0) {
            throw new IllegalArgumentException("Earth landing requires a unit body-fixed normal and navigation epoch");
        }
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeDouble(normal.x()); buffer.writeDouble(normal.y()); buffer.writeDouble(normal.z());
        buffer.writeLong(navigationEpoch);
    }

    private static EarthLandingPayload read(RegistryFriendlyByteBuf buffer) {
        return new EarthLandingPayload(new SpaceVector(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readLong());
    }

    @Override public Type<EarthLandingPayload> type() { return TYPE; }
}
