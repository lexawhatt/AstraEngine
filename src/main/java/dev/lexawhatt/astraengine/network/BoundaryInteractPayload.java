package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

/** Own-player neighboring-block request. The server independently checks current observation, ray and permission. */
public record BoundaryInteractPayload(Action action, CubeStorageChart owner, BlockPos position,
                                      InteractionHand hand, long revision) implements CustomPacketPayload {
    public enum Action { START, KEEP, ABORT, USE }
    public static final Type<BoundaryInteractPayload> TYPE = new Type<>(ResourceLocation.parse("astraengine:boundary_interact"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BoundaryInteractPayload> CODEC = StreamCodec.ofMember(
            BoundaryInteractPayload::write, buffer -> new BoundaryInteractPayload(buffer.readEnum(Action.class),
                    CubeStorageCharts.read(buffer), buffer.readBlockPos(), buffer.readEnum(InteractionHand.class), buffer.readLong()));

    public BoundaryInteractPayload {
        if (action == null || owner == null || position == null || hand == null || revision < 1) {
            throw new IllegalArgumentException("Boundary interaction requires a bounded current observation target");
        }
        position = position.immutable();
    }
    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeEnum(action); CubeStorageCharts.write(buffer, owner); buffer.writeBlockPos(position);
        buffer.writeEnum(hand); buffer.writeLong(revision);
    }
    @Override public Type<BoundaryInteractPayload> type() { return TYPE; }
}
