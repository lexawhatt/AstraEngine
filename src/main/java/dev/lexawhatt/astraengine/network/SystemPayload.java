package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.SystemDescriptor;
import dev.lexawhatt.astraengine.api.SystemSnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Small server-to-client snapshot; dimensional identity prevents displaying a previous world's state. */
public record SystemPayload(SystemSnapshot snapshot, ResourceLocation dimension, int transitTicks)
        implements CustomPacketPayload {
    public static final Type<SystemPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            AstraEngine.MOD_ID, "system_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SystemPayload> CODEC = StreamCodec.ofMember(
            SystemPayload::write, SystemPayload::read);

    public SystemPayload {
        if (snapshot == null || dimension == null || transitTicks < 0 || transitTicks > 80) {
            throw new IllegalArgumentException("Invalid system payload");
        }
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        SystemDescriptor descriptor = snapshot.descriptor();
        buffer.writeUtf(descriptor.id(), 64);
        buffer.writeLong(descriptor.seed());
        buffer.writeVarInt(descriptor.generatorVersion());
        buffer.writeVarInt(descriptor.temperatureKelvin());
        buffer.writeVarInt(descriptor.planetCount());
        buffer.writeLong(descriptor.resourceCapacity());
        buffer.writeLong(snapshot.remainingResource());
        buffer.writeLong(snapshot.activeTicks());
        buffer.writeVarInt(snapshot.ticksUntilBurst());
        buffer.writeLong(snapshot.burstCount());
        buffer.writeLong(snapshot.revision());
        buffer.writeResourceLocation(dimension);
        buffer.writeVarInt(transitTicks);
    }

    private static SystemPayload read(RegistryFriendlyByteBuf buffer) {
        SystemDescriptor descriptor = new SystemDescriptor(buffer.readUtf(64), buffer.readLong(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readLong());
        SystemSnapshot snapshot = new SystemSnapshot(descriptor, buffer.readLong(), buffer.readLong(),
                buffer.readVarInt(), buffer.readLong(), buffer.readLong());
        return new SystemPayload(snapshot, buffer.readResourceLocation(), buffer.readVarInt());
    }

    @Override
    public Type<SystemPayload> type() { return TYPE; }
}
