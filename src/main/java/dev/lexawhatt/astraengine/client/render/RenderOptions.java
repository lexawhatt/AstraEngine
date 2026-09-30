package dev.lexawhatt.astraengine.client.render;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Locale;
import dev.lexawhatt.astraengine.client.environment.EnvironmentProfiles;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Session-local visual controls; no command here changes the server or saved stellar state. */
public final class RenderOptions {
    public enum Quality {
        LOW(4, 0), BALANCED(8, 8), HIGH(16, 16);
        public final int lights;
        public final int shadowSteps;
        Quality(int lights, int shadowSteps) { this.lights = lights; this.shadowSteps = shadowSteps; }
    }

    private Quality quality = Quality.BALANCED;
    private String environment = "auto";
    private final EnvironmentProfiles profiles;

    private boolean lighting = true;
    private boolean bloom = true;
    private float bloomStrength = 0.65f;
    private float bloomThreshold = 1.0f;
    private float bloomRadius = 0.65f;
    private float exposure = 1.0f;
    private boolean shafts = true;
    private float cloudCover = -1;
    private boolean flashlight;
    private int selectedLights;

    /** Uses the current reloadable profiles for validation and command completion. */
    public RenderOptions(EnvironmentProfiles profiles) { this.profiles = profiles; }

    public Quality quality() { return quality; }
    public String environment() { return environment; }
    /** Explicit resource-pack mapping owns the Overworld scene before the built-in astronomical fallback. */
    public boolean astronomicalOverworld() {
        return environment.equals("auto") && profiles.get("minecraft:overworld") == null;
    }
    public boolean lighting() { return lighting; }
    public boolean bloom() { return bloom; }
    public float bloomStrength() { return bloomStrength; }
    public float bloomThreshold() { return bloomThreshold; }
    public float bloomRadius() { return bloomRadius; }
    public float exposure() { return exposure; }
    /** Whether cloud-shadowed aerial scattering is enabled; independent of optical bloom. */
    public boolean shafts() { return shafts; }
    /** Resolves the session override, or the weather/season coverage when set to auto. */
    public float cloudCover(float automatic) { return cloudCover < 0 ? automatic : cloudCover; }
    /** Final celestial display exposure shared by the flight and renderer controls; client thread only. */
    public void setExposure(float value) {
        if (!Float.isFinite(value) || value < 0.1f || value > 4.0f) {
            throw new IllegalArgumentException("Celestial exposure must be in 0.1..4");
        }
        exposure = value;
    }
    public boolean flashlight() { return flashlight; }
    public void reportLights(int count) { selectedLights = count; }

    /** Client editor controls share the same session-local options as commands. */
    public void toggleFlashlight() { flashlight = !flashlight; }
    public void cycleQuality() { quality = Quality.values()[(quality.ordinal() + 1) % Quality.values().length]; }
    public void cycleEnvironment() {
        environment = switch (environment) {
            case "auto" -> "space";
            case "space" -> "planet";
            case "planet" -> "off";
            default -> "auto";
        };
    }

    /** Registers client-local commands for profiles, budgets, flashlight and pass comparison. */
    public void registerCommands(RegisterClientCommandsEvent event) {
        var root = Commands.literal("astra-render");
        root.then(Commands.literal("environment").then(Commands.argument("name", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    builder.suggest("auto");
                    builder.suggest("off");
                    profiles.ids().forEach(id -> builder.suggest(id.toString()));
                    return builder.buildFuture();
                })
                .executes(context -> {
                    String requested = StringArgumentType.getString(context, "name");
                    try {
                        if (!requested.equals("auto") && !requested.equals("off") && profiles.get(requested) == null) {
                            context.getSource().sendFailure(Component.translatable("astraengine.render.invalid"));
                            return 0;
                        }
                    } catch (IllegalArgumentException invalid) {
                        context.getSource().sendFailure(Component.translatable("astraengine.render.invalid"));
                        return 0;
                    }
                    environment = requested;
                    return status(context.getSource());
                })));
        root.then(Commands.literal("quality").then(Commands.argument("name", StringArgumentType.word())
                .suggests((context, builder) -> {
                    for (var value : Quality.values()) { builder.suggest(value.name().toLowerCase(Locale.ROOT)); }
                    return builder.buildFuture();
                })
                .executes(context -> {
                    try {
                        quality = Quality.valueOf(StringArgumentType.getString(context, "name").toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException invalid) {
                        context.getSource().sendFailure(Component.translatable("astraengine.render.invalid"));
                        return 0;
                    }
                    return status(context.getSource());
                })));
        root.then(Commands.literal("flashlight").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
            flashlight = BoolArgumentType.getBool(context, "enabled"); return status(context.getSource());
        })));
        root.then(Commands.literal("lighting").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
            lighting = BoolArgumentType.getBool(context, "enabled"); return status(context.getSource());
        })));
        root.then(Commands.literal("bloom").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
            bloom = BoolArgumentType.getBool(context, "enabled"); return status(context.getSource());
        })));
        root.then(Commands.literal("bloom-strength").then(Commands.argument("value", FloatArgumentType.floatArg(0, 4))
                .executes(context -> {
                    bloomStrength = FloatArgumentType.getFloat(context, "value");
                    return status(context.getSource());
                })));
        root.then(Commands.literal("bloom-threshold").then(Commands.argument("value", FloatArgumentType.floatArg(0.1f, 16))
                .executes(context -> {
                    bloomThreshold = FloatArgumentType.getFloat(context, "value");
                    return status(context.getSource());
                })));
        root.then(Commands.literal("bloom-radius").then(Commands.argument("value", FloatArgumentType.floatArg(0, 1))
                .executes(context -> {
                    bloomRadius = FloatArgumentType.getFloat(context, "value");
                    return status(context.getSource());
                })));
        root.then(Commands.literal("exposure").then(Commands.argument("value", FloatArgumentType.floatArg(0.1f, 4))
                .executes(context -> {
                    setExposure(FloatArgumentType.getFloat(context, "value"));
                    return status(context.getSource());
                })));
        root.then(Commands.literal("status").executes(context -> status(context.getSource())));
        root.then(Commands.literal("shafts").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
            shafts = BoolArgumentType.getBool(context, "enabled"); return status(context.getSource());
        })));
        root.then(Commands.literal("cloud-cover")
                .then(Commands.literal("auto").executes(context -> { cloudCover = -1; return status(context.getSource()); }))
                .then(Commands.argument("value", FloatArgumentType.floatArg(0, 1)).executes(context -> {
                    cloudCover = FloatArgumentType.getFloat(context, "value"); return status(context.getSource());
                })));
        event.getDispatcher().register(root);
    }

    private int status(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("astraengine.render.status", environment, quality.name(),
                lighting, bloom, flashlight, selectedLights, quality.lights), false);
        source.sendSuccess(() -> Component.translatable("astraengine.render.hdr", bloomStrength,
                bloomThreshold, bloomRadius, exposure), false);
        source.sendSuccess(() -> Component.translatable("astraengine.render.clouds", shafts,
                cloudCover < 0 ? "auto" : Float.toString(cloudCover)), false);
        return 1;
    }
}
