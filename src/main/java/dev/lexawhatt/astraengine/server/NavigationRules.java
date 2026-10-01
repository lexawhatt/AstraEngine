package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.cosmos.NavigationPolicy;
import dev.lexawhatt.astraengine.network.NavigationPolicyPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.network.PacketDistributor;

/** Host-persisted, server-wide navigation rules. No additional saved state or client authority. */
public final class NavigationRules {
    public static final GameRules.Key<GameRules.BooleanValue> FREE_NAVIGATION = GameRules.register(
            "astraFreeNavigation", GameRules.Category.PLAYER,
            GameRules.BooleanValue.create(false, (server, value) -> broadcast(server)));
    public static final GameRules.Key<GameRules.IntegerValue> TRAVEL_SECONDS = GameRules.register(
            "astraTravelSeconds", GameRules.Category.PLAYER,
            GameRules.IntegerValue.create(0, 0, NavigationPolicy.MAX_SECONDS, (server, value) -> broadcast(server)));

    private NavigationRules() { }

    /** Initializes host rule registration during serialized common setup, before any world is created. */
    public static void register() { }

    /** Owning server thread only. Invalid externally edited rule values use the nearest supported duration. */
    public static NavigationPolicy policy(MinecraftServer server) {
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("Navigation rules require the owning server thread");
        }
        return new NavigationPolicy(server.getGameRules().getBoolean(FREE_NAVIGATION),
                Math.clamp(server.getGameRules().getInt(TRAVEL_SECONDS), 0, NavigationPolicy.MAX_SECONDS));
    }

    /** Sends connection-owned policy at login; ordered main-thread payload delivery also handles rule changes. */
    public static void send(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new NavigationPolicyPayload(policy(player.getServer())));
    }

    private static void broadcast(MinecraftServer server) {
        if (server != null) {
            PacketDistributor.sendToAllPlayers(new NavigationPolicyPayload(policy(server)));
        }
    }
}
