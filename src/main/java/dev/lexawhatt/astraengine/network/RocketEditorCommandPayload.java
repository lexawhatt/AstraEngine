package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import dev.lexawhatt.astraengine.server.rocket.RocketBlueprintCodec;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Own-session request; token/revision are rechecked against the loaded host before server mutation. */
public record RocketEditorCommandPayload(UUID session, long revision, Action action, RocketBlueprint blueprint)
        implements CustomPacketPayload {
    public enum Action { SAVE, DEPLOY, CLOSE }
    public static final Type<RocketEditorCommandPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "rocket_editor_command"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RocketEditorCommandPayload> CODEC = StreamCodec.ofMember(
            RocketEditorCommandPayload::write, RocketEditorCommandPayload::read);

    /** SAVE requires a validated blueprint; DEPLOY and CLOSE require null and never carry draft data. */
    public RocketEditorCommandPayload {
        if (session == null || revision < 0 || action == null || (action == Action.SAVE) != (blueprint != null)) {
            throw new IllegalArgumentException("Invalid rocket editor command");
        }
    }
    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(session); buffer.writeLong(revision); buffer.writeEnum(action);
        if (action == Action.SAVE) { RocketBlueprintCodec.write(buffer, blueprint); }
    }
    private static RocketEditorCommandPayload read(RegistryFriendlyByteBuf buffer) {
        UUID token = buffer.readUUID(); long revision = buffer.readLong(); Action action = buffer.readEnum(Action.class);
        return new RocketEditorCommandPayload(token, revision, action, action == Action.SAVE ? RocketBlueprintCodec.read(buffer) : null);
    }
    @Override
    public Type<RocketEditorCommandPayload> type() { return TYPE; }
}
