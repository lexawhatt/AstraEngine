package dev.lexawhatt.astraengine.worldgen;

import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthChart;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;

/** Physical snow/freezing temperature for Earth storage; does not alter vanilla or legacy biome definitions. */
public final class EarthWeather {
    private EarthWeather() {}

    /**
     * Narrow host integration for Biome.shouldSnow/shouldFreeze. Uses only the immutable generator on the
     * owning server or generation worker; no registry/world mutation or loaded-world lookup occurs. Unsupported
     * readers use the original biome test. Suppresses artificial ice at a submerged altitude-band ceiling.
     */
    public static boolean warmEnoughToRain(Biome biome, LevelReader reader, BlockPos position) {
        ServerLevel level = reader instanceof ServerLevel server ? server
                : reader instanceof WorldGenRegion generation ? generation.getLevel() : null;
        if (level == null || !(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator)) {
            return biome.warmEnoughToRain(position);
        }
        return warmEnough(generator.chart(), generator.terrain(), position.getX(), position.getY(), position.getZ());
    }

    /** Pure worker-safe physical predicate. A subsurface storage ceiling is not an exposed water/snow surface. */
    public static boolean warmEnough(EarthChart chart, ContinentalTerrain terrain, int x, int y, int z) {
        if (chart == null || terrain == null) { throw new IllegalArgumentException("Earth weather requires chart and field"); }
        var normal = chart.normal(x + .5, z + .5);
        var sample = terrain.sample(normal);
        double altitude = y + chart.altitudeOriginMeters();
        double temperature = terrain.version() >= 3
                ? sample.temperature() - (Math.max(0, altitude) - Math.max(0, sample.heightMeters())) * .0065
                : 31 - 48 * Math.pow(Math.abs(normal.y()), 1.15) - Math.max(0, altitude) * .0065;
        return altitude < Math.max(0, sample.heightMeters()) - 2 || temperature >= 0;
    }
}
