package dev.lexawhatt.astraengine.server.interaction;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.EarthChartTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Routes an actual block-item placement before NeoForge captures snapshots, retaining its normal item/event path. */
public final class BoundaryPlacement {
    private BoundaryPlacement() { }

    /** Null keeps the host call unchanged. A rejected foreign placement consumes neither a block nor inventory. */
    public static InteractionResult route(ItemStack stack, UseOnContext context) {
        if (!(stack.getItem() instanceof BlockItem) || !(context.getPlayer() instanceof ServerPlayer player)
                || !(context.getLevel() instanceof ServerLevel sourceLevel)) { return null; }
        var source = PlanetSurfaceWorlds.getCube(sourceLevel).orElse(null);
        if (source == null) { return null; }
        var placement = new BlockPlaceContext(context);
        var proposed = placement.getClickedPos();
        var center = new SpaceVector(proposed.getX() + .5, proposed.getY() + .5, proposed.getZ() + .5);
        if (source.contains(center)) { return null; }
        var owner = source.ownerChart(source.projectedGeographic(center)).orElse(null);
        if (owner == null || !player.isAlive() || player.isRemoved() || player.serverLevel() != sourceLevel) {
            return InteractionResult.FAIL;
        }
        var target = player.server.getLevel(PlanetSurfaceWorlds.dimension(owner));
        if (target == null || !PlanetSurfaceWorlds.getCube(target).filter(owner::equals).isPresent()) {
            return InteractionResult.FAIL;
        }
        var transform = new EarthChartTransform(source, owner);
        var mapped = transform.position(center);
        var destination = BlockPos.containing(mapped.x(), mapped.y(), mapped.z());
        if (target.getChunkSource().getChunkNow(destination.getX() >> 4, destination.getZ() >> 4) == null) {
            return InteractionResult.FAIL;
        }
        var anchor = context.getClickedPos();
        var mappedAnchor = transform.position(new SpaceVector(anchor.getX() + .5, anchor.getY() + .5, anchor.getZ() + .5));
        var face = context.getClickedFace();
        var faceVector = transform.velocity(center, new SpaceVector(face.getStepX(), face.getStepY(), face.getStepZ()));
        var mappedFace = Direction.getNearest(faceVector.x(), faceVector.y(), faceVector.z());
        var click = context.getClickLocation();
        var mappedClick = transform.position(new SpaceVector(click.x, click.y, click.z));
        var hit = new BlockHitResult(new Vec3(mappedClick.x(), mappedClick.y(), mappedClick.z()), mappedFace,
                BlockPos.containing(mappedAnchor.x(), mappedAnchor.y(), mappedAnchor.z()), context.isInside());
        return BoundaryActionScope.call(player, target, owner, () -> {
            var mappedContext = new UseOnContext(target, player, context.getHand(), stack, hit);
            if (!new BlockPlaceContext(mappedContext).getClickedPos().equals(destination)
                    || !target.mayInteract(player, destination) || !player.canInteractWithBlock(destination, 0)) {
                return InteractionResult.FAIL;
            }
            // This recursion terminates because the mapped placement is canonical in its target world.
            return stack.useOn(mappedContext);
        });
    }
}
