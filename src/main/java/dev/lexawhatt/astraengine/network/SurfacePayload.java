package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-authored surface context. Presentation may interpolate but cannot commit a transfer. */
public record SurfacePayload(String bodyId, long clockTicks, Phase phase, int remainingTicks,
        double orbitalSeconds, FlightOrientation earthOrientation, long calendarEpoch)
        implements CustomPacketPayload {
    public static final Type<SurfacePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            AstraEngine.MOD_ID, "surface"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SurfacePayload> CODEC = StreamCodec.ofMember(
            SurfacePayload::write, SurfacePayload::read);

    /** Preparation retains its source; only SURFACE denotes a committed arrival. */
    public enum Phase { NONE, PREPARING, DESCENDING, SURFACE, ASCENDING }

    public SurfacePayload {
        if (!Double.isFinite(orbitalSeconds) || calendarEpoch < 0 || bodyId == null || phase == null || clockTicks < 0 || clockTicks > 1_000_000_000_000L
                || remainingTicks < 0 || remainingTicks > 480
                || (phase == Phase.NONE || phase == Phase.SURFACE) && remainingTicks != 0
                || !bodyId.matches("[a-z0-9_-]{0,64}") || (phase == Phase.NONE) != bodyId.isEmpty()) {
            throw new IllegalArgumentException("Invalid surface context");
        }
    }

    /** Legacy occupied-clock context, retained for consumers and verification fixtures. */
    public SurfacePayload(String bodyId, long clockTicks, Phase phase, int remainingTicks) {
        this(bodyId, clockTicks, phase, remainingTicks, clockTicks / 20.0, null, 0);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(bodyId, 64); buffer.writeLong(clockTicks);
        buffer.writeEnum(phase); buffer.writeVarInt(remainingTicks);
        buffer.writeDouble(orbitalSeconds); buffer.writeBoolean(earthOrientation != null);
        if (earthOrientation != null) {
            buffer.writeDouble(earthOrientation.x()); buffer.writeDouble(earthOrientation.y());
            buffer.writeDouble(earthOrientation.z()); buffer.writeDouble(earthOrientation.w());
        }
        buffer.writeLong(calendarEpoch);
    }

    private static SurfacePayload read(RegistryFriendlyByteBuf buffer) {
        String bodyId = buffer.readUtf(64);
        long clock = buffer.readLong();
        Phase phase = buffer.readEnum(Phase.class);
        int remaining = buffer.readVarInt();
        double orbital = buffer.readDouble();
        FlightOrientation rotation = buffer.readBoolean() ? new FlightOrientation(buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble()) : null;
        return new SurfacePayload(bodyId, clock, phase, remaining, orbital, rotation, buffer.readLong());
    }

    @Override
    public Type<SurfacePayload> type() { return TYPE; }
}
