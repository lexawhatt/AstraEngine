package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Imminent authoritative surface/space handoff. A null chart denotes the permanent bounded flight stage. */
public record SpaceBoundaryHandoffPayload(long revision, ResourceLocation source, ResourceLocation target,
        CubeStorageChart chart, SpaceVector feet, SpaceVector velocity, FlightOrientation orientation, boolean cancelled)
        implements CustomPacketPayload {
    public static final Type<SpaceBoundaryHandoffPayload> TYPE = new Type<>(ResourceLocation.parse("astraengine:space_boundary_handoff"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpaceBoundaryHandoffPayload> CODEC = StreamCodec.ofMember(
            SpaceBoundaryHandoffPayload::write, SpaceBoundaryHandoffPayload::read);
    public SpaceBoundaryHandoffPayload {
        if (revision < 1 || source == null || target == null || source.equals(target) || feet == null || velocity == null
                || orientation == null || velocity.length() > 128 || chart != null && (!chart.contains(feet)
                    || !target.toString().equals(chart.dimensionId()))
                || chart == null && (!target.toString().equals("astraengine:flight") || feet.length() > 256)) {
            throw new IllegalArgumentException("Invalid physical space-boundary handoff");
        }
    }
    private void write(RegistryFriendlyByteBuf b) {
        b.writeLong(revision); b.writeResourceLocation(source); b.writeResourceLocation(target);
        b.writeBoolean(chart != null); if (chart != null) { CubeStorageCharts.write(b, chart); }
        writeVector(b, feet); writeVector(b, velocity);
        b.writeDouble(orientation.x()); b.writeDouble(orientation.y()); b.writeDouble(orientation.z()); b.writeDouble(orientation.w());
        b.writeBoolean(cancelled);
    }
    private static SpaceBoundaryHandoffPayload read(RegistryFriendlyByteBuf b) {
        return new SpaceBoundaryHandoffPayload(b.readLong(), b.readResourceLocation(), b.readResourceLocation(),
                b.readBoolean() ? CubeStorageCharts.read(b) : null, readVector(b), readVector(b),
                new FlightOrientation(b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble()), b.readBoolean());
    }
    private static void writeVector(RegistryFriendlyByteBuf b, SpaceVector v) { b.writeDouble(v.x()); b.writeDouble(v.y()); b.writeDouble(v.z()); }
    private static SpaceVector readVector(RegistryFriendlyByteBuf b) { return new SpaceVector(b.readDouble(), b.readDouble(), b.readDouble()); }
    @Override public Type<SpaceBoundaryHandoffPayload> type() { return TYPE; }
}
