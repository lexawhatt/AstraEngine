package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-to-server own-flight input. The epoch echoes the last server navigation snapshot,
 * including approach start/end, so delayed input cannot replace an authoritative view.
 * During approach only an explicit brake cancels guidance. RocketService enforces authority and rate limits.
 */
public record FlightControlPayload(float forward, float strafe, float vertical, FlightOrientation orientation,
        boolean brake, long sequence, long navigationEpoch) implements CustomPacketPayload {
    public static final Type<FlightControlPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            AstraEngine.MOD_ID, "flight_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FlightControlPayload> CODEC = StreamCodec.ofMember(
            FlightControlPayload::write, FlightControlPayload::read);

    public FlightControlPayload {
        new FlightDynamics.Input(forward, strafe, vertical, orientation, brake);
        if (sequence < 0 || navigationEpoch < 0) {
            throw new IllegalArgumentException("Flight input sequence and navigation epoch must be nonnegative");
        }
    }
    /** Compatibility adapter for old fixtures; version-three semantics retain the quaternion wire layout. */
    public FlightControlPayload(float forward, float strafe, float vertical, float yaw, float pitch,
            boolean brake, long sequence, long navigationEpoch) {
        this(forward, strafe, vertical, FlightDynamics.legacyOrientation(yaw, pitch), brake, sequence, navigationEpoch);
    }
    public float yaw() { return orientation.yaw(); }
    public float pitch() { return orientation.pitch(); }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeFloat(forward); buffer.writeFloat(strafe); buffer.writeFloat(vertical);
        buffer.writeDouble(orientation.x()); buffer.writeDouble(orientation.y());
        buffer.writeDouble(orientation.z()); buffer.writeDouble(orientation.w());
        buffer.writeBoolean(brake); buffer.writeLong(sequence);
        buffer.writeLong(navigationEpoch);
    }
    private static FlightControlPayload read(RegistryFriendlyByteBuf buffer) {
        return new FlightControlPayload(buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                new FlightOrientation(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readBoolean(), buffer.readLong(), buffer.readLong());
    }
    @Override
    public Type<FlightControlPayload> type() { return TYPE; }
}
