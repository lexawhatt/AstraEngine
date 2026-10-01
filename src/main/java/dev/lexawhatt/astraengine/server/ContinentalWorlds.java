package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.surface.ContinentalRegion;
import dev.lexawhatt.astraengine.worldgen.ContinentalTerrainChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/** Server-thread guards for permanent continental inspection worlds; no world allocation or chunk replacement. */
public final class ContinentalWorlds {
    private ContinentalWorlds() {}

    /** Returns a stable registry key for a non-null region; this lookup retains no loaded level. */
    public static ResourceKey<Level> dimension(ContinentalRegion region) {
        if (region == null) { throw new IllegalArgumentException("A continental region is required"); }
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(region.dimensionId()));
    }

    /**
     * Rejects incompatible generator bindings or storage heights at server startup. Missing data-defined worlds
     * are left absent. Must run on the owning server thread before travel or generation requests are accepted.
     */
    public static void validate(MinecraftServer server) {
        requireServerThread(server);
        for (ContinentalRegion region : ContinentalRegion.values()) {
            var level = server.getLevel(dimension(region));
            if (level == null) { continue; }
            if (!(level.getChunkSource().getGenerator() instanceof ContinentalTerrainChunkGenerator generator)
                    || generator.region() != region || level.getMinBuildHeight() != ContinentalRegion.MIN_Y
                    || level.getHeight() != ContinentalRegion.HEIGHT
                    || level.dimensionType().logicalHeight() != ContinentalRegion.HEIGHT) {
                throw new IllegalStateException("Continental world has an incompatible saved generator or height: "
                        + region.dimensionId());
            }
        }
    }

    /** Reasserts each fixed horizontal border after Overworld delegation, without touching any saved blocks. */
    public static void maintainBorders(MinecraftServer server) {
        requireServerThread(server);
        for (ContinentalRegion region : ContinentalRegion.values()) {
            var level = server.getLevel(dimension(region));
            if (level == null) { continue; }
            var border = level.getWorldBorder();
            double size = region.patch().halfWidth() * 2 - 16;
            if (border.getCenterX() != 0 || border.getCenterZ() != 0) { border.setCenter(0, 0); }
            if (border.getSize() != size || border.getLerpTarget() != size) { border.setSize(size); }
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (server == null) { throw new IllegalArgumentException("Continental worlds require a server"); }
        if (!server.isSameThread()) { throw new IllegalStateException("Continental worlds require the owning server thread"); }
    }
}
