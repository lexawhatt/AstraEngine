package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.surface.EarthChart;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-to-client connection geography. Zero is legacy/unbound; one selects the pinned continental Earth. */
public record EarthContextPayload(int version) implements CustomPacketPayload {
    public static final Type<EarthContextPayload> TYPE = new Type<>(ResourceLocation.parse("astraengine:earth_context"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EarthContextPayload> CODEC = StreamCodec.ofMember(
            (value, buffer) -> buffer.writeVarInt(value.version()), buffer -> new EarthContextPayload(buffer.readVarInt()));

    public EarthContextPayload {
        if (version != 0 && version != EarthChart.VERSION) {
            throw new IllegalArgumentException("Unsupported Earth connection geography version: " + version);
        }
    }

    @Override public Type<EarthContextPayload> type() { return TYPE; }
}
