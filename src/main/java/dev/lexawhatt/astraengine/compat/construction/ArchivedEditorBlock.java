package dev.lexawhatt.astraengine.compat.construction;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Inert, survival-protected marker for an archived construction record. */
public final class ArchivedEditorBlock extends BaseEntityBlock {
    private static final MapCodec<ArchivedEditorBlock> CODEC = simpleCodec(ArchivedEditorBlock::new);

    /** Called by the historical block registration and native codec. */
    public ArchivedEditorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** Creates a passive record owned and saved by the containing chunk. */
    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new ArchivedEditorBlockEntity(position, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position,
            Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        player.displayClientMessage(Component.translatable("astraengine.compat.construction_archived"), false);
        return InteractionResult.CONSUME;
    }
}
