package dev.lexawhatt.astraengine.verification;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Fixture-only host teleport behavior; FakePlayer's default listener intentionally makes teleport a no-op. */
final class HostPlayerConnection implements AutoCloseable {
    private final ServerPlayer player;
    private final ServerGamePacketListenerImpl previous;
    private final EmbeddedChannel channel = new EmbeddedChannel();

    HostPlayerConnection(ServerPlayer player) {
        this.player = player;
        this.previous = player.connection;
        var transport = new Connection(PacketFlow.SERVERBOUND) {
            @Override public Channel channel() { return HostPlayerConnection.this.channel; }
        };
        player.connection = new ServerGamePacketListenerImpl(player.server, transport, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            // Preserve the real listener's position/teleport acknowledgement state, but there is no client socket.
            @Override public void send(Packet<?> packet) { }
            @Override public void send(Packet<?> packet, PacketSendListener listener) { }
        };
    }

    @Override public void close() {
        player.connection = previous;
        channel.finishAndReleaseAll();
    }
}
