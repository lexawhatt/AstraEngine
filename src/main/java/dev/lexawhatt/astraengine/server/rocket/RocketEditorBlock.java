package dev.lexawhatt.astraengine.server.rocket;

import com.mojang.serialization.MapCodec;
import dev.lexawhatt.astraengine.api.rocket.AstraRocketEditor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** One diagnostic entry block; gameplay consumers provide their own RocketEditorHost and permissions. */
public final class RocketEditorBlock extends BaseEntityBlock {
    private static final MapCodec<RocketEditorBlock> CODEC = simpleCodec(RocketEditorBlock::new);
    public RocketEditorBlock(Properties properties) { super(properties); }
    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override
    protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) { return new RocketEditorBlockEntity(position, state); }
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
        if (level.isClientSide) { return InteractionResult.SUCCESS; }
        return player instanceof ServerPlayer serverPlayer && AstraRocketEditor.open(serverPlayer, position)
                ? InteractionResult.CONSUME : InteractionResult.FAIL;
    }
}
