package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.render.RenderOptions;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Actual new-preset cave geometry, native dark/lit images and host keyboard/resource lifecycle. */
final class UndergroundScenario {
    private final Minecraft game = Minecraft.getInstance();
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private final StringBuilder evidence = new StringBuilder();
    private RenderOptions options;
    private KeyMapping key;
    private BlockPos floor;
    private BlockState originalFloor;
    private double dark;
    private int step;
    private int ticks;

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        require(++ticks < 1800, "Underground fixture stalled at " + step);
        switch (step) {
            case 0 -> {
                options = (RenderOptions) field(game.level.effects(), "options");
                key = Arrays.stream(game.options.keyMappings).filter(k -> k.getName().equals("key.astraengine.flashlight"))
                        .findFirst().orElseThrow();
                game.options.hideGui = true;
                game.options.bobView().set(false);
                game.options.gamma().set(1.0);
                game.options.renderDistance().set(6);
                game.options.framerateLimit().set(30);
                server(server -> {
                    var level = server.overworld();
                    var generator = (EarthChunkGenerator) level.getChunkSource().getGenerator();
                    require(generator.caves().version() == 1, "Fresh Earth did not enable caves");
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                    level.setDayTime(6000);
                    var player = server.getPlayerList().getPlayers().getFirst();
                    // This point is inside a generated chamber, not an excavated verification room.
                    var probe = new BlockPos(8, 40, 8);
                    require(level.getBlockState(probe).isAir(), "Actual carver did not open the sampled chamber");
                    int y = 40;
                    while (y > -200 && level.getBlockState(new BlockPos(8, y, 8)).isAir()) { y--; }
                    floor = new BlockPos(8, y, 8);
                    originalFloor = level.getBlockState(floor);
                    int ceiling = 40;
                    while (ceiling < 300 && level.getBlockState(new BlockPos(8, ceiling, 8)).isAir()) { ceiling++; }
                    require(ceiling - y > 60, "Generated cavern has no vertical scale");
                    evidence.append("floor=").append(floor).append(" ceiling=").append(ceiling).append('\n');
                    player.teleportTo(level, 8.5, y + 5, 8.5, 35, 18);
                    player.getAbilities().flying = true; player.onUpdateAbilities();
                    require(level.getBrightness(LightLayer.SKY, BlockPos.containing(player.position())) == 0,
                            "Fixture is exposed to daylight");
                });
                next();
            }
            case 1 -> {
                if (ticks < 100 || !game.isWindowActive()) { return false; }
                require(!options.flashlight(), "Flashlight unexpectedly enabled on login");
                int unlit = unlitTexel();
                require(unlit <= 9, "Unlit underground lightmap retains gamma-amplified ambient: " + unlit);
                var emission = new Vector3f(.2f, .3f, .4f);
                game.level.effects().adjustLightmapColors(game.level, 0, 1, 1.5f, 0, 15, 0, emission);
                require(emission.equals(new Vector3f(.2f, .3f, .4f)), "Cave correction changed enclosed block emission");
                dark = shot("01-dark");
                require(dark < .04, "Unlit cave is visibly bright: " + dark);
                tap(GLFW.GLFW_KEY_F8); next();
            }
            case 2 -> {
                if (ticks < 30) { return false; }
                require(options.flashlight(), "F8 did not enable the real renderer light");
                double lit = shot("02-flashlight");
                require(lit > dark + .025, "Ordinary Earth flashlight did not illuminate geometry: " + lit + " / " + dark);
                key.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F6)); KeyMapping.resetMapping();
                tap(GLFW.GLFW_KEY_F8); next();
            }
            case 3 -> {
                if (ticks < 3) { return false; }
                require(options.flashlight(), "Rebinding retained the old key");
                tap(GLFW.GLFW_KEY_F6); next();
            }
            case 4 -> {
                if (ticks < 3) { return false; }
                require(!options.flashlight(), "Rebound key did not turn the lamp off");
                game.setScreen(new ChatScreen("")); tap(GLFW.GLFW_KEY_F6); next();
            }
            case 5 -> {
                if (ticks < 3) { return false; }
                require(!options.flashlight(), "Menu key toggled the lamp");
                game.setScreen(null); next();
            }
            case 6 -> {
                if (ticks < 5) { return false; }
                require(!options.flashlight(), "Menu key replayed after close");
                server(server -> server.overworld().setBlockAndUpdate(floor, Blocks.GLOWSTONE.defaultBlockState()));
                next();
            }
            case 7 -> {
                if (ticks < 50) { return false; }
                double lamp = shot("03-block-emission");
                require(lamp > dark + .003, "Host local lamp stopped illuminating the cave: " + lamp);
                server(server -> {
                    server.overworld().setBlockAndUpdate(floor, originalFloor);
                    server.getPlayerList().getPlayers().getFirst().addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 4000));
                });
                next();
            }
            case 8 -> {
                if (ticks < 40) { return false; }
                require(unlitTexel() > 200, "Night vision was darkened or produced invalid lightmap values");
                require(shot("04-night-vision-geometry") > dark + .1, "Night vision did not expose actual cave geometry");
                server(server -> server.getPlayerList().getPlayers().getFirst().removeEffect(MobEffects.NIGHT_VISION));
                tap(GLFW.GLFW_KEY_F6); next();
            }
            case 9 -> {
                if (ticks < 10) { return false; }
                require(options.flashlight(), "Rebound switch failed after night vision");
                pending = game.reloadResourcePacks(); next();
            }
            case 10 -> {
                if (ticks < 30) { return false; }
                require(options.flashlight() && shot("05-reload-flashlight") > dark + .025,
                        "Resource reload lost the enabled flashlight");
                key.setKey(key.getDefaultKey()); KeyMapping.resetMapping();
                game.options.hideGui = false;
                game.level.disconnect(); game.disconnect();
                require(!options.flashlight(), "Disconnect retained the flashlight");
                Files.writeString(game.gameDirectory.toPath().resolve("evidence/underground.txt"), evidence
                        + "key=F8; rebound=F6; menus=ignored; reload=retained; logout=off\n",
                        StandardOpenOption.CREATE_NEW);
                return true;
            }
            default -> throw new IllegalStateException("Unknown underground verification step");
        }
        return false;
    }

    private int unlitTexel() throws Exception {
        NativeImage pixels = (NativeImage) field(game.gameRenderer.lightTexture(), "lightPixels");
        return pixels.getPixelRGBA(0, 0) & 255;
    }
    private void next() {
        AstraEngine.LOGGER.info("ASTRA_VERIFY_UNDERGROUND step={} ticks={}", step, ticks);
        step++; ticks = 0;
    }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void tap(int code) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(code)); }
    private double shot(String name) throws Exception {
        var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(directory.resolve(name + ".png"));
            double sum = 0; int count = 0, greenLeak = 0, skyLeak = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int color = image.getPixelRGBA(x, y), r = color & 255, g = color >> 8 & 255, b = color >> 16 & 255;
                    if (g > 70 && g > r * 1.8 && g > b * 1.8) { greenLeak++; }
                    if (b > 130 && b > r * 1.3 && b > g * 1.1) { skyLeak++; }
                }
            }
            require(skyLeak < 1000, "Blue sky/fog leaked through underground far plane: " + skyLeak);
            require(greenLeak < 1000, "Exterior green LOD leaked through underground far plane: " + greenLeak);
            for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
                for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                    int color = image.getPixelRGBA(x, y);
                    sum += ((color & 255) * .2126 + (color >> 8 & 255) * .7152 + (color >> 16 & 255) * .0722) / 255;
                    count++;
                }
            }
            double mean = sum / count;
            evidence.append(name).append(" mean=").append(mean).append(" unlitTexel=").append(unlitTexel()).append('\n');
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Cave capture raised a GL error");
            return mean;
        }
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
