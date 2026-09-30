package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import dev.lexawhatt.astraengine.server.rocket.RocketBlueprintCodec;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-authorized editor state; only OPEN may create a client screen, other statuses acknowledge the same token. */
public record RocketEditorStatePayload(UUID session, ResourceLocation dimension, BlockPos position, long revision,
        RocketBlueprint blueprint, Status status) implements CustomPacketPayload {
    public enum Status { OPEN, SAVED, DEPLOYED, STALE, RATE_LIMITED, OBSTRUCTED, INVALID, DENIED, CLOSED }
    public static final Type<RocketEditorStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "rocket_editor_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RocketEditorStatePayload> CODEC = StreamCodec.ofMember(
            RocketEditorStatePayload::write, RocketEditorStatePayload::read);

    public RocketEditorStatePayload {
        if (session == null || dimension == null || position == null || revision < 0 || blueprint == null || status == null) {
            throw new IllegalArgumentException("Invalid rocket editor state");
        }
        position = position.immutable();
    }
    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(session); buffer.writeResourceLocation(dimension); buffer.writeBlockPos(position);
        buffer.writeLong(revision); buffer.writeEnum(status); RocketBlueprintCodec.write(buffer, blueprint);
    }
    private static RocketEditorStatePayload read(RegistryFriendlyByteBuf buffer) {
        UUID token = buffer.readUUID(); ResourceLocation dimension = buffer.readResourceLocation(); BlockPos position = buffer.readBlockPos();
        long revision = buffer.readLong(); Status status = buffer.readEnum(Status.class);
        return new RocketEditorStatePayload(token, dimension, position, revision, RocketBlueprintCodec.read(buffer), status);
    }
    @Override
    public Type<RocketEditorStatePayload> type() { return TYPE; }
}
