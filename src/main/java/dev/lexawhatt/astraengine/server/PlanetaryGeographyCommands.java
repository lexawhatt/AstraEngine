package dev.lexawhatt.astraengine.server;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.PlanetaryTerrain;
import dev.lexawhatt.astraengine.surface.TileEdge;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Read-only operator diagnostics for the closed geography contract; never authorizes world travel. */
public final class PlanetaryGeographyCommands {
    private PlanetaryGeographyCommands() {}

    /** Mounted below /astra; permission is also enforced here for safe independent registration. */
    public static LiteralArgumentBuilder<CommandSourceStack> commands() {
        return Commands.literal("terrain").requires(source -> source.hasPermission(2))
                .then(Commands.literal("here").executes(context -> {
                    var source = context.getSource();
                    if (!source.getLevel().dimension().equals(PlanetaryTerrainWorld.DIMENSION)) {
                        source.sendFailure(Component.translatable("astraengine.terrain.highlands_only"));
                        return 0;
                    }
                    var position = source.getPosition();
                    return describe(source, GeographicPosition.fromBody(
                            PlanetaryTerrain.PATCH.normal(position.x, position.z).multiply(PlanetaryTerrain.PATCH.radiusMeters()),
                            PlanetaryTerrain.PATCH.radiusMeters()));
                }))
                .then(Commands.literal("locate")
                        .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-90, 90))
                                .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180))
                                        .executes(context -> {
                                            double longitude = DoubleArgumentType.getDouble(context, "longitude");
                                            return describe(context.getSource(), new GeographicPosition(Math.toRadians(
                                                    DoubleArgumentType.getDouble(context, "latitude")),
                                                    Math.toRadians(longitude == 180 ? -180 : longitude), 0));
                                        }))));
    }

    private static int describe(CommandSourceStack source, GeographicPosition position) {
        var state = PlanetaryGeographyState.get(source.getServer());
        var topology = state.topology();
        var tile = topology.locate(position.normal());
        String latitude = String.format(Locale.ROOT, "%.6f", Math.toDegrees(position.latitudeRadians()));
        String longitude = String.format(Locale.ROOT, "%.6f", Math.toDegrees(position.longitudeRadians()));
        String height = String.format(Locale.ROOT, "%.2f", new PlanetaryTerrain(PlanetaryTerrain.VERSION,
                PlanetaryTerrain.SEED).sample(position.normal()).heightMeters());
        source.sendSuccess(() -> Component.translatable("astraengine.terrain.location", latitude, longitude,
                state.tileKey(tile), height), false);
        for (TileEdge edge : TileEdge.values()) {
            var neighbor = topology.neighbor(tile, edge);
            source.sendSuccess(() -> Component.translatable("astraengine.terrain.neighbor", edge.name(),
                    state.tileKey(neighbor.tile()), neighbor.entryEdge().name(), neighbor.reversed()), false);
        }
        return 1;
    }
}
