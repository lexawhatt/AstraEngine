package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.client.surface.BoundaryHandoffAccess;
import dev.lexawhatt.astraengine.client.surface.BoundaryHandoffClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bridges only acknowledged server-selected chart transitions; ordinary respawn/loading remains host-owned. */
@Mixin(ClientPacketListener.class)
abstract class PlanetaryClientPacketMixin implements BoundaryHandoffAccess {
    @Unique private final BoundaryHandoffClient astra$handoff = new BoundaryHandoffClient();
    @Override public BoundaryHandoffClient astra$handoff() { return astra$handoff; }

    @Redirect(method = "startWaitingForNewLevel(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/gui/screens/ReceivingLevelScreen$Reason;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/resources/ResourceKey;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void astra$preparedScreen(Minecraft game, Screen screen) {
        if (game.level == null || !astra$handoff.canBridge(game.level.dimension())) { game.setScreen(screen); }
    }

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void astra$installPrepared(ClientboundRespawnPacket packet, CallbackInfo callback) { astra$handoff.install(); }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void astra$restoreMotion(ClientboundPlayerPositionPacket packet, CallbackInfo callback) { astra$handoff.moved(); }
}
