package dev.lexawhatt.astraengine.server.interaction;

import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Menu-lifetime forwarding view of the actual canonical inventory; no copies or second writable storage. */
final class BoundaryContainer implements Container {
    private final Container canonical;
    private final ServerLevel level;
    private final CubeStorageChart owner;
    private boolean alreadyOpened = true;

    BoundaryContainer(Container canonical, ServerLevel level, CubeStorageChart owner) {
        this.canonical = canonical; this.level = level; this.owner = owner;
    }
    boolean contains(Container container) {
        return canonical == container || canonical instanceof CompoundContainer compound && compound.contains(container);
    }
    @Override public int getContainerSize() { return canonical.getContainerSize(); }
    @Override public boolean isEmpty() { return canonical.isEmpty(); }
    @Override public ItemStack getItem(int slot) { return canonical.getItem(slot); }
    @Override public ItemStack removeItem(int slot, int count) { return canonical.removeItem(slot, count); }
    @Override public ItemStack removeItemNoUpdate(int slot) { return canonical.removeItemNoUpdate(slot); }
    @Override public void setItem(int slot, ItemStack stack) { canonical.setItem(slot, stack); }
    @Override public int getMaxStackSize() { return canonical.getMaxStackSize(); }
    @Override public int getMaxStackSize(ItemStack stack) { return canonical.getMaxStackSize(stack); }
    @Override public void setChanged() { canonical.setChanged(); }
    @Override public void clearContent() { canonical.clearContent(); }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return canonical.canPlaceItem(slot, stack); }
    @Override public boolean canTakeItem(Container target, int slot, ItemStack stack) { return canonical.canTakeItem(target, slot, stack); }
    @Override public void startOpen(Player player) {
        if (alreadyOpened) { alreadyOpened = false; } else { canonical.startOpen(player); }
    }
    @Override public void stopOpen(Player player) { canonical.stopOpen(player); }
    @Override public boolean stillValid(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || !player.isAlive() || player.isRemoved()
                || serverPlayer.server.getLevel(level.dimension()) != level) { return false; }
        var source = PlanetSurfaceWorlds.getCube(serverPlayer.serverLevel()).orElse(null);
        if (source == null || !source.geographyId().equals(owner.geographyId())) { return false; }
        if (source.normal(player.getX(), player.getZ()).dot(owner.face().outward()) <= 0) { return false; }
        if (((BoundaryActionAccess) player).astra$actionScope() != null) { return canonical.stillValid(player); }
        return BoundaryActionScope.call(serverPlayer, level, owner, () -> canonical.stillValid(player));
    }
}
