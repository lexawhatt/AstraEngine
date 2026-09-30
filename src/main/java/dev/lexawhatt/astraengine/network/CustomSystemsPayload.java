package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.celestial.CelestialSystems;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.server.CosmosDescriptorCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client replacement snapshot of this player's discovered custom descriptors, sent before navigation.
 * Contains no creation request or authority. The receiving connection owns the immutable list; an empty list clears it.
 */
public record CustomSystemsPayload(List<CosmosSystem> systems) implements CustomPacketPayload {
    public static final Type<CustomSystemsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            AstraEngine.MOD_ID, "custom_systems"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CustomSystemsPayload> CODEC = StreamCodec.ofMember(
            CustomSystemsPayload::write, CustomSystemsPayload::read);

    /** Creates a defensively copied snapshot with at most 64 valid systems and no duplicate identities. */
    public CustomSystemsPayload {
        if (systems == null || systems.size() > CelestialSystems.MAX_CUSTOM_SYSTEMS) {
            throw new IllegalArgumentException("Custom descriptor snapshot must contain 0..64 systems");
        }
        Set<String> ids = new HashSet<>();
        for (CosmosSystem system : systems) {
            CelestialSystems.validateCustom(system);
            if (!ids.add(system.id())) {
                throw new IllegalArgumentException("Duplicate custom system in snapshot: " + system.id());
            }
        }
        if (CosmosDescriptorCodec.snapshotBytes(systems) > CosmosDescriptorCodec.MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("Custom descriptor snapshot exceeds its 900-KiB wire budget");
        }
        systems = List.copyOf(systems);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(systems.size());
        for (CosmosSystem system : systems) {
            CosmosDescriptorCodec.write(buffer, system);
        }
    }

    private static CustomSystemsPayload read(RegistryFriendlyByteBuf buffer) {
        if (buffer.readableBytes() > CosmosDescriptorCodec.MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("Custom descriptor packet exceeds its 900-KiB wire budget");
        }
        int count = buffer.readVarInt();
        if (count < 0 || count > CelestialSystems.MAX_CUSTOM_SYSTEMS) {
            throw new IllegalArgumentException("Custom descriptor packet count exceeds bounds");
        }
        List<CosmosSystem> systems = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            systems.add(CosmosDescriptorCodec.read(buffer));
        }
        return new CustomSystemsPayload(systems);
    }

    @Override
    public Type<CustomSystemsPayload> type() {
        return TYPE;
    }
}
