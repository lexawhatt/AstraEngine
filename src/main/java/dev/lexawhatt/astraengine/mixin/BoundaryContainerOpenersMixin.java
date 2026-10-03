package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.server.interaction.BoundaryMenus;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps actual geographic menu users in the host's lid, sound and trapped-chest opener lifecycle. */
@Mixin(ContainerOpenersCounter.class)
abstract class BoundaryContainerOpenersMixin {
    @Inject(method = "getPlayersWithContainerOpen", at = @At("RETURN"), cancellable = true)
    private void astra$includeGeographicUsers(Level level, BlockPos position,
            CallbackInfoReturnable<List<Player>> callback) {
        if (!(level instanceof ServerLevel server)) { return; }
        var block = level.getBlockEntity(position);
        if (block == null) { return; }
        List<Player> result = null;
        for (var player : server.getServer().getPlayerList().getPlayers()) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()
                    || !BoundaryMenus.observes(player, block) || callback.getReturnValue().contains(player)) { continue; }
            if (result == null) { result = new ArrayList<>(callback.getReturnValue()); }
            result.add(player);
        }
        if (result != null) { callback.setReturnValue(result); }
    }
}
