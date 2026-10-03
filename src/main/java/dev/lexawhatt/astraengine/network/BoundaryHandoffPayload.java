package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-owned imminent chart handoff referencing an already prepared immutable observation. */
public record BoundaryHandoffPayload(long revision, CubeStorageChart source, CubeStorageChart target,
        SpaceVector feet, SpaceVector velocity, FlightOrientation orientation, boolean cancelled) implements CustomPacketPayload {
    public static final Type<BoundaryHandoffPayload> TYPE = new Type<>(ResourceLocation.parse("astraengine:boundary_handoff"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BoundaryHandoffPayload> CODEC = StreamCodec.ofMember(
            BoundaryHandoffPayload::write, BoundaryHandoffPayload::read);
    public BoundaryHandoffPayload {
        if (revision < 1 || source == null || target == null || !source.geographyId().equals(target.geographyId())
                || !target.contains(feet) || velocity == null || orientation == null || velocity.length() > 128) {
            throw new IllegalArgumentException("Invalid prepared geographic handoff");
        }
    }
    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(revision); CubeStorageCharts.write(buffer, source); CubeStorageCharts.write(buffer, target);
        buffer.writeDouble(feet.x()); buffer.writeDouble(feet.y()); buffer.writeDouble(feet.z());
        buffer.writeDouble(velocity.x()); buffer.writeDouble(velocity.y()); buffer.writeDouble(velocity.z());
        buffer.writeDouble(orientation.x()); buffer.writeDouble(orientation.y());
        buffer.writeDouble(orientation.z()); buffer.writeDouble(orientation.w()); buffer.writeBoolean(cancelled);
    }
    private static BoundaryHandoffPayload read(RegistryFriendlyByteBuf buffer) {
        return new BoundaryHandoffPayload(buffer.readLong(), CubeStorageCharts.read(buffer), CubeStorageCharts.read(buffer),
                new SpaceVector(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                new SpaceVector(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                new FlightOrientation(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readBoolean());
    }
    @Override public Type<BoundaryHandoffPayload> type() { return TYPE; }
}
