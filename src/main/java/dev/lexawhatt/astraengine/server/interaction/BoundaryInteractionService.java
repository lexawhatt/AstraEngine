package dev.lexawhatt.astraengine.server.interaction;

import dev.lexawhatt.astraengine.mixin.BoundaryGameModeAccessor;
import dev.lexawhatt.astraengine.network.BoundaryInteractPayload;
import dev.lexawhatt.astraengine.server.EarthBoundaryService;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.BoundaryCollision;
import dev.lexawhatt.astraengine.surface.BoundaryRaycast;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * One server's canonical neighboring interactions. Requests are never authority: current loaded ownership,
 * observation, eye ray, tool, range and normal host permission hooks are checked before mutation. Survival
 * progress advances on server ticks in an isolated host game-mode object, with a six-tick input lease.
 */
public final class BoundaryInteractionService implements AutoCloseable {
    private final MinecraftServer server;
    private final EarthBoundaryService boundaries;
    private final Map<UUID, Mining> mining = new HashMap<>();
    private final Map<UUID, Rate> rates = new HashMap<>();
    private boolean closed;

    public BoundaryInteractionService(MinecraftServer server, EarthBoundaryService boundaries) {
        if (server == null || !server.isSameThread() || boundaries == null) {
            throw new IllegalStateException("Neighbor interaction service requires the owning server thread");
        }
        this.server = server; this.boundaries = boundaries;
    }

    /** Own-connection request handler; invalid, stale, occluded, out-of-reach or rate-limited actions have no effect. */
    public void request(ServerPlayer player, BoundaryInteractPayload request) {
        requirePlayer(player);
        if (request == null) { throw new IllegalArgumentException("A boundary interaction request is required"); }
        if (request.action() == BoundaryInteractPayload.Action.ABORT) { stop(player.getUUID()); return; }
        int now = server.getTickCount();
        var rate = rates.computeIfAbsent(player.getUUID(), ignored -> new Rate(now));
        if (now - rate.start >= 10) { rate.start = now; rate.count = 0; }
        if (++rate.count > 12) { return; }
        if (!boundaries.acceptsInteraction(player, request.revision())) { return; }
        var hit = validate(player, request.owner(), request.position());
        if (hit == null) { stop(player.getUUID()); return; }
        var level = server.getLevel(PlanetSurfaceWorlds.dimension(hit.owner()));
        if (request.action() == BoundaryInteractPayload.Action.KEEP) {
            var active = mining.get(player.getUUID());
            if (active != null && active.player == player && active.owner.equals(hit.owner())
                    && active.position.equals(hit.position())) { active.lease = now + 6; }
            return;
        }
        stop(player.getUUID());
        var mode = actionMode(player, level);
        if (request.action() == BoundaryInteractPayload.Action.USE) {
            if (now - rate.lastUse < 4) { return; }
            rate.lastUse = now;
            BoundaryActionScope.call(player, level, hit.owner(), () -> {
                if (!level.mayInteract(player, hit.position()) || !player.canInteractWithBlock(hit.position(), 0)) { return false; }
                var result = mode.useItemOn(player, level, player.getItemInHand(request.hand()), request.hand(),
                        new BlockHitResult(hit.targetHit(), hit.face(), hit.position(), false));
                if (result.shouldSwing()) { player.swing(request.hand(), true); }
                return true;
            });
            return;
        }
        if (request.hand() != InteractionHand.MAIN_HAND) { return; }
        var initial = level.getBlockState(hit.position());
        var tool = player.getMainHandItem().copy();
        var originalLevel = player.serverLevel();
        BoundaryActionScope.call(player, level, hit.owner(), () -> {
            mode.handleBlockBreakAction(hit.position(), ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    hit.face(), level.getMaxBuildHeight(), 0);
            if (!mode.isCreative() && ((BoundaryGameModeAccessor) mode).astra$destroying()
                    && ((BoundaryActionAccess) player).astra$actionScope() != null
                    && level.getBlockState(hit.position()).equals(initial)) {
                mode.handleBlockBreakAction(hit.position(), ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        hit.face(), level.getMaxBuildHeight(), 0);
            }
            return true;
        });
        if (!mode.isCreative() && player.serverLevel() == originalLevel && !player.isRemoved()
                && (((BoundaryGameModeAccessor) mode).astra$destroying()
                    || ((BoundaryGameModeAccessor) mode).astra$delayedDestroy())
                && level.getBlockState(hit.position()).equals(initial)) {
            mining.put(player.getUUID(), new Mining(player, mode, level, hit.owner(), hit.position(), initial, tool, now + 6));
        }
    }

    /** Tick-thread progress; no packet can accelerate this clock or continue after its source ray/tool changes. */
    public void tick() {
        requireOpen();
        for (UUID id : List.copyOf(mining.keySet())) {
            var active = mining.get(id);
            var player = server.getPlayerList().getPlayer(id);
            if (player != active.player || server.getTickCount() > active.lease
                    || active.mode.getGameModeForPlayer() != player.gameMode.getGameModeForPlayer()
                    || !ItemStack.isSameItemSameComponents(active.tool, player.getMainHandItem())
                    || !active.level.getBlockState(active.position).equals(active.initial)
                    || validate(player, active.owner, active.position) == null) { stop(id); continue; }
            BoundaryActionScope.call(player, active.level, active.owner, () -> { active.mode.tick(); return true; });
            if (!active.level.getBlockState(active.position).equals(active.initial)) { stop(id); }
        }
        if (server.getTickCount() % 100 == 0) {
            rates.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        }
    }

    /** Logout/respawn cleanup. World blocks and inventory remain host-owned. */
    public void forget(ServerPlayer player) { requirePlayer(player); stop(player.getUUID()); rates.remove(player.getUUID()); }

    private BoundaryRaycast.Hit validate(ServerPlayer player, CubeStorageChart requested, BlockPos position) {
        if (player == null || !player.isAlive() || player.isRemoved() || player.isSpectator() || player.isPassenger()
                || player.containerMenu != player.inventoryMenu) { return null; }
        var source = PlanetSurfaceWorlds.getCube(player.serverLevel()).orElse(null);
        if (source == null || source.equals(requested) || !source.geographyId().equals(requested.geographyId())) { return null; }
        var target = server.getLevel(PlanetSurfaceWorlds.dimension(requested));
        if (target == null || !PlanetSurfaceWorlds.getCube(target).filter(requested::equals).isPresent()
                || target.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) == null) { return null; }
        double reach = Math.min(BoundaryRaycast.MAX_REACH_METERS, player.blockInteractionRange());
        var direction = player.getLookAngle();
        if (!Double.isFinite(reach) || reach <= 0 || !Double.isFinite(direction.lengthSqr())
                || direction.lengthSqr() < 1e-12) { return null; }
        // Host yaw/pitch use the float sine table and are not an exactly unit-length ray at oblique angles.
        var hit = BoundaryRaycast.pick(source, player.getEyePosition(), direction.normalize(), reach,
                owner -> BoundaryCollision.observations(player.serverLevel(), owner), player).orElse(null);
        return hit != null && hit.owner().equals(requested) && hit.position().equals(position) ? hit : null;
    }

    private static ServerPlayerGameMode actionMode(ServerPlayer player, ServerLevel level) {
        var mode = new ServerPlayerGameMode(player) {
            @Override public boolean destroyBlock(BlockPos position) {
                var scope = ((BoundaryActionAccess) player).astra$actionScope();
                return scope != null && scope.target() == level && player.isAlive() && !player.isRemoved()
                        && super.destroyBlock(position);
            }
        };
        ((BoundaryGameModeAccessor) mode).astra$actionGameType(player.gameMode.getGameModeForPlayer(),
                player.gameMode.getPreviousGameModeForPlayer());
        mode.setLevel(level);
        return mode;
    }

    private void stop(UUID id) {
        var active = mining.remove(id);
        if (active != null) { active.level.destroyBlockProgress(active.player.getId(), active.position, -1); }
    }
    @Override public void close() {
        requireOpen(); for (UUID id : List.copyOf(mining.keySet())) { stop(id); } rates.clear(); closed = true;
    }
    private void requirePlayer(ServerPlayer player) {
        requireOpen(); if (player == null || player.server != server) { throw new IllegalArgumentException("Foreign interaction player"); }
    }
    private void requireOpen() {
        if (closed || !server.isSameThread()) { throw new IllegalStateException("Neighbor interactions require their live server thread"); }
    }
    private static final class Rate {
        private int start, count;
        private int lastUse = Integer.MIN_VALUE / 2;
        private Rate(int start) { this.start = start; }
    }
    private static final class Mining {
        private final ServerPlayer player;
        private final ServerPlayerGameMode mode;
        private final ServerLevel level;
        private final CubeStorageChart owner;
        private final BlockPos position;
        private final BlockState initial;
        private final ItemStack tool;
        private int lease;
        private Mining(ServerPlayer player, ServerPlayerGameMode mode, ServerLevel level, CubeStorageChart owner,
                BlockPos position, BlockState initial, ItemStack tool, int lease) {
            this.player = player; this.mode = mode; this.level = level; this.owner = owner;
            this.position = position; this.initial = initial; this.tool = tool; this.lease = lease;
        }
    }
}
