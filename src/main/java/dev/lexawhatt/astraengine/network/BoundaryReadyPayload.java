package dev.lexawhatt.astraengine.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client acknowledges prepared observation geometry only; it cannot select a destination or authorize movement. */
public record BoundaryReadyPayload(long revision) implements CustomPacketPayload {
    public static final Type<BoundaryReadyPayload> TYPE = new Type<>(ResourceLocation.parse("astraengine:boundary_ready"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BoundaryReadyPayload> CODEC = StreamCodec.ofMember(
            (value, buffer) -> buffer.writeLong(value.revision), buffer -> new BoundaryReadyPayload(buffer.readLong()));
    public BoundaryReadyPayload {
        if (revision < 1) { throw new IllegalArgumentException("Boundary readiness requires a positive revision"); }
    }
    @Override public Type<BoundaryReadyPayload> type() { return TYPE; }
}
