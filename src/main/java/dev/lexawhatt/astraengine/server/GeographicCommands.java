package dev.lexawhatt.astraengine.server;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.lexawhatt.astraengine.api.AstraGeography;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** Operator geographic diagnostics in an existing bound world; never creates terrain bindings or new worlds. */
public final class GeographicCommands {
    private GeographicCommands() {}

    /** Registers below /astra. The server-owned guard rejects travel while another engine route owns the player. */
    public static LiteralArgumentBuilder<CommandSourceStack> commands(Predicate<ServerPlayer> rejectTravel) {
        if (rejectTravel == null) { throw new IllegalArgumentException("A geographic travel guard is required"); }
        return Commands.literal("geography").requires(source -> source.hasPermission(2))
                .then(Commands.literal("here").executes(context -> describe(context.getSource())))
                .then(Commands.literal("tp")
                        .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-90, 90))
                                .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180))
                                        .then(Commands.argument("altitude", DoubleArgumentType.doubleArg())
                                                .executes(context -> {
                                                    double longitude = DoubleArgumentType.getDouble(context, "longitude");
                                                    var position = new GeographicPosition(Math.toRadians(
                                                            DoubleArgumentType.getDouble(context, "latitude")),
                                                            Math.toRadians(longitude == 180 ? -180 : longitude),
                                                            DoubleArgumentType.getDouble(context, "altitude"));
                                                    return teleport(context.getSource(), position, rejectTravel);
                                                })))));
    }

    private static int describe(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        var snapshot = AstraGeography.snapshot(player).orElse(null);
        if (snapshot == null) { return fail(source, "unsupported"); }
        // Use the exact host altitude datum rather than recovering it from rounded body-vector length.
        var reference = AstraGeography.planetaryReference(player.serverLevel()).orElseThrow();
        var geographic = reference.geographic(new SpaceVector(player.getX(), player.getY(), player.getZ()));
        source.sendSuccess(() -> Component.translatable("astraengine.geography.position", reference.geographyId(),
                number(Math.toDegrees(geographic.latitudeRadians()), 6),
                number(Math.toDegrees(geographic.longitudeRadians()), 6), number(geographic.altitudeMeters(), 2)), false);
        return 1;
    }

    private static int teleport(CommandSourceStack source, GeographicPosition geographic,
            Predicate<ServerPlayer> rejectTravel) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        if (rejectTravel.test(player)) { return 0; }
        var level = player.serverLevel();
        if (!player.isAlive() || player.isRemoved() || source.getLevel() != level) { return fail(source, "unsupported"); }
        var target = AstraGeography.resolve(level, geographic).orElse(null);
        if (target == null) { return fail(source, "outside"); }
        var event = EventHooks.onEntityTeleportCommand(player, target.x(), target.y(), target.z());
        if (event.isCanceled()) { return fail(source, "cancelled"); }
        double x = event.getTargetX(), y = event.getTargetY(), z = event.getTargetZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) { return fail(source, "outside"); }
        var feet = new SpaceVector(x, y, z);
        var reference = AstraGeography.planetaryReference(level).orElse(null);
        if (reference == null || !reference.contains(feet) || !level.getWorldBorder().isWithinBounds(x, z)
                || y < level.getMinBuildHeight() || y + player.getBbHeight() > level.getMaxBuildHeight()) {
            return fail(source, "outside");
        }
        // Host transport owns chunk tickets and networking. Like /tp, this is not a safe-ground finder.
        if (!player.teleportTo(level, x, y, z, Set.of(), player.getYRot(), player.getXRot())) {
            return fail(source, "cancelled");
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        return describe(source);
    }

    private static int fail(CommandSourceStack source, String key) {
        source.sendFailure(Component.translatable("astraengine.geography." + key));
        return 0;
    }

    private static String number(double value, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", value == 0 ? 0 : value);
    }
}
