package dev.lexawhatt.astraengine.server;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.lexawhatt.astraengine.api.AstraSky;
import dev.lexawhatt.astraengine.network.SkyProfilePayload;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import dev.lexawhatt.astraengine.sky.SkyEphemeris;
import dev.lexawhatt.astraengine.sky.SkySample;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Locale;
import java.util.function.DoubleFunction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread settings transport and operator commands. Minecraft's host dayTime is never advanced here. */
public final class SkyService {
    private SkyService() {
    }

    /** Sends current settings to a joining client; connection ownership outlives client resource reloads. */
    public static void send(ServerPlayer player) {
        if (player == null) { throw new IllegalArgumentException("A server player is required for sky settings"); }
        SkyState state = SkyState.get(player.server);
        PacketDistributor.sendToPlayer(player, new SkyProfilePayload(state.revision(), state.profile()));
    }

    /** Publishes a new complete settings revision; requires the owning server thread. */
    public static void broadcast(MinecraftServer server) {
        SkyState state = SkyState.get(server);
        SkyProfilePayload payload = new SkyProfilePayload(state.revision(), state.profile());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) { PacketDistributor.sendToPlayer(player, payload); }
    }

    /** Operator-only subtree under /astra. Settings persist; season selection changes offset rather than host time. */
    public static LiteralArgumentBuilder<CommandSourceStack> commands() {
        LiteralArgumentBuilder<CommandSourceStack> seasons = Commands.literal("set");
        String[] names = {"spring", "summer", "autumn", "winter"};
        for (int index = 0; index < names.length; index++) {
            double phase = index * 0.25;
            String name = names[index];
            seasons.then(Commands.literal(name).executes(context -> {
                CommandSourceStack source = context.getSource();
                PlanetarySkyProfile profile = AstraSky.profile(source.getServer());
                AstraSky.configure(source.getServer(), SkyEphemeris.withSeasonAtTime(profile,
                        source.getServer().overworld().getDayTime(), phase));
                source.sendSuccess(() -> Component.translatable("astraengine.season.selected",
                        Component.translatable("astraengine.season." + name)), true);
                return status(source);
            }));
        }
        return Commands.literal("season").requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(seasons)
                .then(Commands.literal("year").then(Commands.argument("days",
                        IntegerArgumentType.integer(4, PlanetarySkyProfile.MAX_YEAR_DAYS)).executes(context -> {
                            CommandSourceStack source = context.getSource();
                            return configure(source, AstraSky.profile(source.getServer()).withYearDays(
                                    IntegerArgumentType.getInteger(context, "days")));
                        })))
                .then(numeric("latitude", -90, 90))
                .then(numeric("tilt", 0, 90))
                .then(numeric("pollution", 0, 1))
                .then(numeric("sun-size", 1, 8));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> numeric(String command, double minimum, double maximum) {
        return Commands.literal(command).then(Commands.argument("value", DoubleArgumentType.doubleArg(minimum, maximum))
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    PlanetarySkyProfile profile = AstraSky.profile(source.getServer());
                    DoubleFunction<PlanetarySkyProfile> change = switch (command) {
                        case "latitude" -> profile::withLatitudeDegrees;
                        case "tilt" -> profile::withAxialTiltDegrees;
                        case "pollution" -> profile::withLightPollution;
                        case "sun-size" -> profile::withSunSizeMultiplier;
                        default -> throw new IllegalArgumentException("Unknown sky setting command");
                    };
                    return configure(source, change.apply(DoubleArgumentType.getDouble(context, "value")));
                }));
    }

    private static int configure(CommandSourceStack source, PlanetarySkyProfile replacement) {
        AstraSky.configure(source.getServer(), replacement);
        source.sendSuccess(() -> Component.translatable("astraengine.season.updated"), true);
        return status(source);
    }

    private static int status(CommandSourceStack source) {
        SkyState state = SkyState.get(source.getServer());
        PlanetarySkyProfile profile = state.profile();
        var chart = EarthWorlds.chart(source.getLevel()).orElse(null);
        var position = source.getPosition();
        var feet = new SpaceVector(position.x, position.y, position.z);
        var observer = chart != null && chart.contains(feet) ? chart.geographic(feet) : null;
        double latitude = observer == null ? profile.latitudeDegrees() : Math.toDegrees(observer.latitudeRadians());
        SkySample sample = observer == null ? AstraSky.snapshot(source.getServer())
                : AstraSky.snapshotAt(source.getServer(), observer);
        source.sendSuccess(() -> Component.translatable("astraengine.season.status", profile.yearDays(),
                number(latitude), number(profile.axialTiltDegrees()), number(sample.seasonPhase() * 360),
                number(sample.daylightHours()), number(Math.toDegrees(sample.solarAltitudeRadians())),
                number(profile.lightPollution()), number(profile.sunSizeMultiplier()), state.revision()), false);
        return 1;
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.2f", value); }
}
