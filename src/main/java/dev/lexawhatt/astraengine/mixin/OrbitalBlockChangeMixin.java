package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.server.orbit.OrbitalBlockChangedEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Host events omit /fill and several non-player changes; this narrow hook observes successful full-chunk writes only. */
@Mixin(LevelChunk.class)
abstract class OrbitalBlockChangeMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void astra$changed(BlockPos position, BlockState state, boolean moving,
            CallbackInfoReturnable<BlockState> callback) {
        if (callback.getReturnValue() == null) { return; }
        var chunk = (LevelChunk) (Object) this;
        if (chunk.getLevel() instanceof ServerLevel level && level.getServer().isSameThread()) {
            NeoForge.EVENT_BUS.post(new OrbitalBlockChangedEvent(level, chunk.getPos()));
        }
    }
}
