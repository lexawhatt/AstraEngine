package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded server-to-client settings snapshot; Minecraft time packets remain the only sky clock. */
public record SkyProfilePayload(long revision, PlanetarySkyProfile profile) implements CustomPacketPayload {
    public static final Type<SkyProfilePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "sky_profile"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkyProfilePayload> CODEC = StreamCodec.ofMember(
            SkyProfilePayload::write, SkyProfilePayload::read);

    /** Rejects malformed counters or missing settings before connection-owned client state changes. */
    public SkyProfilePayload {
        if (revision < 0 || profile == null) { throw new IllegalArgumentException("Invalid sky profile payload"); }
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(revision);
        buffer.writeInt(profile.yearDays());
        buffer.writeDouble(profile.latitudeDegrees());
        buffer.writeDouble(profile.axialTiltDegrees());
        buffer.writeDouble(profile.eccentricity());
        buffer.writeDouble(profile.seasonOffsetDays());
        buffer.writeDouble(profile.lightPollution());
        buffer.writeDouble(profile.sunSizeMultiplier());
    }

    private static SkyProfilePayload read(RegistryFriendlyByteBuf buffer) {
        return new SkyProfilePayload(buffer.readLong(), new PlanetarySkyProfile(buffer.readInt(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
    }

    @Override
    public Type<SkyProfilePayload> type() { return TYPE; }
}
