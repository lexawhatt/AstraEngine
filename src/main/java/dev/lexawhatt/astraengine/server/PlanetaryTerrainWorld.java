package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.surface.PlanetaryTerrain;
import dev.lexawhatt.astraengine.worldgen.PlanetaryTerrainChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/** Server-thread guards for the independent permanent highlands prototype; no dynamic world allocation. */
public final class PlanetaryTerrainWorld {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse(PlanetaryTerrain.DIMENSION_ID));

    private PlanetaryTerrainWorld() {}

    /** Rejects changed host height or generator rather than silently mixing incompatible new chunks. */
    public static void validate(MinecraftServer server) {
        var level = server.getLevel(DIMENSION);
        if (level == null) { return; }
        if (!(level.getChunkSource().getGenerator() instanceof PlanetaryTerrainChunkGenerator)
                || level.getMinBuildHeight() != PlanetaryTerrain.MIN_Y || level.getHeight() != PlanetaryTerrain.HEIGHT) {
            throw new IllegalStateException("Highlands prototype has an incompatible saved generator or dimension height");
        }
    }

    /** Reasserts the fixed patch border after host Overworld border delegation, without touching saved blocks. */
    public static void maintainBorder(MinecraftServer server) {
        var level = server.getLevel(DIMENSION);
        if (level == null) { return; }
        var border = level.getWorldBorder();
        double size = PlanetaryTerrain.PATCH.halfWidth() * 2 - 16;
        if (border.getCenterX() != 0 || border.getCenterZ() != 0) { border.setCenter(0, 0); }
        if (border.getSize() != size || border.getLerpTarget() != size) { border.setSize(size); }
    }
}
