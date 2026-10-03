package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Fresh-world spawn selection in real band-zero lowland, before host spawn chunks are prepared. */
public final class EarthSpawn {
    private EarthSpawn() { }

    /** The host fires this only while initializing a new world; saved spawns and existing players are untouched. */
    public static void create(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(Level.OVERWORLD)
                || !(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator)
                || generator.chart().band() != 0) { return; }
        event.getSettings().setSpawn(select(generator), 0);
        if (level.getServer().getWorldData().worldGenOptions().generateBonusChest()) {
            level.registryAccess().registry(Registries.CONFIGURED_FEATURE)
                    .flatMap(registry -> registry.getHolder(MiscOverworldFeatures.BONUS_CHEST))
                    .ifPresent(feature -> feature.value().place(level, generator, level.random, event.getSettings().getSpawnPos()));
        }
        event.setCanceled(true);
    }

    /** Bounded deterministic numeric search; requests no chunks and never clamps a mountain into a storage ceiling. */
    public static BlockPos select(EarthChunkGenerator generator) {
        if (generator == null || generator.chart().band() != 0) {
            throw new IllegalArgumentException("Initial Earth spawn requires the ground storage band");
        }
        var chart = generator.chart();
        for (int index = 0; index < 4096; index++) {
            double radius = index == 0 ? 0 : Math.min(EarthChart.RADIUS_METERS * .95,
                    2048 * Math.pow(1.018, Math.sqrt(index) * 8));
            double angle = index * Math.PI * (3 - Math.sqrt(5));
            int x = (int) Math.floor(Math.cos(angle) * radius), z = (int) Math.floor(Math.sin(angle) * radius);
            var center = generator.terrain().sample(chart.normal(x + .5, z + .5));
            double height = Math.floor(center.heightMeters());
            if (center.water() || height < 8 || height > chart.minY() + chart.height() - 128) { continue; }
            boolean safe = true;
            for (int corner = 0; corner < 4; corner++) {
                var sample = generator.terrain().sample(chart.normal(x + (corner % 2 == 0 ? -24 : 24),
                        z + (corner < 2 ? -24 : 24)));
                if (sample.water() || Math.abs(sample.heightMeters() - height) > 12) { safe = false; break; }
            }
            if (safe) { return new BlockPos(x, (int) height + 2, z); }
        }
        throw new IllegalStateException("Earth profile has no safe initial lowland in the bounded spawn search");
    }
}
