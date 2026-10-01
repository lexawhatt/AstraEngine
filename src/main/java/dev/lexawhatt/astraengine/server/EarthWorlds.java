package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Read-only logical-server bindings for the saved Earth preset; no dimension creation or runtime recycling. */
public final class EarthWorlds {
    private EarthWorlds() {}

    /** Requires the server thread. Ordinary and legacy Overworlds are never inferred to be continental Earth. */
    public static boolean active(MinecraftServer server) {
        requireServer(server);
        return server.overworld().getChunkSource().getGenerator() instanceof EarthChunkGenerator generator
                && generator.chart().face() == CubeFace.POSITIVE_X && generator.chart().band() == 0;
    }

    /** Saved terrain algorithm of the active Earth; zero means unbound. Requires the owning server thread. */
    public static int terrainVersion(MinecraftServer server) {
        return active(server) ? ((EarthChunkGenerator) server.overworld().getChunkSource().getGenerator()).terrain().version() : 0;
    }

    /** Exact permanent dimension key; null fails. This lookup performs no loading or mutation. */
    public static ResourceKey<Level> dimension(EarthChart chart) {
        if (chart == null) { throw new IllegalArgumentException("An Earth chart is required"); }
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(chart.dimensionId()));
    }

    /** Loaded canonical chart only; absent/foreign instances or an unbound Overworld return absence. */
    public static Optional<EarthChart> chart(ServerLevel level) {
        if (level == null) { throw new IllegalArgumentException("A server level is required"); }
        var server = level.getServer();
        if (!active(server) || server.getLevel(level.dimension()) != level) { return Optional.empty(); }
        if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator
                && generator.terrain().version() == terrainVersion(server)
                && dimension(generator.chart()).equals(level.dimension())) { return Optional.of(generator.chart()); }
        return Optional.empty();
    }

    /**
     * Startup integrity check against host-saved generator identities. A partial/changed active preset fails
     * explicitly; it never creates missing storage or rebinds old worlds. No separate mutable manifest is needed.
     */
    public static void validate(MinecraftServer server) {
        requireServer(server);
        if (server.overworld().getChunkSource().getGenerator() instanceof EarthChunkGenerator && !active(server)) {
            throw new IllegalStateException("Saved Earth Overworld is not positive-X altitude band zero");
        }
        if (!active(server)) { return; }
        for (var expected : EarthChart.all(terrainVersion(server))) {
            var level = server.getLevel(dimension(expected));
            if (level == null || !chart(level).map(expected::equals).orElse(false)
                    || level.getMinBuildHeight() != EarthChart.MIN_Y || level.getHeight() != EarthChart.HEIGHT) {
                throw new IllegalStateException("Saved Earth preset has missing or changed storage: " + expected.dimensionId());
            }
        }
    }

    private static void requireServer(MinecraftServer server) {
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("Earth bindings require the owning server thread");
        }
    }
}
