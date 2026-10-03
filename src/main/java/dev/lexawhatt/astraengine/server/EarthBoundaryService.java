package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.EarthBoundaryPayload;
import dev.lexawhatt.astraengine.surface.EarthBoundaryPlan;
import dev.lexawhatt.astraengine.surface.EarthBoundarySection;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.api.AstraGeography;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/** One logical server's bounded seam prefetch and observation owner. Never generates synchronously or edits terrain. */
public final class EarthBoundaryService implements AutoCloseable {
    private static final TicketType<UUID> TICKET = TicketType.create("astraengine_boundary", UUID::compareTo, 100);
    private final MinecraftServer server;
    private final Map<UUID, Observed> observations = new HashMap<>();
    private long revision;
    private boolean closed;

    /** Creates a transient owner on the server thread. Registers no handlers and retains no players or chunks. */
    public EarthBoundaryService(MinecraftServer server) {
        if (server == null || !server.isSameThread()) { throw new IllegalStateException("Boundary service requires its server thread"); }
        this.server = server;
    }

    /** Refreshes expiring FULL-status tickets and copies only already available sections every five host ticks. */
    public void tick() {
        requireOpen();
        if (server.getTickCount() % 5 != 0) { return; }
        var online = new HashSet<UUID>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID()); observe(player);
        }
        for (UUID id : List.copyOf(observations.keySet())) {
            if (!online.contains(id)) { forget(id); }
        }
    }

    /** Latest actually captured immutable view; server thread only. Missing means no prepared neighborhood. */
    public Optional<EarthBoundarySnapshot> snapshot(ServerPlayer player) {
        requirePlayer(player);
        Observed current = observations.get(player.getUUID());
        return current != null && current.entity.get() == player
                ? Optional.ofNullable(current.snapshot) : Optional.empty();
    }

    /** A short network-latency window; callers must still recompute current ownership, ray and permissions. */
    public boolean acceptsInteraction(ServerPlayer player, long requested) {
        requirePlayer(player);
        var current = observations.get(player.getUUID());
        if (current == null || current.entity.get() != player || current.snapshot == null
                || !current.snapshot.complete()) { return false; }
        if (current.snapshot.revision() == requested) { return true; }
        int now = server.getTickCount();
        return current.recent.stream().anyMatch(value -> value.revision == requested && now - value.tick <= 20);
    }

    /** Records a current own-player geometry acknowledgement; never accepts a client destination or revision leap. */
    public void ready(ServerPlayer player, long acknowledged) {
        requirePlayer(player);
        var current = observations.get(player.getUUID());
        if (current != null && current.entity.get() == player && current.snapshot != null
                && current.snapshot.revision() == acknowledged && current.snapshot.complete()) {
            current.readyRevision = acknowledged;
        }
    }

    /** Both real sections and this connection's matching prepared view exist. */
    public boolean ready(ServerPlayer player) {
        requirePlayer(player);
        var current = observations.get(player.getUUID());
        return current != null && current.entity.get() == player && current.snapshot != null
                && current.snapshot.complete() && current.readyRevision == current.snapshot.revision();
    }

    /** Ends one entity instance's observations/tickets on logout or respawn. A stale entity cannot retire its replacement. */
    public void forget(ServerPlayer player) {
        requirePlayer(player);
        Observed current = observations.get(player.getUUID());
        if (current != null && current.entity.get() == player) { forget(player.getUUID()); }
    }

    private void observe(ServerPlayer player) {
        var source = PlanetSurfaceWorlds.getCube(player.serverLevel()).orElse(null);
        var feet = new SpaceVector(player.getX(), player.getY(), player.getZ());
        EarthBoundaryPlan plan = source != null && source.contains(feet) && player.isAlive() && !player.isRemoved()
                ? EarthBoundaryPlan.around(source, feet).orElse(null) : null;
        Observed current = observations.get(player.getUUID());
        if (current != null && (plan == null || current.entity.get() != player || !current.source.equals(source))) {
            clear(player, current); forget(player.getUUID()); current = null;
        }
        if (plan == null) { return; }
        if (current == null) {
            current = new Observed(player, source); observations.put(player.getUUID(), current);
        }
        var requested = new HashSet<ChunkAddress>();
        for (var address : plan.sections()) {
            requested.add(new ChunkAddress(address.chart(), new ChunkPos(address.section().x(), address.section().z())));
        }
        for (ChunkAddress previous : current.tickets) {
            if (!requested.contains(previous)) { release(player.getUUID(), previous); }
        }
        var acquired = new HashSet<ChunkAddress>();
        var refused = new HashSet<CubeStorageChart>();
        for (ChunkAddress address : requested) {
            if (refused.contains(address.chart)) { continue; }
            var level = server.getLevel(EarthWorlds.dimension(address.chart));
            // The saved preset was validated at startup. No missing world is replaced here.
            if (level == null) {
                try { level = PlanetSurfaceWorlds.ensure(server, address.chart); }
                catch (PlanetSurfaceBindings.CapacityExceededException exhausted) {
                    // Keep this neighborhood incomplete: traversal retains its last valid pose and can retry.
                    // Identity conflicts and damaged historical storage remain errors, not capacity refusals.
                    refused.add(address.chart);
                    continue;
                }
            }
            level.getChunkSource().addRegionTicket(TICKET, address.chunk, 0, player.getUUID());
            acquired.add(address);
        }
        current.tickets = Set.copyOf(acquired);
        var sections = new ArrayList<EarthBoundarySection>();
        boolean complete = refused.isEmpty();
        for (var address : plan.sections()) {
            if (refused.contains(address.chart())) { continue; }
            var level = server.getLevel(EarthWorlds.dimension(address.chart()));
            var section = EarthBoundaryCapture.capture(level, address.chart(), address.section());
            if (section.isPresent()) { sections.add(section.orElseThrow()); } else { complete = false; }
        }
        EarthBoundarySnapshot previous = current.snapshot;
        if (previous != null && previous.complete() == complete && previous.visibleFrom(source, feet)
                && sameSections(previous.sections(), sections)) { return; }
        if (previous != null && previous.complete()) {
            current.recent.addLast(new RecentRevision(previous.revision(), server.getTickCount()));
            while (current.recent.size() > 4) { current.recent.removeFirst(); }
        }
        current.snapshot = new EarthBoundarySnapshot(Math.incrementExact(revision), source, feet, complete, sections);
        revision = current.snapshot.revision();
        PacketDistributor.sendToPlayer(player, new EarthBoundaryPayload(current.snapshot));
    }

    private static boolean sameSections(List<EarthBoundarySection> first, List<EarthBoundarySection> second) {
        if (first.size() != second.size()) { return false; }
        for (int i = 0; i < first.size(); i++) { if (!first.get(i).sameContents(second.get(i))) { return false; } }
        return true;
    }

    private void clear(ServerPlayer player, Observed current) {
        if (current.snapshot == null) { return; }
        revision = Math.incrementExact(revision);
        PacketDistributor.sendToPlayer(player, new EarthBoundaryPayload(new EarthBoundarySnapshot(revision, current.source,
                current.snapshot.anchorFeet(), false, List.of())));
    }

    private void forget(UUID id) {
        Observed previous = observations.remove(id);
        if (previous != null) { for (ChunkAddress address : previous.tickets) { release(id, address); } }
    }

    private void release(UUID player, ChunkAddress address) {
        var level = server.getLevel(EarthWorlds.dimension(address.chart));
        if (level != null) { level.getChunkSource().removeRegionTicket(TICKET, address.chunk, 0, player); }
    }

    /** Releases only this owner's tickets on shutdown; canonical chunk storage is never deleted. */
    @Override public void close() {
        if (!server.isSameThread()) { throw new IllegalStateException("Boundary shutdown requires its server thread"); }
        for (UUID id : List.copyOf(observations.keySet())) { forget(id); }
        closed = true;
    }

    private void requireOpen() {
        if (!server.isSameThread() || closed) { throw new IllegalStateException("Boundary service is closed or on the wrong thread"); }
    }

    private void requirePlayer(ServerPlayer player) {
        requireOpen();
        if (player == null || player.getServer() != server) { throw new IllegalArgumentException("Boundary player belongs to another server"); }
    }

    private record ChunkAddress(CubeStorageChart chart, ChunkPos chunk) {}
    private record RecentRevision(long revision, int tick) {}

    private static final class Observed {
        private final WeakReference<ServerPlayer> entity;
        private final CubeStorageChart source;
        private Set<ChunkAddress> tickets = Set.of();
        private EarthBoundarySnapshot snapshot;
        private final ArrayDeque<RecentRevision> recent = new ArrayDeque<>();
        private long readyRevision;
        private Observed(ServerPlayer player, CubeStorageChart source) { this.entity = new WeakReference<>(player); this.source = source; }
    }
}
