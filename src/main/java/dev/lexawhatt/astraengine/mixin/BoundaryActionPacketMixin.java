package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.server.interaction.BoundaryActionAccess;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A target-chart correction must not overwrite the same numeric position in the source client world. */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class BoundaryActionPacketMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void astra$canonicalCorrection(Packet<?> packet, PacketSendListener listener, CallbackInfo callback) {
        if (packet instanceof ClientboundBlockUpdatePacket && (Object) this instanceof ServerGamePacketListenerImpl connection
                && ((BoundaryActionAccess) connection.player).astra$actionScope() != null) {
            // The normal five-tick boundary snapshot carries the actual canonical state to every observer.
            callback.cancel();
        }
    }
}
