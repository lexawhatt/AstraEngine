package dev.lexawhatt.astraengine.server.orbit;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.OrbitalSummaryPayload;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import dev.lexawhatt.astraengine.server.SkyState;
import dev.lexawhatt.astraengine.surface.EarthEphemeris;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalPage;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalPatch;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalSurface;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Logical-server owner of derived orbital summaries. Captures at most two loaded chunks per tick, never generates
 * terrain, keeps page I/O asynchronous, and streams bounded interest transactions independently for each player.
 * Unloaded dirty chunks remain marked in SavedData and revalidate on load; stale summaries are not transmitted.
 */
public final class OrbitalSummaryService implements AutoCloseable {
    private static final int MAX_VIEW_PATCHES = 4096;
    private static final int FINE_PAGES = 12;
    private final MinecraftServer server;
    private final OrbitalSummaryIndex index;
    private final OrbitalPageStore store;
    private final Map<UUID, Delivery> deliveries = new HashMap<>();
    private int dirtyCursor;
    private long epoch;
    private boolean closed;

    public OrbitalSummaryService(MinecraftServer server) {
        if (server == null || !server.isSameThread()) { throw new IllegalStateException("Orbital service requires its server thread"); }
        this.server = server; index = OrbitalSummaryIndex.get(server);
        store = new OrbitalPageStore(server.getWorldPath(LevelResource.ROOT).resolve("data/astraengine_orbital_pages"));
    }

    /** Observes actual successful host mutations, including /fill, explosions, light toggles and removal. */
    public void changed(OrbitalBlockChangedEvent event) {
        if (closed || event.level().getServer() != server) { return; }
        surface(event.level()).ifPresent(value -> mark(value, event.chunk()));
    }

    /** Revalidates already observed pages when real chunks load; initial loaded terrain also discovers existing builds. */
    public void loaded(ChunkEvent.Load event) {
        if (closed || !(event.getLevel() instanceof ServerLevel level) || level.getServer() != server
                || !server.isSameThread() || !(event.getChunk() instanceof LevelChunk)) { return; }
        surface(level).ifPresent(value -> mark(value, event.getChunk().getPos()));
    }

    private void mark(OrbitalSurface surface, ChunkPos chunk) {
        var key = new OrbitalPage.Key(surface, Math.floorDiv(chunk.x, 16), Math.floorDiv(chunk.z, 16));
        index.mark(key, chunk.x, chunk.z);
    }

    /** Bounded CPU work; no join/read/load/generation occurs on ordinary ticks. */
    public void tick() {
        requireOpen(); store.tick(); capture();
        var online = new HashSet<UUID>();
        for (var player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            Delivery delivery = deliveries.get(player.getUUID());
            if (delivery == null) {
                delivery = new Delivery(); deliveries.put(player.getUUID(), delivery); seedLoadedNeighborhood(player);
            }
            if (delivery.next >= delivery.pending.size() && server.getTickCount() % 40 == 0) { prepare(player, delivery); }
            send(player, delivery);
        }
        deliveries.keySet().removeIf(id -> !online.contains(id));
    }

    private void seedLoadedNeighborhood(ServerPlayer player) {
        var surface = surface(player.serverLevel()).orElse(null);
        if (surface == null) { return; }
        int centerX = player.chunkPosition().x, centerZ = player.chunkPosition().z;
        for (int z = centerZ - 2; z <= centerZ + 2; z++) {
            for (int x = centerX - 2; x <= centerX + 2; x++) {
                if (player.serverLevel().getChunkSource().getChunkNow(x, z) != null) {
                    mark(surface, new ChunkPos(x, z));
                }
            }
        }
    }

    private void capture() {
        int attempts = Math.min(32, index.dirtyPageCount()), captured = 0, checked = 0;
        for (int attempt = 0; attempt < attempts && captured < 2 && checked < 512; attempt++) {
            var key = index.dirtyKey(dirtyCursor++);
            if (key == null) { return; }
            var dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(key.surface().dimensionId()));
            ServerLevel level = server.getLevel(dimension);
            if (level == null || !surface(level).map(key.surface()::equals).orElse(false)) { continue; }
            var dirty = index.dirty(key);
            for (int slot = dirty.nextSetBit(0); slot >= 0 && captured < 2 && checked < 512; slot = dirty.nextSetBit(slot + 1)) {
                checked++;
                int x = key.x() * 16 + slot % 16, z = key.z() * 16 + slot / 16;
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) { continue; }
                var page = store.get(key).orElse(null); if (page == null) { break; }
                long revision = index.nextRevision();
                var patch = OrbitalCapture.capture(level, key.surface(), chunk, revision);
                var previous = page.chunks().get(slot);
                if (!patch.sameContent(previous)) {
                    var updated = page.with(patch); store.put(updated); index.update(updated, revision);
                }
                index.captured(key, slot); captured++;
            }
        }
    }

    private void prepare(ServerPlayer player, Delivery delivery) {
        Interest interest = interest(player);
        if (interest == null) { delivery.candidates.clear(); }
        else {
            if (!delivery.systemId.equals(interest.systemId)) {
                delivery.candidates.clear(); delivery.systemId = interest.systemId; delivery.directoryCursor = 0;
            }
            for (var patch : index.window(delivery.directoryCursor, 1024)) {
                if (patch.surface().systemId().equals(interest.systemId)) {
                    delivery.candidates.add(new OrbitalPage.Key(patch.surface(), patch.x(), patch.z()));
                }
            }
            if (index.pageCount() > 0) { delivery.directoryCursor = (delivery.directoryCursor + 1024) % index.pageCount(); }
        }
        var ordered = delivery.candidates.stream().map(index::page).filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingDouble((OrbitalPatch patch) -> distance(patch, interest))
                        .thenComparing(patch -> patch.surface().dimensionId()).thenComparingInt(OrbitalPatch::x)
                        .thenComparingInt(OrbitalPatch::z)).limit(1024).toList();
        delivery.candidates.clear();
        ordered.forEach(patch -> delivery.candidates.add(new OrbitalPage.Key(patch.surface(), patch.x(), patch.z())));
        var next = new ArrayList<OrbitalPatch>();
        for (int i = 0; i < ordered.size(); i++) {
            var coarse = ordered.get(i);
            var key = new OrbitalPage.Key(coarse.surface(), coarse.x(), coarse.z());
            var dirty = index.dirty(key);
            // Fine pages are requested only in the closest interest region, never across the whole globe.
            var fine = i < FINE_PAGES ? store.get(key).orElse(null) : null;
            if (fine != null) {
                fine.chunks().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .filter(entry -> !dirty.get(entry.getKey())).map(Map.Entry::getValue).forEach(next::add);
                next.add(dirty.isEmpty() ? coarse : fine.coarse(coarse.revision(), dirty));
            } else if (dirty.isEmpty()) {
                next.add(coarse);
            }
            if (next.size() >= MAX_VIEW_PATCHES) { break; }
        }
        if (next.size() > MAX_VIEW_PATCHES) { next.subList(MAX_VIEW_PATCHES, next.size()).clear(); }
        List<OrbitalPatch> snapshot = List.copyOf(next);
        if (snapshot.equals(delivery.published) && delivery.sent) { return; }
        delivery.pending = snapshot; delivery.next = 0; delivery.epoch = ++epoch;
        delivery.published = snapshot; delivery.sent = false;
    }

    private void send(ServerPlayer player, Delivery delivery) {
        if (delivery.epoch == 0 || delivery.sent) { return; }
        int end = Math.min(delivery.pending.size(), delivery.next + OrbitalSummaryPayload.MAX_PATCHES);
        boolean complete = end == delivery.pending.size();
        PacketDistributor.sendToPlayer(player, new OrbitalSummaryPayload(delivery.epoch, delivery.next == 0,
                complete, delivery.pending.subList(delivery.next, end)));
        delivery.next = end; delivery.sent = complete;
    }

    private Interest interest(ServerPlayer player) {
        var ground = surface(player.serverLevel()).orElse(null);
        if (ground != null) {
            return new Interest(ground.systemId(), Map.of(ground.bodyId(), ground.normal(player.getX(), player.getZ())
                    .multiply(ground.radiusMeters() + player.getY() + ground.altitudeOriginMeters())));
        }
        if (!RocketService.isFlightWorld(player)) { return null; }
        var catalog = ExplorationCatalog.get(server); var pilot = catalog.player(player.getUUID());
        var system = catalog.system(pilot.systemId());
        Map<String, SpaceVector> bodyObservers = new HashMap<>();
        double seconds = catalog.clockTicks() / 20.0;
        if (EarthWorlds.active(server) && system.id().equals("sol")) {
            var calendar = EarthEphemeris.sample(SkyState.get(server).profile(), server.overworld().getDayTime(), 0);
            bodyObservers.put("earth", calendar.frame().toBodyPoint(pilot.position()));
            seconds = calendar.orbitalSeconds();
        }
        for (var body : system.bodies()) {
            if (bodyObservers.containsKey(body.id())) { continue; }
            var profile = PlanetSurfaceWorlds.profile(server, system, body).orElse(null);
            if (profile != null) { bodyObservers.put(body.id(), profile.frame(system, seconds).toBodyPoint(pilot.position())); }
        }
        return new Interest(system.id(), Map.copyOf(bodyObservers));
    }

    private static double distance(OrbitalPatch patch, Interest interest) {
        var observer = interest.observers.get(patch.surface().bodyId());
        if (observer == null) { return Double.MAX_VALUE; }
        double size = patch.sizeMeters();
        return patch.surface().normal((patch.x() + .5) * size, (patch.z() + .5) * size)
                .multiply(patch.surface().radiusMeters()).distance(observer);
    }

    /** Resolves canonical owned storage; other generators and ordinary Overworlds are excluded. */
    public static Optional<OrbitalSurface> surface(ServerLevel level) {
        var earth = EarthWorlds.chart(level).map(chart -> new OrbitalSurface("sol", "earth", chart.dimensionId(),
                chart.face(), chart.topology().radiusMeters(), chart.altitudeOriginMeters()));
        if (earth.isPresent()) { return earth; }
        return PlanetSurfaceWorlds.chart(level).map(chart -> new OrbitalSurface(chart.profile().systemId(),
                chart.profile().bodyId(), chart.dimensionId(), chart.face(), chart.radiusMeters(), chart.altitudeOriginMeters()));
    }

    /** For diagnostics and verification; returned immutable page metadata cannot edit chunks. */
    public OrbitalSummaryIndex index() { requireOpen(); return index; }
    /** Removes only connection delivery state. Stored observations survive departure and logout. */
    public void forget(ServerPlayer player) { requireOpen(); deliveries.remove(player.getUUID()); }
    /** Drains accepted page writes at shutdown only; no canonical world files are changed by this cache. */
    @Override public void close() {
        if (closed) { return; }
        requireOpen(); store.close();
        for (var key : store.unflushedPages()) {
            for (int slot = 0; slot < 256; slot++) {
                index.mark(key, key.x() * 16 + slot % 16, key.z() * 16 + slot / 16);
            }
        }
        deliveries.clear(); closed = true;
    }
    private void requireOpen() {
        if (closed || !server.isSameThread()) { throw new IllegalStateException("Orbital service is closed or on another thread"); }
    }
    private record Interest(String systemId, Map<String, SpaceVector> observers) { }
    private static final class Delivery {
        private List<OrbitalPatch> pending = List.of();
        private List<OrbitalPatch> published = List.of();
        private long epoch;
        private int next;
        private boolean sent;
        private final java.util.Set<OrbitalPage.Key> candidates = new java.util.HashSet<>();
        private String systemId = "";
        private int directoryCursor;
    }
}
