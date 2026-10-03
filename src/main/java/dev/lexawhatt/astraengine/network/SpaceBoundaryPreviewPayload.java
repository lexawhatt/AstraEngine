package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A server-selected real destination neighborhood prepared before crossing the physical space boundary. */
public record SpaceBoundaryPreviewPayload(ResourceLocation sourceDimension, EarthBoundarySnapshot snapshot)
        implements CustomPacketPayload {
    public static final Type<SpaceBoundaryPreviewPayload> TYPE = new Type<>(ResourceLocation.parse("astraengine:space_boundary_preview"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpaceBoundaryPreviewPayload> CODEC = StreamCodec.ofMember(
            SpaceBoundaryPreviewPayload::write, SpaceBoundaryPreviewPayload::read);
    public SpaceBoundaryPreviewPayload {
        if (sourceDimension == null || snapshot == null) { throw new IllegalArgumentException("A source and actual observation are required"); }
    }
    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeResourceLocation(sourceDimension); EarthBoundaryPayload.CODEC.encode(buffer, new EarthBoundaryPayload(snapshot));
    }
    private static SpaceBoundaryPreviewPayload read(RegistryFriendlyByteBuf buffer) {
        return new SpaceBoundaryPreviewPayload(buffer.readResourceLocation(), EarthBoundaryPayload.CODEC.decode(buffer).snapshot());
    }
    @Override public Type<SpaceBoundaryPreviewPayload> type() { return TYPE; }
}
