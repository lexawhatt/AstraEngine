package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.client.surface.BoundaryHandoffAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Prevents the host's forced intermediate loading frame only when real prepared geometry bridges the new level. */
@Mixin(Minecraft.class)
abstract class PlanetaryMinecraftMixin {
    @Shadow protected abstract void updateScreenAndTick(Screen screen);

    @Redirect(method = "setLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;updateScreenAndTick(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void astra$preparedFrame(Minecraft game, Screen screen, ClientLevel destination, ReceivingLevelScreen.Reason reason) {
        if (!(game.getConnection() instanceof BoundaryHandoffAccess connection)
                || !connection.astra$handoff().canBridge(destination.dimension())) { updateScreenAndTick(screen); }
    }
}
