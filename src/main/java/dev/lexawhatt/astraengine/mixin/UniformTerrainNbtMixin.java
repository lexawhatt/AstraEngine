package dev.lexawhatt.astraengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.PlanetChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.UniformBiomeNbt;
import dev.lexawhatt.astraengine.worldgen.UniformTerrainNbt;
import dev.lexawhatt.astraengine.worldgen.UniformTerrainStates;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reuses exact host encodings for uniform generated or owned tall-chart palettes; save format is unchanged. */
@Mixin(ChunkSerializer.class)
abstract class UniformTerrainNbtMixin {
    @Shadow @Final private static Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC;

    @Inject(method = "write", at = @At("HEAD"))
    private static void astra$captureWriteScope(ServerLevel level, ChunkAccess chunk,
            CallbackInfoReturnable<CompoundTag> callback,
            @Share("astra$ownedTallWrite") LocalBooleanRef owned) {
        var generator = level.getChunkSource().getGenerator();
        owned.set(chunk.getHeight() == PlanetChart.HEIGHT
                && (generator instanceof EarthChunkGenerator || generator instanceof PlanetChunkGenerator));
    }

    @WrapOperation(method = "write", at = @At(value = "INVOKE", target =
            "Lcom/mojang/serialization/Codec;encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private static DataResult<?> astra$reuseUniformPalette(Codec<?> codec, DynamicOps<?> ops, Object value,
            Operation<DataResult<?>> original, @Share("astra$ownedTallWrite") LocalBooleanRef owned) {
        if (codec == BLOCK_STATE_CODEC && ops == NbtOps.INSTANCE && value instanceof UniformTerrainStates states) {
            Tag encoded = UniformTerrainNbt.encode(states, palette ->
                    (Tag) original.call(codec, ops, palette).getOrThrow());
            if (encoded != null) { return DataResult.success(encoded); }
        }
        if (owned.get() && codec == BLOCK_STATE_CODEC && ops == NbtOps.INSTANCE
                && value instanceof PalettedContainer<?> states && states.getClass() == PalettedContainer.class) {
            @SuppressWarnings("unchecked")
            var blocks = (PalettedContainer<BlockState>) states;
            Tag encoded = UniformTerrainNbt.encodeHostPalette(blocks, palette ->
                    (Tag) original.call(codec, ops, palette).getOrThrow());
            if (encoded != null) { return DataResult.success(encoded); }
        }
        return original.call(codec, ops, value);
    }

    // The pinned 1.21.1 writer's fourth encodeStart is the per-section biome palette. Earlier calls encode
    // blending/retrogen and block states; they must retain their independent codecs and error behavior.
    @WrapOperation(method = "write", at = @At(value = "INVOKE", ordinal = 3, target =
            "Lcom/mojang/serialization/Codec;encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private static DataResult<?> astra$reuseUniformBiomes(Codec<?> codec, DynamicOps<?> ops, Object value,
            Operation<DataResult<?>> original, @Share("astra$ownedTallWrite") LocalBooleanRef owned,
            @Share("astra$biomeTemplates") LocalRef<UniformBiomeNbt> templates,
            @Local(name = "registry") Registry<Biome> registry) {
        if (owned.get() && ops == NbtOps.INSTANCE && value instanceof PalettedContainerRO<?>) {
            if (templates.get() == null) { templates.set(new UniformBiomeNbt(registry)); }
            @SuppressWarnings("unchecked")
            var biomes = (PalettedContainerRO<Holder<Biome>>) value;
            Tag encoded = templates.get().encode(biomes, palette ->
                    (Tag) original.call(codec, ops, palette).getOrThrow());
            if (encoded != null) { return DataResult.success(encoded); }
        }
        return original.call(codec, ops, value);
    }
}
