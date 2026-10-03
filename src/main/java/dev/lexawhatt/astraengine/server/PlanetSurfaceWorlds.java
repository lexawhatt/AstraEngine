package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.mixin.ServerStorageAccessor;
import dev.lexawhatt.astraengine.cosmos.CosmosIds;
import dev.lexawhatt.astraengine.network.CustomSystemsPayload;
import dev.lexawhatt.astraengine.network.PlanetContextPayload;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.EarthClimate;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.worldgen.EarthBiomeSource;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.PlanetChunkGenerator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-thread adapter for permanent dynamically opened chart worlds. Minecraft owns chunk IO, saving,
 * collision, entities and shutdown. A chart receives its own persistent directory, never a recycled slot.
 * Constructing a level uses the host storage lease but requests no chunks; callers prepare destinations
 * asynchronously through normal host chunk tickets before moving players.
 */
public final class PlanetSurfaceWorlds {
    private static final ChunkProgressListener NO_SPAWN_PREGEN = new ChunkProgressListener() {
        @Override public void updateSpawnPos(ChunkPos center) { }
        @Override public void onStatusChange(ChunkPos position, ChunkStatus status) { }
        @Override public void start() { }
        @Override public void stop() { }
    };
    private PlanetSurfaceWorlds() { }

    public static ResourceKey<Level> dimension(CubeStorageChart chart) {
        if (chart == null) { throw new IllegalArgumentException("A storage chart is required"); }
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(chart.dimensionId()));
    }

    /** Startup restoration before logins; previously saved definitions are used without recomputation. */
    public static void restore(MinecraftServer server) {
        requireServer(server);
        for (var binding : PlanetSurfaceBindings.get(server).bindings()) { open(server, binding); }
    }

    /**
     * Resolves the permanent saved realization before the default for a new body. Server thread only, no
     * allocation. A descriptor conflict fails closed; visiting another chart never upgrades terrain materials.
     */
    public static Optional<dev.lexawhatt.astraengine.surface.SolidPlanetProfile> profile(MinecraftServer server,
            dev.lexawhatt.astraengine.cosmos.CosmosSystem system, dev.lexawhatt.astraengine.cosmos.CelestialBody body) {
        requireServer(server);
        var proposed = dev.lexawhatt.astraengine.surface.SolidPlanetProfile.create(system, body);
        if (proposed.isEmpty()) { return proposed; }
        var saved = PlanetSurfaceBindings.get(server).profile(system.id(), body.id());
        if (saved.isPresent()) { saved.get().frame(system, 0); return saved; }
        return proposed;
    }

    /** Canonical loaded generic chart only; a dimension name without its saved generator grants no binding. */
    public static Optional<PlanetChart> chart(ServerLevel level) {
        if (level == null) { throw new IllegalArgumentException("A server level is required"); }
        requireServer(level.getServer());
        if (level.getServer().getLevel(level.dimension()) != level
                || !(level.getChunkSource().getGenerator() instanceof PlanetChunkGenerator generator)) { return Optional.empty(); }
        var binding = PlanetSurfaceBindings.get(level.getServer()).binding(level.dimension().location().toString());
        return binding != null && binding.chart().equals(generator.chart()) ? Optional.of(generator.chart()) : Optional.empty();
    }

    /** Shared validated storage lookup, retaining Earth's historical binding contract. */
    public static Optional<CubeStorageChart> getCube(ServerLevel level) {
        var earth = EarthWorlds.chart(level);
        return earth.isPresent() ? earth.map(value -> value) : chart(level).map(value -> value);
    }

    /** Shared allocation entry point; unknown chart implementations fail without a fabricated world. */
    public static ServerLevel ensure(MinecraftServer server, CubeStorageChart chart) {
        if (chart instanceof EarthChart earth) { return ensureEarth(server, earth); }
        if (chart instanceof PlanetChart planet) { return ensure(server, planet); }
        throw new IllegalArgumentException("Unsupported planetary storage chart");
    }

    /** No-allocation lookup for an already opened matching permanent chart. */
    public static Optional<ServerLevel> level(MinecraftServer server, PlanetChart chart) {
        requireServer(server);
        var level = server.getLevel(dimension(chart));
        return level != null && chart(level).filter(chart::equals).isPresent() ? Optional.of(level) : Optional.empty();
    }

    /** Opens or reuses one permanent chart. Capacity/conflict failures happen before any player handoff. */
    public static ServerLevel ensure(MinecraftServer server, PlanetChart chart) {
        requireServer(server);
        var bindings = PlanetSurfaceBindings.get(server);
        var previous = bindings.binding(chart.dimensionId());
        if (previous != null) {
            if (!previous.chart().equals(chart)) { throw new IllegalArgumentException("Saved planetary chart definition changed"); }
            return open(server, previous);
        }
        var biome = server.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(
                chart.profile().kind() == dev.lexawhatt.astraengine.cosmos.CelestialBody.Kind.OCEAN ? Biomes.PLAINS : Biomes.THE_VOID);
        var binding = bindings.bind(chart, new PlanetChunkGenerator(chart, new FixedBiomeSource(biome)));
        var level = open(server, binding);
        sendAll(server);
        return level;
    }

    /** Adds only new upper-air Earth bands; historical ground storage must already exist in the saved preset. */
    public static ServerLevel ensureEarth(MinecraftServer server, EarthChart chart) {
        requireServer(server);
        if (!EarthWorlds.active(server) || chart.terrainVersion() != EarthWorlds.terrainVersion(server)) {
            throw new IllegalArgumentException("Upper Earth storage requires the active saved Earth profile");
        }
        var existing = server.getLevel(dimension(chart));
        if (existing != null) {
            if (!EarthWorlds.chart(existing).filter(chart::equals).isPresent()) {
                throw new IllegalStateException("Loaded Earth dimension differs from its permanent chart");
            }
            return existing;
        }
        if (chart.band() <= 3) { throw new IllegalStateException("Historical Earth storage is missing from the save"); }
        var bindings = PlanetSurfaceBindings.get(server);
        var previous = bindings.binding(chart.dimensionId());
        if (previous != null) { return open(server, previous); }
        var ground = (EarthChunkGenerator) server.overworld().getChunkSource().getGenerator();
        var source = (EarthBiomeSource) ground.getBiomeSource();
        var palette = new LinkedHashMap<String, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>();
        for (var climate : EarthClimate.values()) { palette.put(climate.name().toLowerCase(Locale.ROOT), source.biome(climate)); }
        if (chart.terrainVersion() >= 3) { palette.put("river", source.riverBiome()); }
        var generator = new EarthChunkGenerator(chart, new EarthBiomeSource(chart.face(), chart.terrainVersion(), palette),
                ground.caves().version());
        return open(server, bindings.bind(chart, generator));
    }

    /** Sends only authorized context; private custom descriptors precede any chart referencing their identity. */
    public static void send(ServerPlayer player) {
        var bindings = PlanetSurfaceBindings.get(player.server);
        var catalog = ExplorationCatalog.get(player.server);
        var visible = new LinkedHashSet<>(catalog.player(player.getUUID()).discoveredSystems());
        chart(player.serverLevel()).ifPresent(chart -> visible.add(chart.profile().systemId()));
        PacketDistributor.sendToPlayer(player, new CustomSystemsPayload(visible.stream().filter(CosmosIds::isCustom)
                .map(catalog::system).toList()));
        PacketDistributor.sendToPlayer(player, new PlanetContextPayload(bindings.nextContextRevision(), bindings.bindings().stream()
                .map(PlanetSurfaceBindings.Binding::chart).filter(PlanetChart.class::isInstance).map(PlanetChart.class::cast)
                .filter(chart -> visible.contains(chart.profile().systemId())).toList()));
    }

    private static void sendAll(MinecraftServer server) {
        for (var player : server.getPlayerList().getPlayers()) { send(player); }
    }

    private static ServerLevel open(MinecraftServer server, PlanetSurfaceBindings.Binding binding) {
        var key = dimension(binding.chart());
        var existing = server.getLevel(key);
        if (existing != null) {
            if (existing.getChunkSource().getGenerator() != binding.generator()) {
                throw new IllegalStateException("Permanent planet dimension is already occupied by a different generator: " + key.location());
            }
            return existing;
        }
        var type = server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
                .getHolderOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, ResourceLocation.parse(binding.chart() instanceof PlanetChart ? "astraengine:planet_surface" : "astraengine:earth_surface")));
        var data = new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData());
        var level = new ServerLevel(server, Util.backgroundExecutor(), ((ServerStorageAccessor) server).astraengine$storageSource(),
                data, key, new LevelStem(type, binding.generator()), NO_SPAWN_PREGEN, false,
                BiomeManager.obfuscateSeed(server.getWorldData().worldGenOptions().seed()), List.of(), false,
                server.overworld().getRandomSequences());
        level.getWorldBorder().setCenter(0, 0);
        level.getWorldBorder().setSize(binding.chart().radiusMeters() * 2
                + dev.lexawhatt.astraengine.surface.CubeChartRebase.MAX_EXTENSION_METERS * 2);
        server.forgeGetWorldMap().put(key, level);
        server.markWorldsDirty();
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        return level;
    }

    private static void requireServer(MinecraftServer server) {
        if (server == null || !server.isSameThread()) { throw new IllegalStateException("Planet world allocation requires the server thread"); }
    }
}
