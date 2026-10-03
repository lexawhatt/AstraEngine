package dev.lexawhatt.astraengine.server.interaction;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.Container;

/** Preserves native chest slots/packets while forwarding their storage and reach to the permanent neighbor. */
public final class BoundaryMenus {
    private BoundaryMenus() { }
    /** True only for an actual open forwarded chest or an active ender inventory at this canonical block. */
    public static boolean observes(Player player, BlockEntity block) {
        if (player == null || block == null || !(player.containerMenu instanceof ChestMenu menu)) { return false; }
        if (block instanceof EnderChestBlockEntity ender) {
            return player.getEnderChestInventory().isActiveChest(ender);
        }
        return block instanceof Container canonical && menu.getContainer() instanceof BoundaryContainer forwarding
                && forwarding.contains(canonical);
    }
    public static MenuProvider wrap(MenuProvider provider, BoundaryActionScope scope) {
        if (provider == null || scope == null) { return provider; }
        return new MenuProvider() {
            @Override public Component getDisplayName() { return provider.getDisplayName(); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
                var menu = provider.createMenu(id, inventory, player);
                if (menu != null && menu.getClass() == ChestMenu.class) {
                    var chest = (ChestMenu) menu;
                    return new ChestMenu(menu.getType(), id, inventory,
                            new BoundaryContainer(chest.getContainer(), scope.target(), scope.owner()), chest.getRowCount());
                }
                return menu;
            }
            @Override public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buffer) {
                provider.writeClientSideData(menu, buffer);
            }
            @Override public boolean shouldTriggerClientSideContainerClosingOnOpen() {
                return provider.shouldTriggerClientSideContainerClosingOnOpen();
            }
        };
    }
}
