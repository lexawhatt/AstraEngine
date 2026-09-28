package dev.lexawhatt.astraengine.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Server-owned bounded travel. Tickets are polled rather than blocking the server for chunk generation. */
public final class TravelService {
    private static final String HOME = "astraengine_home";
    private static final String RECOVERY = "astraengine_recovery";
    private static final TicketType<UUID> TRAVEL = TicketType.create("astraengine_travel", UUID::compareTo, 240);
    private final MinecraftServer server;
    private final Map<UUID, Journey> journeys = new HashMap<>();

    /** Creates the travel owner for one running server. */
    public TravelService(MinecraftServer server) { this.server = server; }

    /** Whether this player already has a pending preparation or transit owned by this service. */
    public boolean busy(ServerPlayer player) { return journeys.containsKey(player.getUUID()); }

    /** Releases outstanding server-owned tickets during shutdown without erasing recovery data. */
    public void close() {
        journeys.forEach(this::release);
        journeys.clear();
    }

    /** Requests a four-second visual transit; permissions are enforced by the diagnostic command caller. */
    public boolean begin(ServerPlayer player, String systemId) {
        if (journeys.containsKey(player.getUUID()) || !player.isAlive() || player.isPassenger()
                || player.isSleeping() || player.serverLevel().dimension().equals(SystemWorlds.TRANSIT)) {
            player.sendSystemMessage(Component.translatable("astraengine.travel.unavailable"));
            return false;
        }
        ResourceKey<Level> target = SystemWorlds.dimension(systemId);
        if (target.equals(player.serverLevel().dimension())) {
            player.sendSystemMessage(Component.translatable("astraengine.travel.already_here"));
            return false;
        }
        ServerLevel destination = server.getLevel(target);
        ServerLevel transit = server.getLevel(SystemWorlds.TRANSIT);
        if (destination == null || transit == null) {
            player.sendSystemMessage(Component.translatable("astraengine.travel.missing_world"));
            return false;
        }
        Point source = Point.of(player);
        if (SystemWorlds.systemId(source.dimension).isEmpty()) {
            player.getPersistentData().put(HOME, source.save());
        }
        journeys.put(player.getUUID(), new Journey(source, systemId));
        addTicket(destination, player.getUUID());
        addTicket(transit, player.getUUID());
        player.sendSystemMessage(Component.translatable("astraengine.travel.preparing", systemId));
        return true;
    }

    /** Polls bounded preparations and advances transit in server ticks. No celestial model is ticked here. */
    public void tick() {
        Iterator<Map.Entry<UUID, Journey>> iterator = journeys.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Journey> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Journey journey = entry.getValue();
            ServerLevel target = server.getLevel(SystemWorlds.dimension(journey.target));
            ServerLevel transit = server.getLevel(SystemWorlds.TRANSIT);
            if (player == null || !player.isAlive() || target == null || transit == null || ++journey.age > 220) {
                if (player != null && player.serverLevel().dimension().equals(SystemWorlds.TRANSIT)) {
                    journey.source.teleport(player, server);
                    player.getPersistentData().remove(RECOVERY);
                    player.sendSystemMessage(Component.translatable("astraengine.travel.failed"));
                }
                release(entry.getKey(), journey);
                iterator.remove();
                continue;
            }
            if (!journey.inTransit) {
                if (!player.serverLevel().dimension().equals(journey.source.dimension)) {
                    release(entry.getKey(), journey);
                    iterator.remove();
                    continue;
                }
                if (target.getChunkSource().getChunkNow(0, 0) == null
                        || transit.getChunkSource().getChunkNow(0, 0) == null) {
                    continue;
                }
                initializeLanding(target, journey.target, false);
                initializeLanding(transit, "transit", true);
                Vec3 landing = safeLanding(target);
                if (landing == null || !canStandAt(transit, new BlockPos(8, 80, 8))) {
                    player.sendSystemMessage(Component.translatable("astraengine.travel.obstructed"));
                    release(entry.getKey(), journey);
                    iterator.remove();
                    continue;
                }
                player.getPersistentData().put(RECOVERY, journey.source.save());
                player.teleportTo(transit, 8.5, 80, 8.5, Set.of(), player.getYRot(), player.getXRot());
                journey.inTransit = true;
                journey.remaining = 80;
            } else if (!player.serverLevel().dimension().equals(SystemWorlds.TRANSIT)) {
                player.getPersistentData().remove(RECOVERY);
                release(entry.getKey(), journey);
                iterator.remove();
            } else {
                player.setDeltaMovement(Vec3.ZERO);
                player.fallDistance = 0;
                if (player.position().distanceToSqr(8.5, 80, 8.5) > 0.01) {
                    player.teleportTo(8.5, 80, 8.5);
                }
                if (--journey.remaining == 0) {
                    Vec3 landing = safeLanding(target);
                    if (landing != null) {
                        player.teleportTo(target, landing.x, landing.y, landing.z, Set.of(), player.getYRot(), player.getXRot());
                        player.sendSystemMessage(Component.translatable("astraengine.travel.arrived", journey.target));
                    } else {
                        journey.source.teleport(player, server);
                        player.sendSystemMessage(Component.translatable("astraengine.travel.obstructed"));
                    }
                    player.getPersistentData().remove(RECOVERY);
                    release(entry.getKey(), journey);
                    iterator.remove();
                }
            }
        }
    }

    /** Returns the transit target used only to choose a player's synchronized background. */
    public String target(ServerPlayer player) {
        Journey journey = journeys.get(player.getUUID());
        return journey != null && journey.inTransit ? journey.target : null;
    }

    /** Remaining visual-transit ticks, or zero outside an active transit. */
    public int remainingTicks(ServerPlayer player) {
        Journey journey = journeys.get(player.getUUID());
        return journey == null ? 0 : journey.remaining;
    }

    /** Restores an interrupted transit on login; a normal system login preserves the player's position. */
    public void recover(ServerPlayer player) {
        if (player.serverLevel().dimension().equals(SystemWorlds.TRANSIT)) {
            Point.load(player.getPersistentData().getCompound(RECOVERY), server).teleport(player, server);
        }
        player.getPersistentData().remove(RECOVERY);
    }

    /** Returns to the saved pre-expedition position and cancels any outstanding transit. */
    public void returnHome(ServerPlayer player) {
        if (!player.getPersistentData().contains(HOME)) {
            player.sendSystemMessage(Component.translatable("astraengine.system.visit_first"));
            return;
        }
        Journey journey = journeys.remove(player.getUUID());
        if (journey != null) { release(player.getUUID(), journey); }
        Point.load(player.getPersistentData().getCompound(HOME), server).teleport(player, server);
        player.getPersistentData().remove(RECOVERY);
        player.getPersistentData().remove(HOME);
    }

    /** Removes owned tickets at logout; the recovery tag remains in the player save. */
    public void disconnect(ServerPlayer player) {
        Journey journey = journeys.remove(player.getUUID());
        if (journey != null) { release(player.getUUID(), journey); }
    }

    private void initializeLanding(ServerLevel level, String id, boolean invisible) {
        SystemCatalog catalog = SystemCatalog.get(server);
        if (catalog.landingInitialized(id)) { return; }
        for (int x = 4; x <= 12; x++) {
            for (int z = 4; z <= 12; z++) {
                BlockPos floor = new BlockPos(x, 79, z);
                if (level.isEmptyBlock(floor)) {
                    level.setBlockAndUpdate(floor, (invisible ? Blocks.BARRIER : Blocks.POLISHED_DEEPSLATE).defaultBlockState());
                }
            }
        }
        if (!invisible && level.isEmptyBlock(new BlockPos(8, 78, 8))) {
            level.setBlockAndUpdate(new BlockPos(8, 78, 8), Blocks.SEA_LANTERN.defaultBlockState());
        }
        catalog.markLandingInitialized(id);
    }

    private static Vec3 safeLanding(ServerLevel level) {
        for (int y = 80; y <= 96; y++) {
            for (int x = 8; x <= 12; x++) {
                for (int z = 8; z <= 12; z++) {
                    BlockPos feet = new BlockPos(x, y, z);
                    if (canStandAt(level, feet)) {
                        return new Vec3(x + 0.5, y, z + 0.5);
                    }
                }
            }
        }
        return null;
    }

    private static boolean canStandAt(ServerLevel level, BlockPos feet) {
        return level.isEmptyBlock(feet) && level.isEmptyBlock(feet.above())
                && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
    }

    private static void addTicket(ServerLevel level, UUID playerId) {
        level.getChunkSource().addRegionTicket(TRAVEL, new ChunkPos(0, 0), 2, playerId);
    }

    private void release(UUID playerId, Journey journey) {
        for (ResourceKey<Level> key : java.util.List.of(SystemWorlds.dimension(journey.target), SystemWorlds.TRANSIT)) {
            ServerLevel level = server.getLevel(key);
            if (level != null) { level.getChunkSource().removeRegionTicket(TRAVEL, new ChunkPos(0, 0), 2, playerId); }
        }
    }

    private static final class Journey {
        private final Point source;
        private final String target;
        private int age;
        private int remaining;
        private boolean inTransit;
        private Journey(Point source, String target) { this.source = source; this.target = target; }
    }

    private record Point(ResourceKey<Level> dimension, Vec3 position, float yaw, float pitch) {
        static Point of(ServerPlayer player) {
            return new Point(player.serverLevel().dimension(), player.position(), player.getYRot(), player.getXRot());
        }
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", dimension.location().toString());
            tag.putDouble("x", position.x); tag.putDouble("y", position.y); tag.putDouble("z", position.z);
            tag.putFloat("yaw", yaw); tag.putFloat("pitch", pitch);
            return tag;
        }
        static Point load(CompoundTag tag, MinecraftServer server) {
            ResourceLocation location = ResourceLocation.tryParse(tag.getString("dimension"));
            if (location != null) {
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
                Vec3 position = new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
                if (server.getLevel(key) != null && !key.equals(SystemWorlds.TRANSIT)
                        && Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
                        && Math.abs(position.x) < 29_999_984 && Math.abs(position.z) < 29_999_984
                        && position.y >= server.getLevel(key).getMinBuildHeight()
                        && position.y < server.getLevel(key).getMaxBuildHeight()
                        && Float.isFinite(tag.getFloat("yaw")) && Float.isFinite(tag.getFloat("pitch"))) {
                    return new Point(key, position, tag.getFloat("yaw"), tag.getFloat("pitch"));
                }
            }
            return new Point(Level.OVERWORLD, Vec3.atBottomCenterOf(server.overworld().getSharedSpawnPos()), 0, 0);
        }
        void teleport(ServerPlayer player, MinecraftServer server) {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                Point.load(new CompoundTag(), server).teleport(player, server);
                return;
            }
            player.teleportTo(level, position.x, position.y, position.z, Set.of(), yaw, pitch);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0;
        }
    }
}
