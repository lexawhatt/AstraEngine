package dev.lexawhatt.astraengine.server;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import dev.lexawhatt.astraengine.network.SolarPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread occupancy, diagnostic command and snapshot adapter for the persisted Sol evolution model. */
public final class SolarEvolutionService {
    private final MinecraftServer server;
    private final RocketService rocket;

    /** Creates one owner for a running server; it does not start a depletion schedule. */
    public SolarEvolutionService(MinecraftServer server, RocketService rocket) {
        this.server = server; this.rocket = rocket; SolarState.get(server);
    }

    /** Advances only for living Overworld observers or actual Rocket occupants whose current system is Sol. */
    public void tick() {
        boolean occupied = server.overworld().players().stream().anyMatch(ServerPlayer::isAlive);
        if (!occupied && rocket != null) {
            ExplorationCatalog catalog = ExplorationCatalog.get(server);
            occupied = server.getPlayerList().getPlayers().stream().anyMatch(player -> player.isAlive()
                    && rocket.active(player) && RocketService.isFlightWorld(player)
                    && catalog.player(player.getUUID()).systemId().equals("sol"));
        }
        SolarState.get(server).tick(occupied);
        if (server.getTickCount() % 5 == 0) { broadcast(server); }
    }

    /** Sends a full snapshot on login so reload/reconnect never begins with invented client phase state. */
    public void send(ServerPlayer player) { PacketDistributor.sendToPlayer(player, new SolarPayload(SolarState.get(server).snapshot())); }

    /** Operator-only subtree intended beneath /astra. All mutations happen on the command's owning server thread. */
    public static LiteralArgumentBuilder<CommandSourceStack> commands() {
        return Commands.literal("sun").requires(source -> source.hasPermission(2))
                .then(Commands.literal("demo").executes(context -> demo(context.getSource(), 60))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 3600)).executes(context ->
                                demo(context.getSource(), IntegerArgumentType.getInteger(context, "seconds")))))
                .then(Commands.literal("pause").executes(context -> change(context.getSource(), "pause")))
                .then(Commands.literal("resume").executes(context -> change(context.getSource(), "resume")))
                .then(Commands.literal("reset").executes(context -> change(context.getSource(), "reset")))
                .then(Commands.literal("status").executes(context -> status(context.getSource())));
    }

    private static int demo(CommandSourceStack source, int seconds) {
        SolarState.get(source.getServer()).startDemo(seconds);
        source.sendSuccess(() -> Component.translatable("astraengine.sun.demo", seconds), true);
        broadcast(source.getServer()); return 1;
    }
    private static int change(CommandSourceStack source, String action) {
        SolarState state = SolarState.get(source.getServer());
        switch (action) {
            case "pause" -> state.pause();
            case "resume" -> {
                if (!state.resume()) {
                    source.sendFailure(Component.translatable("astraengine.sun.resume_unavailable")); return 0;
                }
            }
            case "reset" -> state.reset();
            default -> throw new IllegalArgumentException("Unknown solar diagnostic command");
        }
        source.sendSuccess(() -> Component.translatable("astraengine.sun." + action), true);
        broadcast(source.getServer()); return 1;
    }
    private static int status(CommandSourceStack source) {
        StellarEvolutionSnapshot snapshot = SolarState.get(source.getServer()).snapshot();
        source.sendSuccess(() -> Component.translatable("astraengine.sun.status", snapshot.phase().name(), snapshot.remaining(),
                snapshot.extracted(), snapshot.activeTicks(), snapshot.phaseTicks(), snapshot.running(),
                snapshot.revision(), snapshot.cycle()), false);
        return 1;
    }
    private static void broadcast(MinecraftServer server) {
        SolarPayload payload = new SolarPayload(SolarState.get(server).snapshot());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) { PacketDistributor.sendToPlayer(player, payload); }
    }
}
