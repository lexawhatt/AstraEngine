package dev.lexawhatt.astraengine.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Numeric action pose only: bypassing setPos prevents temporary queries from moving host entity-section ownership. */
@Mixin(Entity.class)
public interface BoundaryEntityAccessor {
    @Accessor("level") void astra$actionLevel(Level level);
    @Accessor("position") void astra$actionPosition(Vec3 position);
    @Accessor("blockPosition") void astra$actionBlockPosition(BlockPos position);
    @Accessor("chunkPosition") void astra$actionChunkPosition(ChunkPos position);
    @Accessor("inBlockState") void astra$actionBlockState(BlockState state);
}
