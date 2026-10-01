package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.cosmos.NavigationPolicy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Small server-to-client policy snapshot, independent of persistent discovery and descriptor wire formats. */
public record NavigationPolicyPayload(NavigationPolicy policy) implements CustomPacketPayload {
    public static final Type<NavigationPolicyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "navigation_policy"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NavigationPolicyPayload> CODEC = StreamCodec.ofMember(
            NavigationPolicyPayload::write, NavigationPolicyPayload::read);

    /** Requires a validated immutable policy. */
    public NavigationPolicyPayload {
        if (policy == null) { throw new IllegalArgumentException("Navigation policy is required"); }
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(policy.freeNavigation());
        buffer.writeVarInt(policy.travelSeconds());
    }

    private static NavigationPolicyPayload read(RegistryFriendlyByteBuf buffer) {
        return new NavigationPolicyPayload(new NavigationPolicy(buffer.readBoolean(), buffer.readVarInt()));
    }

    @Override
    public Type<NavigationPolicyPayload> type() { return TYPE; }
}
