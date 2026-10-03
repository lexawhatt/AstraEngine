package dev.lexawhatt.astraengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.PlanetChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.UniformPaletteReads;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Reuses only canonical successful singleton decodes within the current owned tall-chunk read. */
@Mixin(ChunkSerializer.class)
abstract class PlanetaryReadPalettesMixin {
    // Pinned read has precisely two parse(DynamicOps,Object) sites: block states, then biomes.
    // Retrogen/blending use parse(Dynamic) and preserve their original independent behavior.
    @WrapOperation(method = "read", at = @At(value = "INVOKE", ordinal = 0, target =
            "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private static DataResult<PalettedContainer<BlockState>> astra$reuseReadBlocks(
            Codec<PalettedContainer<BlockState>> codec, DynamicOps<?> ops, Object input,
            Operation<DataResult<PalettedContainer<BlockState>>> original,
            @Local(argsOnly = true) ServerLevel level,
            @Share("astra$readPalettes") LocalRef<UniformPaletteReads> scope) {
        if (ops != NbtOps.INSTANCE || !(input instanceof Tag tag) || !astra$ownedTallRead(level)) {
            return original.call(codec, ops, input);
        }
        if (scope.get() == null) { scope.set(new UniformPaletteReads(level.registryAccess().registryOrThrow(Registries.BIOME))); }
        return scope.get().blocks(codec, tag, () -> original.call(codec, ops, input));
    }

    @WrapOperation(method = "read", at = @At(value = "INVOKE", ordinal = 1, target =
            "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private static DataResult<PalettedContainerRO<Holder<Biome>>> astra$reuseReadBiomes(
            Codec<PalettedContainerRO<Holder<Biome>>> codec, DynamicOps<?> ops, Object input,
            Operation<DataResult<PalettedContainerRO<Holder<Biome>>>> original,
            @Local(argsOnly = true) ServerLevel level,
            @Share("astra$readPalettes") LocalRef<UniformPaletteReads> scope) {
        if (ops != NbtOps.INSTANCE || !(input instanceof Tag tag) || !astra$ownedTallRead(level)) {
            return original.call(codec, ops, input);
        }
        if (scope.get() == null) { scope.set(new UniformPaletteReads(level.registryAccess().registryOrThrow(Registries.BIOME))); }
        return scope.get().biomes(codec, tag, () -> original.call(codec, ops, input));
    }

    @Unique
    private static boolean astra$ownedTallRead(ServerLevel level) {
        var generator = level.getChunkSource().getGenerator();
        return level.getHeight() == PlanetChart.HEIGHT
                && (generator instanceof EarthChunkGenerator || generator instanceof PlanetChunkGenerator);
    }
}
