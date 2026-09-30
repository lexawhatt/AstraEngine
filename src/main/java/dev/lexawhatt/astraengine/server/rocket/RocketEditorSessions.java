package dev.lexawhatt.astraengine.server.rocket;

import dev.lexawhatt.astraengine.api.rocket.RocketEditorHost;
import dev.lexawhatt.astraengine.network.RocketEditorCommandPayload;
import dev.lexawhatt.astraengine.network.RocketEditorStatePayload;
import dev.lexawhatt.astraengine.network.RocketEditorStatePayload.Status;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Own-player, nonpersisted sessions. Requests never trust client positions, permissions, revisions or definitions. */
public final class RocketEditorSessions {
    /** Immutable transient session bound to the exact loaded host instance; stored only on its owning server player. */
    public record Session(UUID token, ResourceKey<Level> dimension, BlockPos position, BlockEntity host, long lastActionTick) {}
    private RocketEditorSessions() {}

    /** Opens or replaces an editor token after loaded-host/range/permission/model checks, on the server thread. */
    public static boolean open(ServerPlayer player, BlockPos position) {
        requireThread(player);
        if (position == null) { throw new IllegalArgumentException("A rocket editor position is required"); }
        var level = player.serverLevel();
        BlockEntity host = level.hasChunkAt(position) ? level.getBlockEntity(position) : null;
        if (!authorized(player, position, host)) {
            player.displayClientMessage(Component.translatable("astraengine.rocket.status.denied"), true); return false;
        }
        RocketEditorHost editor = (RocketEditorHost) host;
        try { RocketWorkshop.catalog().validate(editor.blueprint()); }
        catch (IllegalArgumentException invalid) {
            player.displayClientMessage(Component.translatable("astraengine.rocket.status.invalid"), true); return false;
        }
        Session session = new Session(UUID.randomUUID(), level.dimension(), position.immutable(), host, level.getGameTime() - 4);
        player.setData(RocketWorkshop.EDIT_SESSION, Optional.of(session)); send(player, session, Status.OPEN); return true;
    }

    /** Processes a bounded main-thread request with token identity, live-host checks and revision compare-and-set. */
    public static void handle(ServerPlayer player, RocketEditorCommandPayload command) {
        requireThread(player);
        if (command == null) { throw new IllegalArgumentException("A rocket editor command is required"); }
        Session session = player.getData(RocketWorkshop.EDIT_SESSION).orElse(null);
        if (session == null || !session.token().equals(command.session())) {
            closed(player, command.session()); return;
        }
        if (command.action() == RocketEditorCommandPayload.Action.CLOSE) {
            player.removeData(RocketWorkshop.EDIT_SESSION); closed(player, session.token()); return;
        }
        if (!live(player, session)) {
            player.removeData(RocketWorkshop.EDIT_SESSION); closed(player, session.token()); return;
        }
        RocketEditorHost host = (RocketEditorHost) session.host(); long now = player.serverLevel().getGameTime();
        if (command.revision() != host.revision()) { send(player, session, Status.STALE); return; }
        if (now - session.lastActionTick() < 4) { send(player, session, Status.RATE_LIMITED); return; }
        session = new Session(session.token(), session.dimension(), session.position(), session.host(), now);
        player.setData(RocketWorkshop.EDIT_SESSION, Optional.of(session));
        if (command.action() == RocketEditorCommandPayload.Action.SAVE) {
            try { RocketWorkshop.catalog().validate(command.blueprint()); }
            catch (IllegalArgumentException invalid) { send(player, session, Status.INVALID); return; }
            if (host.revision() == Long.MAX_VALUE) { send(player, session, Status.INVALID); return; }
            host.setBlueprint(command.blueprint()); send(player, session, Status.SAVED);
        } else {
            send(player, session, RocketDeployment.deploy(player.serverLevel(), session.position(), host));
        }
    }

    /** Expires inaccessible hosts without retaining chunks or worlds after travel, block replacement or logout. */
    public static void tick(ServerPlayer player) {
        if (!player.hasData(RocketWorkshop.EDIT_SESSION)) { return; }
        Session session = player.getData(RocketWorkshop.EDIT_SESSION).orElse(null);
        if (session != null && !live(player, session)) {
            player.removeData(RocketWorkshop.EDIT_SESSION); closed(player, session.token());
        }
    }

    private static boolean live(ServerPlayer player, Session session) {
        return player.serverLevel().dimension().equals(session.dimension())
                && player.serverLevel().hasChunkAt(session.position())
                && player.serverLevel().getBlockEntity(session.position()) == session.host()
                && authorized(player, session.position(), session.host());
    }
    private static boolean authorized(ServerPlayer player, BlockPos position, BlockEntity host) {
        return player.isAlive() && !player.isSpectator() && player.getAbilities().mayBuild
                && player.distanceToSqr(Vec3.atCenterOf(position)) <= 64
                && !player.server.isUnderSpawnProtection(player.serverLevel(), position, player)
                && host instanceof RocketEditorHost editor && !host.isRemoved()
                && editor.revision() >= 0 && editor.canEdit(player);
    }
    private static void send(ServerPlayer player, Session session, Status status) {
        RocketEditorHost host = (RocketEditorHost) session.host();
        PacketDistributor.sendToPlayer(player, new RocketEditorStatePayload(session.token(), session.dimension().location(),
                session.position(), host.revision(), host.blueprint(), status));
    }
    private static void closed(ServerPlayer player, UUID token) {
        PacketDistributor.sendToPlayer(player, new RocketEditorStatePayload(token, player.serverLevel().dimension().location(),
                BlockPos.ZERO, 0, new RocketBlueprint("Rocket", List.of()), Status.CLOSED));
    }
    private static void requireThread(ServerPlayer player) {
        if (player == null) { throw new IllegalArgumentException("A server player is required for the rocket editor"); }
        if (!player.server.isSameThread()) { throw new IllegalStateException("Rocket editor operations require the owning server thread"); }
    }
}
