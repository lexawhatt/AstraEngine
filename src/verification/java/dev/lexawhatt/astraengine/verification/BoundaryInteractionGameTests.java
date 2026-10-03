package dev.lexawhatt.astraengine.verification;

import com.mojang.authlib.GameProfile;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.server.interaction.BoundaryActionAccess;
import dev.lexawhatt.astraengine.server.interaction.BoundaryActionScope;
import dev.lexawhatt.astraengine.server.interaction.BoundaryMenus;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.EarthChartTransform;
import dev.lexawhatt.astraengine.surface.BoundaryCollision;
import dev.lexawhatt.astraengine.surface.BoundaryRaycast;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual host placement, block entities, inventory and coordinate restoration across one permanent storage seam. */
@PrefixGameTestTemplate(false)
public final class BoundaryInteractionGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void canceledCanonicalPlacementRestoresInventoryAndHostSnapshots(GameTestHelper helper) {
        var worlds = worlds(helper);
        var player = player(worlds.lower, "canceled");
        var top = new BlockPos(8, 2031, 8);
        var bottom = new BlockPos(8, -2032, 8);
        worlds.lower.setBlock(top, Blocks.STONE.defaultBlockState(), 3);
        worlds.upper.setBlock(bottom, Blocks.AIR.defaultBlockState(), 3);
        var stack = new ItemStack(Items.CHEST, 2);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Canceled canonical chest"));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var observed = new AtomicBoolean();
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
            if (event.getEntity() == player) {
                helper.assertTrue(event.getLevel() == worlds.upper && player.serverLevel() == worlds.upper
                        && event.getPos().equals(bottom), "Placement hooks did not observe their real canonical world");
                observed.set(true);
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(new Vec3(8.5, 2032, 8.5), Direction.UP, top, false)));
            helper.assertTrue(observed.get(), "Canonical placement bypassed the host placement event");
            helper.assertTrue(stack.getCount() == 2 && stack.get(DataComponents.CUSTOM_NAME).getString()
                    .equals("Canceled canonical chest"), "Canceled placement consumed or changed its item");
            helper.assertTrue(worlds.upper.getBlockState(bottom).isAir() && worlds.upper.getBlockEntity(bottom) == null
                    && worlds.lower.getBlockState(top.above()).isAir(), "Cancellation left a canonical block or alias");
            helper.assertTrue(!worlds.upper.captureBlockSnapshots && worlds.upper.capturedBlockSnapshots.isEmpty()
                    && player.serverLevel() == worlds.lower && ((BoundaryActionAccess) player).astra$actionScope() == null,
                    "Cancellation leaked host snapshots or callback ownership");
        } finally { NeoForge.EVENT_BUS.unregister(listener); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void faceEdgePlacementUsesTheRotatedCanonicalCell(GameTestHelper helper) {
        var base = SolidPlanetGameTests.chart("moon");
        var sourceChart = new PlanetChart(base.profile(), base.face(), 6);
        int radius = (int) sourceChart.radiusMeters();
        var source = PlanetSurfaceWorlds.ensure(helper.getLevel().getServer(), sourceChart);
        var proposed = new SpaceVector(radius + .5, .5, 8.5);
        var owner = sourceChart.ownerChart(sourceChart.projectedGeographic(proposed)).orElseThrow();
        var target = PlanetSurfaceWorlds.ensure(helper.getLevel().getServer(), owner);
        var mapped = new EarthChartTransform(sourceChart, owner).position(proposed);
        var destination = BlockPos.containing(mapped.x(), mapped.y(), mapped.z());
        var support = new BlockPos(radius - 1, 0, 8);
        source.getChunk(support.getX() >> 4, 0);
        target.getChunk(destination.getX() >> 4, destination.getZ() >> 4);
        source.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
        target.setBlock(destination, Blocks.AIR.defaultBlockState(), 3);
        var player = player(source, "face");
        player.setPos(radius - 2.5, 0, 10.5);
        var stack = new ItemStack(Items.DIAMOND_BLOCK, 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(new Vec3(radius, .5, 8.5), Direction.EAST, support, false)));
        helper.assertTrue(result.consumesAction() && stack.getCount() == 1
                && target.getBlockState(destination).is(Blocks.DIAMOND_BLOCK), "Rotated face placement missed its physical owner");
        helper.assertTrue(source.getBlockState(support.east()).isAir() && player.serverLevel() == source,
                "Face placement left a source alias or changed entity ownership");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void placementRoutesBothDirectionsWithoutAliasesAndPreservesComponents(GameTestHelper helper) {
        var worlds = worlds(helper);
        var player = player(worlds.lower, "placement");
        var top = new BlockPos(8, 2031, 8);
        var bottom = new BlockPos(8, -2032, 8);
        worlds.lower.setBlock(top, Blocks.STONE.defaultBlockState(), 3);
        worlds.upper.setBlock(bottom, Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(!worlds.lower.setBlock(top.above(), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3),
                "Ordinary writes created alias storage beyond the band");
        var stack = new ItemStack(Items.CHEST, 2);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Canonical chest"));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(new Vec3(8.5, 2032, 8.5), Direction.UP, top, false)));
        helper.assertTrue(result.consumesAction() && stack.getCount() == 1, "Host placement did not consume exactly one item");
        helper.assertTrue(worlds.upper.getBlockEntity(bottom) instanceof ChestBlockEntity chest
                && chest.getName().getString().equals("Canonical chest"), "Canonical placement lost native block entity components");
        helper.assertTrue(worlds.lower.getBlockState(top.above()).isAir(), "Placement retained a source alias");
        helper.assertTrue(player.serverLevel() == worlds.lower && player.position().equals(new Vec3(10.5, 2030, 8.5)),
                "Placement did not restore the player's source pose");

        worlds.lower.setBlock(top, Blocks.AIR.defaultBlockState(), 3);
        var second = new ItemStack(Items.DIAMOND_BLOCK, 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, second);
        var reverse = BoundaryActionScope.call(player, worlds.upper, worlds.upperChart, () -> second.useOn(
                new UseOnContext(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(new Vec3(8.5, -2032, 8.5), Direction.DOWN, bottom, false))));
        helper.assertTrue(reverse.consumesAction() && second.getCount() == 1
                && worlds.lower.getBlockState(top).is(Blocks.DIAMOND_BLOCK), "Nested placement back into the source failed");
        helper.assertTrue(worlds.upper.getBlockState(bottom.below()).isAir()
                && ((BoundaryActionAccess) player).astra$actionScope() == null, "Reverse placement leaked an alias or action scope");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void twoPlayersShareOneCanonicalChestAndScopeRestoresAfterFailure(GameTestHelper helper) {
        var worlds = worlds(helper);
        var first = player(worlds.lower, "first");
        var second = player(worlds.lower, "second");
        var position = new BlockPos(12, -2032, 8);
        worlds.upper.setBlock(position, Blocks.CHEST.defaultBlockState(), 3);
        var chest = (ChestBlockEntity) worlds.upper.getBlockEntity(position);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
        first.setYRot(-90); first.setXRot((float) -Math.toDegrees(Math.atan2(.88, 2)));
        var source = PlanetSurfaceWorlds.getCube(worlds.lower).orElseThrow();
        var observedChest = BoundaryRaycast.pick(source, first.getEyePosition(), first.getLookAngle().normalize(), 4.5,
                owner -> BoundaryCollision.observations(worlds.lower, owner), first).orElseThrow();
        helper.assertTrue(observedChest.owner().equals(worlds.upperChart) && observedChest.position().equals(position),
                "An oblique actual host yaw/pitch ray missed the canonical chest");
        ChestMenu firstMenu = menu(first, worlds, chest), secondMenu = menu(second, worlds, chest);
        first.containerMenu = firstMenu; second.containerMenu = secondMenu;
        helper.assertTrue(firstMenu.stillValid(first) && secondMenu.stillValid(second), "Geographic chest reach failed");
        helper.assertTrue(BoundaryMenus.observes(first, chest) && BoundaryMenus.observes(second, chest),
                "Real forwarded menus lost their canonical chest opener identity");
        helper.assertTrue(firstMenu.getSlot(0).remove(2).getCount() == 2 && secondMenu.getSlot(0).getItem().getCount() == 5
                && chest.getItem(0).getCount() == 5, "Two players received separate inventory copies");
        var original = first.position(); var bounds = first.getBoundingBox();
        try {
            BoundaryActionScope.call(first, worlds.upper, worlds.upperChart, () -> { throw new IllegalArgumentException("fixture callback"); });
            helper.assertTrue(false, "Callback failure was swallowed");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(expected.getMessage().equals("fixture callback"), "Unexpected scope failure");
        }
        helper.assertTrue(first.serverLevel() == worlds.lower && original.equals(first.position())
                && bounds.equals(first.getBoundingBox()) && ((BoundaryActionAccess) first).astra$actionScope() == null,
                "Failed callback leaked numeric pose or action ownership");
        firstMenu.removed(first); secondMenu.removed(second);
        first.containerMenu = first.inventoryMenu; second.containerMenu = second.inventoryMenu;
        helper.assertTrue(!BoundaryMenus.observes(first, chest) && !BoundaryMenus.observes(second, chest),
                "Closed canonical menus retained their chest opener identity");
        BoundaryActionScope.call(first, worlds.upper, worlds.upperChart, () -> {
            first.setServerLevel(helper.getLevel());
            return true;
        });
        helper.assertTrue(first.serverLevel() == helper.getLevel() && original.equals(first.position())
                && ((BoundaryActionAccess) first).astra$actionScope() == null,
                "Closing a callback overwrote the host's actual level transition");
        helper.succeed();
    }

    private static ChestMenu menu(FakePlayer player, Worlds worlds, ChestBlockEntity chest) {
        return BoundaryActionScope.call(player, worlds.upper, worlds.upperChart, () -> (ChestMenu) BoundaryMenus.wrap(chest,
                ((BoundaryActionAccess) player).astra$actionScope()).createMenu(1, player.getInventory(), player));
    }

    private static FakePlayer player(ServerLevel level, String suffix) {
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "Boundary_" + suffix));
        player.setPos(10.5, 2030, 8.5);
        player.getAbilities().mayBuild = true;
        return player;
    }

    private static Worlds worlds(GameTestHelper helper) {
        var base = SolidPlanetGameTests.chart("moon");
        var lowerChart = new PlanetChart(base.profile(), base.face(), 6);
        var upperChart = new PlanetChart(base.profile(), base.face(), 7);
        var server = helper.getLevel().getServer();
        var lower = PlanetSurfaceWorlds.ensure(server, lowerChart);
        var upper = PlanetSurfaceWorlds.ensure(server, upperChart);
        lower.getChunk(0, 0); upper.getChunk(0, 0);
        return new Worlds(lower, upper, upperChart);
    }
    private record Worlds(ServerLevel lower, ServerLevel upper, PlanetChart upperChart) { }
}
