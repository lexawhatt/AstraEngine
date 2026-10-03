package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.SpaceBoundaryPreviewPayload;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.EarthBoundaryPlan;
import dev.lexawhatt.astraengine.surface.EarthBoundarySection;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One flight session's real destination preparation. Canonical chunks remain host-owned; this object owns
 * only bounded expiring tickets and an immutable observation. No movement or render callback waits for IO.
 */
public final class SpaceBoundaryPreparation implements AutoCloseable {
    private static final TicketType<UUID> TICKET = TicketType.create("astraengine_space_boundary", UUID::compareTo, 100);
    private final MinecraftServer server;
    private final UUID playerId;
    private final WeakReference<ServerPlayer> player;
    private final ResourceLocation sourceDimension;
    private final EarthBoundaryPlan plan;
    private final Set<Address> tickets = new HashSet<>();
    private final LongSupplier revisions;
    private long revision;
    private final int startedTick;
    private EarthBoundarySnapshot snapshot;
    private boolean acknowledged;
    private boolean closed;

    public SpaceBoundaryPreparation(ServerPlayer player, CubeStorageChart chart, SpaceVector feet, LongSupplier revisions) {
        if (player == null || chart == null || !chart.contains(feet) || revisions == null) {
            throw new IllegalArgumentException("Boundary preparation requires an owned player and canonical destination");
        }
        this.server = player.getServer(); this.playerId = player.getUUID(); this.player = new WeakReference<>(player);
        this.sourceDimension = player.serverLevel().dimension().location(); this.revisions = revisions;
        this.revision = revisions.getAsLong();
        if (revision < 1) { throw new IllegalArgumentException("Boundary revision must be positive"); }
        this.startedTick = server.getTickCount(); this.plan = EarthBoundaryPlan.arrival(chart, feet);
        requireOpen();
        boolean initialized = false;
        try {
            for (var section : plan.sections()) {
                var address = new Address(section.chart(), new ChunkPos(section.section().x(), section.section().z()));
                if (tickets.add(address)) {
                    var level = PlanetSurfaceWorlds.ensure(server, address.chart);
                    level.getChunkSource().addRegionTicket(TICKET, address.chunk, 0, playerId);
                }
            }
            initialized = true;
        } finally {
            if (!initialized) { close(); }
        }
    }

    public CubeStorageChart chart() { return plan.source(); }
    public SpaceVector feet() { return plan.anchorFeet(); }
    public long revision() { return revision; }
    public boolean timedOut() { requireOpen(); return server.getTickCount() - startedTick > 1200; }

    /** Refreshes only owned requests, then captures loaded data at most every five host ticks. */
    public void tick(ServerPlayer current) {
        requirePlayer(current);
        if (server.getTickCount() % 20 == 0) {
            for (var address : tickets) {
                var level = server.getLevel(PlanetSurfaceWorlds.dimension(address.chart));
                if (level != null) { level.getChunkSource().addRegionTicket(TICKET, address.chunk, 0, playerId); }
            }
        }
        if (snapshot != null || server.getTickCount() % 5 != 0) { return; }
        var sections = capture();
        if (sections.size() != plan.sections().size()) { return; }
        snapshot = new EarthBoundarySnapshot(revision, chart(), feet(), true, sections);
        PacketDistributor.sendToPlayer(current, new SpaceBoundaryPreviewPayload(sourceDimension, snapshot));
    }

    public void acknowledge(ServerPlayer current, long receivedRevision) {
        requirePlayer(current);
        if (snapshot != null && receivedRevision == revision) { acknowledged = true; }
    }

    /** Readiness includes an unchanged actual block/light/biome capture, not merely a client assertion. */
    public boolean ready(ServerPlayer current) {
        requirePlayer(current);
        if (!acknowledged || snapshot == null) { return false; }
        var latest = capture();
        if (latest.size() != snapshot.sections().size()) { invalidate(); return false; }
        for (int i = 0; i < latest.size(); i++) {
            if (!latest.get(i).sameContents(snapshot.sections().get(i))) { invalidate(); return false; }
        }
        return true;
    }

    private void invalidate() {
        long next = revisions.getAsLong();
        if (next <= revision) { throw new IllegalStateException("Boundary revisions must increase"); }
        revision = next; snapshot = null; acknowledged = false;
    }

    private ArrayList<EarthBoundarySection> capture() {
        var result = new ArrayList<EarthBoundarySection>();
        for (var section : plan.sections()) {
            var level = server.getLevel(PlanetSurfaceWorlds.dimension(section.chart()));
            EarthBoundaryCapture.capture(level, section.chart(), section.section()).ifPresent(result::add);
        }
        return result;
    }

    private void requireOpen() {
        if (closed || !server.isSameThread()) { throw new IllegalStateException("Boundary preparation is closed or on the wrong thread"); }
    }
    private void requirePlayer(ServerPlayer current) {
        requireOpen();
        if (current != player.get() || current.getServer() != server || !current.getUUID().equals(playerId)
                || !current.serverLevel().dimension().location().equals(sourceDimension)) {
            throw new IllegalArgumentException("Boundary preparation belongs to another player or source world");
        }
    }
    @Override public void close() {
        requireOpen();
        for (var address : tickets) {
            var level = server.getLevel(PlanetSurfaceWorlds.dimension(address.chart));
            if (level != null) { level.getChunkSource().removeRegionTicket(TICKET, address.chunk, 0, playerId); }
        }
        tickets.clear(); snapshot = null; acknowledged = false; closed = true;
    }
    private record Address(CubeStorageChart chart, ChunkPos chunk) { }
}
