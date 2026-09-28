package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.cosmos.CosmosIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Discrete client navigation request. Target IDs never authorize discovery or arbitrary world mutation. */
public record FlightActionPayload(Action action, String target) implements CustomPacketPayload {
    public static final Type<FlightActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            AstraEngine.MOD_ID, "flight_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FlightActionPayload> CODEC = StreamCodec.ofMember(
            FlightActionPayload::write, FlightActionPayload::read);

    /** Bounded presentation-navigation operations available to the requesting player. */
    public enum Action { TOGGLE, SCAN, JUMP_SYSTEM, APPROACH_BODY, SPEED_UP, SPEED_DOWN, BRAKE }

    public FlightActionPayload {
        if (action == null || target == null || target.length() > 64
                || !target.matches("[a-z0-9_.:-]*")
                || action == Action.JUMP_SYSTEM && !CosmosIds.isKnownId(target)
                || action == Action.APPROACH_BODY && !target.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid flight action target");
        }
    }
    private void write(RegistryFriendlyByteBuf buffer) { buffer.writeEnum(action); buffer.writeUtf(target, 64); }
    private static FlightActionPayload read(RegistryFriendlyByteBuf buffer) {
        return new FlightActionPayload(buffer.readEnum(Action.class), buffer.readUtf(64));
    }
    @Override
    public Type<FlightActionPayload> type() { return TYPE; }
}
