package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.api.AstraGeography;
import dev.lexawhatt.astraengine.api.SurfaceFrameChangedEvent;
import dev.lexawhatt.astraengine.api.SurfaceFrameSnapshot;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

/**
 * One logical server's transient frame observations. Minecraft alone saves position and block data.
 * Stores immutable values and weak entity-instance identity, never owning player/level references.
 * Host respawn can reuse a numeric entity ID. All methods require the owning thread.
 */
public final class SurfaceFrameTracker {
    private final MinecraftServer server;
    private final Map<UUID, Tracked> tracked = new HashMap<>();
    private boolean closed;

    /** Creates a session owner without reading saves, sampling players or registering event listeners. */
    public SurfaceFrameTracker(MinecraftServer server) {
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("Surface frame tracking requires its server thread");
        }
        this.server = server;
    }

    /** Observes connected players after host movement; missed disconnect callbacks cannot retain old contexts. */
    public void tick() {
        requireOpen();
        var online = new HashSet<UUID>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            observe(player);
        }
        tracked.keySet().retainAll(online);
    }

    /**
     * Lifecycle adapter for one host-owned player. Updates previous pose on every observation, but emits only
     * initial context, tile changes or loss. Observing again without movement does not replay an event.
     * Caller must not treat an observed tile difference as proof of a traversed route.
     */
    public void observe(ServerPlayer player) {
        requirePlayer(player);
        Tracked before = tracked.get(player.getUUID());
        if (before != null && before.entity().get() != player) {
            tracked.remove(player.getUUID());
            NeoForge.EVENT_BUS.post(new SurfaceFrameChangedEvent(player, Optional.of(before.snapshot()), Optional.empty()));
            before = null;
        }
        var current = AstraGeography.snapshot(player);
        if (current.isEmpty()) { forget(player); return; }
        SurfaceFrameSnapshot snapshot = current.orElseThrow();
        tracked.put(player.getUUID(), new Tracked(new WeakReference<>(player), snapshot));
        if (before == null || !sameFrame(before.snapshot(), snapshot)) {
            NeoForge.EVENT_BUS.post(new SurfaceFrameChangedEvent(player,
                    before == null ? Optional.empty() : Optional.of(before.snapshot()), current));
        }
    }

    /** Ends one observation context on leave/respawn/logout. Does not move, save or invalidate the host player. */
    public void forget(ServerPlayer player) {
        requirePlayer(player);
        Tracked previous = tracked.get(player.getUUID());
        // A delayed logout/removal of an old entity must not erase its already-observed respawn replacement.
        if (previous != null && previous.entity().get() == player) {
            tracked.remove(player.getUUID());
            NeoForge.EVENT_BUS.post(new SurfaceFrameChangedEvent(player, Optional.of(previous.snapshot()), Optional.empty()));
        }
    }

    /** Releases all transient contexts at shutdown; no movement is replayed and no save data is removed. */
    public void close() {
        if (!server.isSameThread()) { throw new IllegalStateException("Surface frame tracking requires its server thread"); }
        tracked.clear();
        closed = true;
    }

    private static boolean sameFrame(SurfaceFrameSnapshot first, SurfaceFrameSnapshot second) {
        return first.geographyId().equals(second.geographyId()) && first.dimension().equals(second.dimension())
                && first.topology().equals(second.topology()) && first.tile().equals(second.tile());
    }

    private void requireOpen() {
        if (!server.isSameThread() || closed) {
            throw new IllegalStateException("Surface frame tracker is closed or accessed outside its server thread");
        }
    }

    private void requirePlayer(ServerPlayer player) {
        requireOpen();
        if (player == null || player.getServer() != server) {
            throw new IllegalArgumentException("Player belongs to a different frame tracking owner");
        }
    }

    private record Tracked(WeakReference<ServerPlayer> entity, SurfaceFrameSnapshot snapshot) {}
}
