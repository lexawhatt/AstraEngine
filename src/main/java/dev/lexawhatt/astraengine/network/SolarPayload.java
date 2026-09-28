package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Small server-to-client diagnostic Sol snapshot; every numeric field is validated before presentation. */
public record SolarPayload(StellarEvolutionSnapshot snapshot) implements CustomPacketPayload {
    public static final Type<SolarPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "solar"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SolarPayload> CODEC = StreamCodec.ofMember(
            SolarPayload::write, SolarPayload::read);

    public SolarPayload {
        if (snapshot == null) { throw new IllegalArgumentException("Solar snapshot must not be null"); }
    }
    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(snapshot.remaining()); buffer.writeLong(snapshot.extracted()); buffer.writeLong(snapshot.activeTicks());
        buffer.writeEnum(snapshot.phase()); buffer.writeVarInt(snapshot.phaseTicks()); buffer.writeVarInt(snapshot.drainTicks());
        buffer.writeVarInt(snapshot.drainElapsed()); buffer.writeBoolean(snapshot.running());
        buffer.writeLong(snapshot.revision()); buffer.writeLong(snapshot.cycle());
    }
    private static SolarPayload read(RegistryFriendlyByteBuf buffer) {
        return new SolarPayload(new StellarEvolutionSnapshot(buffer.readLong(), buffer.readLong(), buffer.readLong(),
                buffer.readEnum(StellarEvolutionSnapshot.Phase.class), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readBoolean(), buffer.readLong(), buffer.readLong()));
    }
    @Override
    public Type<SolarPayload> type() { return TYPE; }
}
