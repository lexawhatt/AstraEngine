package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Own-player inspection speed request in meters/second; the server checks session ownership and action rate. */
public record FlightSpeedPayload(double speedMetersPerSecond) implements CustomPacketPayload {
    public static final Type<FlightSpeedPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            AstraEngine.MOD_ID, "flight_speed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FlightSpeedPayload> CODEC = StreamCodec.ofMember(
            FlightSpeedPayload::write, buffer -> new FlightSpeedPayload(buffer.readDouble()));

    /** Rejects non-finite speeds or values outside the free-camera range before server mutation. */
    public FlightSpeedPayload {
        FlightDynamics.validateSpeed(speedMetersPerSecond);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeDouble(speedMetersPerSecond);
    }

    @Override
    public Type<FlightSpeedPayload> type() {
        return TYPE;
    }
}
