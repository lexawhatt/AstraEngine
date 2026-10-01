package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.worldgen.EarthWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Replaces only the coordinate-dependent weather predicate, preserving all host block/light/survival checks. */
@Mixin(Biome.class)
abstract class EarthBiomeWeatherMixin {
    @Redirect(method = "shouldSnow", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;warmEnoughToRain(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean astra$snowTemperature(Biome biome, BlockPos argument, LevelReader level, BlockPos position) {
        return EarthWeather.warmEnoughToRain(biome, level, position);
    }

    @Redirect(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/Biome;warmEnoughToRain(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean astra$freezeTemperature(Biome biome, BlockPos argument, LevelReader level, BlockPos position, boolean edge) {
        return EarthWeather.warmEnoughToRain(biome, level, position);
    }
}
