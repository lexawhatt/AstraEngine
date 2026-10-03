package dev.lexawhatt.astraengine.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Own-session preparation acknowledgement; it contains no client-authorized destination or movement. */
public record SpaceBoundaryReadyPayload(long revision) implements CustomPacketPayload {
    public static final Type<SpaceBoundaryReadyPayload> TYPE = new Type<>(ResourceLocation.parse("astraengine:space_boundary_ready"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpaceBoundaryReadyPayload> CODEC = StreamCodec.of(
            (buffer, value) -> buffer.writeLong(value.revision), buffer -> new SpaceBoundaryReadyPayload(buffer.readLong()));
    public SpaceBoundaryReadyPayload {
        if (revision < 1) { throw new IllegalArgumentException("A positive preparation revision is required"); }
    }
    @Override public Type<SpaceBoundaryReadyPayload> type() { return TYPE; }
}
