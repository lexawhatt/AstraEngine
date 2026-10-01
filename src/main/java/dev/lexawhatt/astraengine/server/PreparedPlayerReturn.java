package dev.lexawhatt.astraengine.server;

import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Bounded server-thread return preparation; owns temporary tickets only and never edits destination blocks. */
public final class PreparedPlayerReturn {
    private static final TicketType<UUID> TICKET = TicketType.create("astraengine_return", UUID::compareTo, 240);
    private static final int TICKET_DISTANCE = 2;
    private static final int SEARCH_RADIUS = 4;
    private static final int SEARCH_HEIGHT = 12;
    private static final double POSITION_TOLERANCE_SQUARED = 1e-6;

    private PreparedPlayerReturn() { }

    /**
     * Whether the player's current source can be saved for a bounded return. Requires the owning server thread;
     * checks a full standing volume even when the player crouches or crawls. Unsupported positions return false.
     * Only already loaded collision/fluid cells are inspected; this check adds no tickets and generates no chunks.
     */
    public static boolean acceptsSource(MinecraftServer server, ServerPlayer player) {
        requireServer(server);
        if (player == null || player.getServer() != server) {
            throw new IllegalArgumentException("Prepared return source requires an owned player");
        }
        if (!player.isAlive() || player.isPassenger() || player.isSleeping() || !boundedPosition(player.position())
                || !Float.isFinite(player.getYRot()) || !Float.isFinite(player.getXRot())) { return false; }
        ServerLevel level = player.serverLevel();
        AABB box = player.getDimensions(Pose.STANDING).makeBoundingBox(player.position());
        ChunkPos center = new ChunkPos(player.blockPosition());
        if (!withinBounds(level, box, center)) { return false; }
        // The host block-collision iterator also inspects one neighboring cell around the box.
        int minX = Math.floorDiv((int) Math.floor(box.minX - 1e-7) - 1, 16);
        int maxX = Math.floorDiv((int) Math.floor(box.maxX + 1e-7) + 1, 16);
        int minZ = Math.floorDiv((int) Math.floor(box.minZ - 1e-7) - 1, 16);
        int maxZ = Math.floorDiv((int) Math.floor(box.maxZ + 1e-7) + 1, 16);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) { return false; }
            }
        }
        return level.noCollision(player, box) && safeLiquid(level, player, player.position(), box);
    }

    /**
     * Attempts a return without synchronously loading chunks. False means not ready, obstructed, unavailable,
     * or vetoed; the caller owns bounded retries and must retain recovery data until true. The exact source
     * is preferred, followed by nearby empty player volumes 1..12 meters above it and within 4 meters horizontally.
     * Invalid/null values throw, and every call must run on this player's owning logical-server thread.
     * A failed attempt's ticket expires after 240 ticks unless refreshed; release can abandon it explicitly.
     */
    public static boolean attempt(MinecraftServer server, ServerPlayer player, ResourceKey<Level> targetKey,
            Vec3 targetPosition, float yaw, float pitch) {
        requireServer(server);
        requireTarget(targetKey, targetPosition);
        if (player == null || player.getServer() != server || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Prepared return requires an owned player and finite view angles");
        }
        ServerLevel level = server.getLevel(targetKey);
        if (level == null || !player.isAlive() || targetPosition.y < level.getMinBuildHeight()
                || targetPosition.y >= level.getMaxBuildHeight()) { return false; }
        ChunkPos center = new ChunkPos(BlockPos.containing(targetPosition));
        level.getChunkSource().addRegionTicket(TICKET, center, TICKET_DISTANCE, player.getUUID());
        for (int x = center.x - 1; x <= center.x + 1; x++) {
            for (int z = center.z - 1; z <= center.z + 1; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) { return false; }
            }
        }

        Vec3 destination = clear(level, player, targetPosition, center) ? targetPosition : null;
        for (int up = 1; up <= SEARCH_HEIGHT && destination == null; up++) {
            for (int radius = 0; radius <= SEARCH_RADIUS && destination == null; radius++) {
                for (int x = -radius; x <= radius && destination == null; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius || x * x + z * z > SEARCH_RADIUS * SEARCH_RADIUS) {
                            continue;
                        }
                        Vec3 candidate = targetPosition.add(x, up, z);
                        if (clear(level, player, candidate, center)) { destination = candidate; break; }
                    }
                }
            }
        }
        if (destination == null) { return false; }
        // The host teleport overload returns true even when its underlying dimension transition is vetoed.
        player.teleportTo(level, destination.x, destination.y, destination.z, Set.of(), yaw, pitch);
        if (player.serverLevel() != level || player.position().distanceToSqr(destination) > POSITION_TOLERANCE_SQUARED
                || Math.abs(Math.IEEEremainder((double) player.getYRot() - yaw, 360)) > .001
                || Math.abs((double) player.getXRot() - pitch) > .001) {
            return false;
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        release(server, player.getUUID(), targetKey, targetPosition);
        return true;
    }

    /** Releases this helper's original target ticket on the server thread; no unrelated tickets or chunks are removed. */
    public static void release(MinecraftServer server, UUID playerId, ResourceKey<Level> targetKey, Vec3 targetPosition) {
        requireServer(server);
        requireTarget(targetKey, targetPosition);
        if (playerId == null) { throw new IllegalArgumentException("Prepared return player identity is required"); }
        ServerLevel level = server.getLevel(targetKey);
        if (level != null) {
            level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(BlockPos.containing(targetPosition)),
                    TICKET_DISTANCE, playerId);
        }
    }

    private static boolean clear(ServerLevel level, ServerPlayer player, Vec3 position, ChunkPos center) {
        AABB box = player.getDimensions(Pose.STANDING).makeBoundingBox(position);
        return withinBounds(level, box, center) && level.noCollision(player, box) && safeLiquid(level, player, position, box);
    }

    private static boolean safeLiquid(ServerLevel level, ServerPlayer player, Vec3 position, AABB box) {
        if (!level.containsAnyLiquid(box)) { return true; }
        // Planetary sea arrivals may be swimming, but a saved return never puts the standing eye underwater.
        // Legacy worlds retain the dry-volume contract, and every non-water fluid remains excluded.
        if (EarthWorlds.chart(level).isEmpty()
                || !level.getFluidState(BlockPos.containing(position.x,
                        position.y + player.getEyeHeight(Pose.STANDING), position.z)).isEmpty()) { return false; }
        for (BlockPos block : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            var fluid = level.getFluidState(block);
            if (!fluid.isEmpty() && !fluid.is(FluidTags.WATER)) { return false; }
        }
        return true;
    }

    private static boolean withinBounds(ServerLevel level, AABB box, ChunkPos center) {
        // Collision iteration inspects neighboring cells too. Keep its complete footprint inside the polled 3x3.
        return box.minX >= center.getMinBlockX() - 14 && box.maxX <= center.getMaxBlockX() + 15
                && box.minZ >= center.getMinBlockZ() - 14 && box.maxZ <= center.getMaxBlockZ() + 15
                && box.minY >= level.getMinBuildHeight() && box.maxY <= level.getMaxBuildHeight()
                && level.getWorldBorder().isWithinBounds(box);
    }

    private static void requireServer(MinecraftServer server) {
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("Prepared player return requires the owning server thread");
        }
    }

    private static void requireTarget(ResourceKey<Level> targetKey, Vec3 targetPosition) {
        if (targetKey == null || !boundedPosition(targetPosition)) {
            throw new IllegalArgumentException("Prepared return requires a finite bounded target position and world");
        }
    }

    private static boolean boundedPosition(Vec3 position) {
        return position != null && Double.isFinite(position.x) && Double.isFinite(position.y)
                && Double.isFinite(position.z) && Math.abs(position.x) < 29_999_984 && Math.abs(position.z) < 29_999_984;
    }
}
