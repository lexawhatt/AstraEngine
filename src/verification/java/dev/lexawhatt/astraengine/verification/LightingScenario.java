package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.lighting.CollectSceneLightsEvent;
import dev.lexawhatt.astraengine.client.lighting.LightVector;
import dev.lexawhatt.astraengine.client.lighting.SceneLight;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** A disposable native cave/sky fixture, including an API consumer and image-based light assertions. */
final class LightingScenario {
    private final Minecraft minecraft = Minecraft.getInstance();
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private int step;
    private int ticks;
    private boolean coloredLights;
    private boolean crowdedLights;
    private double darkLuminance;
    private double flashlightLuminance;
    private final Consumer<CollectSceneLightsEvent> lightListener = this::collectLights;

    LightingScenario() {
        NeoForge.EVENT_BUS.addListener(lightListener);
        AstraEngine.LOGGER.info("ASTRA_LIGHTING_GRAPHICS {} transparency={}",
                minecraft.options.graphicsMode().get(), Minecraft.useShaderTransparency());
    }

    private void collectLights(CollectSceneLightsEvent event) {
        if (!coloredLights) { return; }
        event.collector().add(SceneLight.point("verify:red", new LightVector(-2, 202, 3), new LightVector(1, 0.035, 0.01), 4, 12));
        event.collector().add(SceneLight.point("verify:blue", new LightVector(3, 202, 5), new LightVector(0.015, 0.15, 1), 4, 12));
        if (crowdedLights) {
            for (int i = 0; i < 14; i++) {
                event.collector().add(new SceneLight("verify:fill" + i, SceneLight.Kind.POINT,
                        new LightVector((i % 5) - 2, 202, 3 + (i % 3)), new LightVector(0, 0, 1),
                        new LightVector(0.15, 0.3, 0.6), 0.04f, 12, 0, 1, false));
            }
        }
        for (int i = 0; i < 40; i++) {
            event.collector().add(SceneLight.point("verify:far" + i, new LightVector(1000 + i, 200, 1000),
                    new LightVector(1, 1, 1), 1, 10));
        }
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        ticks++;
        switch (step) {
            case 0 -> {
                server(server -> {
                    var level = server.overworld();
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                    level.setDayTime(6000);
                    level.setWeatherParameters(100000, 0, false, false);
                    for (int x = -6; x <= 6; x++) {
                        for (int z = -5; z <= 10; z++) {
                            for (int y = 199; y <= 205; y++) {
                                boolean shell = x == -6 || x == 6 || z == -5 || z == 10 || y == 199 || y == 205;
                                level.setBlockAndUpdate(new BlockPos(x, y, z), shell ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                            }
                        }
                    }
                    for (int y = 200; y <= 202; y++) {
                        level.setBlockAndUpdate(new BlockPos(2, y, 6), Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                    }
                    server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, 0.5);
                });
                command("astra-render environment astraengine_verify:verification");
                command("astra-render bloom false");
                next();
            }
            case 1 -> {
                look(0, 0);
                if (ticks < 80) { return false; }
                darkLuminance = shot("01-cave-dark");
                command("astra-render flashlight true");
                next();
            }
            case 2 -> {
                look(0, 0);
                if (ticks < 40) { return false; }
                flashlightLuminance = shot("02-cave-flashlight");
                require(flashlightLuminance > darkLuminance + 0.025,
                        "Flashlight did not illuminate real opaque geometry: " + darkLuminance + " -> " + flashlightLuminance);
                command("astra-render flashlight false");
                coloredLights = true;
                next();
            }
            case 3 -> {
                look(0, 0);
                if (ticks < 40) { return false; }
                double colored = shot("03-cave-colored-lights");
                require(colored > darkLuminance + 0.01, "Event API lights did not illuminate the cave");
                command("astra-render quality high");
                command("astra-render bloom true");
                crowdedLights = true;
                next();
            }
            case 4 -> {
                if (ticks < 30) { return false; }
                shot("04-high-bloom");
                command("astra-render status");
                installInvalidPack();
                pending = minecraft.reloadResourcePacks();
                next();
            }
            case 5 -> {
                if (ticks < 40) { return false; }
                double reloaded = shot("05-after-rejected-profile");
                require(reloaded > darkLuminance + 0.01, "Invalid profile reload lost the working light setup");
                minecraft.getResourcePackRepository().setSelected(minecraft.getResourcePackRepository().getSelectedIds()
                        .stream().filter(id -> !id.equals("file/astra-invalid")).toList());
                pending = minecraft.reloadResourcePacks();
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 960, 540);
                command("astra-render quality low");
                next();
            }
            case 6 -> {
                if (ticks < 40) { return false; }
                shot("06-low-resized");
                command("astra-render status");
                coloredLights = false;
                crowdedLights = false;
                minecraft.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FABULOUS);
                minecraft.levelRenderer.allChanged();
                AstraEngine.LOGGER.info("ASTRA_LIGHTING_GRAPHICS {} transparency={}",
                        minecraft.options.graphicsMode().get(), Minecraft.useShaderTransparency());
                server(server -> server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 206, 0.5));
                command("astra-render quality balanced");
                next();
            }
            case 7 -> {
                look(22, -17);
                if (ticks < 60) { return false; }
                shot("07-ring-noon");
                server(server -> server.overworld().setDayTime(11800));
                next();
            }
            case 8 -> {
                look(22, -17);
                if (ticks < 40) { return false; }
                shot("08-ring-sunset");
                server(server -> server.overworld().setDayTime(18000));
                next();
            }
            case 9 -> {
                look(22, -17);
                if (ticks < 40) { return false; }
                shot("09-ring-night");
                command("astra-render environment auto");
                next();
            }
            case 10 -> {
                if (ticks < 30) { return false; }
                shot("10-vanilla-restored");
                command("astra visit alpha");
                next();
            }
            case 11 -> {
                look(-14, -6);
                if (!minecraft.level.dimension().location().toString().equals("astraengine:alpha") || ticks < 120) { return false; }
                look(-14, -6);
                shot("11-space-lighting");
                command("astra-render lighting false");
                command("astra-render bloom false");
                next();
            }
            case 12 -> {
                look(-14, -6);
                if (ticks < 30) { return false; }
                shot("12-space-passes-off");
                command("astra leave");
                next();
            }
            case 13 -> {
                if (ticks < 60) { return false; }
                require(minecraft.level.dimension().location().toString().equals("minecraft:overworld"), "Could not leave system");
                shot("13-returned-home");
                Files.writeString(minecraft.gameDirectory.toPath().resolve("lighting-measurements.txt"),
                        "Central opaque ROI mean luminance: dark=" + darkLuminance + ", flashlight=" + flashlightLuminance + "\n");
                NeoForge.EVENT_BUS.unregister(lightListener);
                return true;
            }
            default -> throw new IllegalStateException("Unexpected lighting fixture step");
        }
        return false;
    }

    private void installInvalidPack() throws Exception {
        var root = minecraft.gameDirectory.toPath().resolve("resourcepacks/astra-invalid");
        var profile = root.resolve("assets/astraengine_verify/environments/verification.json");
        Files.createDirectories(profile.getParent());
        int format = net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
        Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":" + format + ",\"description\":\"Invalid verification profile\"}}");
        Files.writeString(profile, "{\"version\":1,\"planetary\":true,\"exposure\":-10}");
        minecraft.getResourcePackRepository().reload();
        var selected = new java.util.ArrayList<>(minecraft.getResourcePackRepository().getSelectedIds());
        selected.add("file/astra-invalid");
        minecraft.getResourcePackRepository().setSelected(selected);
    }

    private void command(String command) { minecraft.player.connection.sendCommand(command); }
    private void look(float yaw, float pitch) { minecraft.player.setYRot(yaw); minecraft.player.setXRot(pitch); }
    private void next() { step++; ticks = 0; }

    private void server(Consumer<MinecraftServer> action) {
        var server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }

    private double shot(String name) throws Exception {
        minecraft.gui.getChat().clearMessages(false);
        var path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        double sum = 0;
        int count = 0;
        try (NativeImage frame = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            frame.writeToFile(path);
            // Excludes HUD/chat/hand and the crosshair; central wall/floor lies inside this ROI.
            for (int y = frame.getHeight() / 3; y < frame.getHeight() / 2; y++) {
                for (int x = frame.getWidth() / 3; x < frame.getWidth() * 2 / 3; x++) {
                    int pixel = frame.getPixelRGBA(x, y);
                    sum += ((pixel & 255) * 0.2126 + ((pixel >> 8) & 255) * 0.7152 + ((pixel >> 16) & 255) * 0.0722) / 255.0;
                    count++;
                }
            }
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after " + name);
        AstraEngine.LOGGER.info("ASTRA_LIGHTING_SCREENSHOT {} roi_luminance={}", name, sum / count);
        return sum / count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
