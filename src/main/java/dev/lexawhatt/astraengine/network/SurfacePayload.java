package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-authored surface context. Presentation may interpolate but cannot commit a transfer. */
public record SurfacePayload(String bodyId, long clockTicks, Phase phase, int remainingTicks)
        implements CustomPacketPayload {
    public static final Type<SurfacePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            AstraEngine.MOD_ID, "surface"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SurfacePayload> CODEC = StreamCodec.ofMember(
            SurfacePayload::write, SurfacePayload::read);

    /** Preparation retains its source; only SURFACE denotes a committed arrival. */
    public enum Phase { NONE, PREPARING, DESCENDING, SURFACE, ASCENDING }

    public SurfacePayload {
        if (bodyId == null || phase == null || clockTicks < 0 || clockTicks > 1_000_000_000_000L
                || remainingTicks < 0 || remainingTicks > 480
                || (phase == Phase.NONE || phase == Phase.SURFACE) && remainingTicks != 0
                || !bodyId.matches("[a-z0-9_-]{0,64}") || (phase == Phase.NONE) != bodyId.isEmpty()) {
            throw new IllegalArgumentException("Invalid surface context");
        }
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(bodyId, 64); buffer.writeLong(clockTicks);
        buffer.writeEnum(phase); buffer.writeVarInt(remainingTicks);
    }

    private static SurfacePayload read(RegistryFriendlyByteBuf buffer) {
        return new SurfacePayload(buffer.readUtf(64), buffer.readLong(), buffer.readEnum(Phase.class), buffer.readVarInt());
    }

    @Override
    public Type<SurfacePayload> type() { return TYPE; }
}
