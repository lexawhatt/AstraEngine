package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.client.solar.AstralOverworldEffects;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot.Phase;
import dev.lexawhatt.astraengine.server.SolarState;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Native default-Overworld sky and persisted, server-owned solar-event fixture in a disposable world. */
final class SolarScenario {
    private static final BlockPos MARKER = new BlockPos(0, 199, 0);
    private final Minecraft minecraft = Minecraft.getInstance();
    private final boolean restart;
    private final StringBuilder videoTimes = new StringBuilder("frame,elapsed_ms,phase,phase_ticks,active_ticks\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private StellarEvolutionSnapshot snapshot;
    private StellarEvolutionSnapshot paused;
    private RocketController rocket;
    private Properties checkpoint;
    private int step;
    private int ticks;
    private int samplingTicks;
    private int videoFrames;
    private int checkpointStage;
    private long videoStarted;
    private boolean moonView;
    private boolean flashCaptured;
    private boolean ejectaCaptured;
    private boolean lateEjectaCaptured;
    private int wallX;
    private long renderedFrames;
    private long capturedVideoFrame = -1;
    private long approachStartEpoch;
    private long approachOwnedEpoch;
    private long approachCompletedFrame = -1;
    private int approachCompletedTick;
    private boolean approachObserved;
    private final Consumer<RenderFrameEvent.Post> frameListener = event -> {
        if (minecraft.level != null && minecraft.getOverlay() == null) { this.renderedFrames++; }
    };

    SolarScenario(boolean restart) {
        this.restart = restart;
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, frameListener);
        minecraft.options.hideGui = true;
        minecraft.options.fov().set(70);
        AstraEngine.LOGGER.info("ASTRA_SOLAR_GRAPHICS {} transparency={} restart={}",
                minecraft.options.graphicsMode().get(), Minecraft.useShaderTransparency(), restart);
        server(server -> { });
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        ticks++;
        samplingTicks++;
        require(ticks < 2400, "Solar fixture step timed out");
        if (minecraft.level.dimension().equals(Level.OVERWORLD)) { faceCelestial(moonView); }
        if (restart && (snapshot.phase() == Phase.COLLAPSING || snapshot.phase() == Phase.SUPERNOVA)
                && samplingTicks % 4 == 0 && videoFrames < 160 && capturedVideoFrame != renderedFrames) {
            videoFrame();
        }
        boolean complete = restart ? restartTick() : createTick();
        if (!complete && pending.isDone() && samplingTicks % 4 == 0) { server(server -> { }); }
        if (complete) { NeoForge.EVENT_BUS.unregister(frameListener); }
        return complete;
    }

    private boolean createTick() throws Exception {
        switch (step) {
            case 0 -> {
                require(snapshot.remaining() == StellarEvolutionSnapshot.CAPACITY && !snapshot.running(),
                        "Solar depletion started without a command");
                command("astra-render environment auto");
                server(this::prepareWorld);
                GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
                next();
            }
            case 1 -> {
                if (ticks < 50) { return false; }
                shot("01-overworld-day");
                server(server -> server.overworld().setDayTime(12600));
                next();
            }
            case 2 -> {
                if (ticks < 35) { return false; }
                require(minecraft.level.getDayTime() == 12600, "Sunset time did not reach the client before capture");
                shot("02-overworld-sunset");
                moonView = true;
                server(server -> server.overworld().setDayTime(18000));
                next();
            }
            case 3 -> {
                if (ticks < 35) { return false; }
                shot("03-overworld-night-moon");
                moonView = false;
                server(server -> {
                    server.overworld().setDayTime(1000);
                    server.overworld().setWeatherParameters(0, 10000, true, true);
                });
                next();
            }
            case 4 -> {
                if (ticks < 140) { return false; }
                require(minecraft.level.getRainLevel(1) > 0.9, "Rain did not reach the native client");
                shot("04-overworld-storm");
                server(server -> server.overworld().setWeatherParameters(10000, 0, false, false));
                next();
            }
            case 5 -> {
                if (ticks < 140) { return false; }
                require(minecraft.level.getRainLevel(1) < 0.1, "Storm did not clear for the solar scenario");
                require(snapshot.activeTicks() == 0, "Default Overworld observing unexpectedly drained the Sun");
                SpaceVector direction = sunDirection();
                wallX = direction.x() > 0 ? 12 : -12;
                server(server -> wall(server, true));
                next();
            }
            case 6 -> {
                if (ticks < 35) { return false; }
                require(minecraft.player.pick(40, 1, false).getType() == net.minecraft.world.phys.HitResult.Type.BLOCK,
                        "Opaque wall does not occlude the ray toward the solar disk");
                shot("05-sun-occluded-by-real-blocks");
                server(server -> wall(server, false));
                next();
            }
            case 7 -> {
                if (ticks < 25) { return false; }
                shot("06-clear-before-drain");
                command("astra sun demo 30");
                next();
            }
            case 8 -> {
                if (!snapshot.running() || snapshot.extracted() < 150_000) { return false; }
                require(snapshot.remaining() + snapshot.extracted() == StellarEvolutionSnapshot.CAPACITY,
                        "Gradual drain violated resource conservation");
                require(snapshot.phase() == Phase.STABLE, "Stable drain sample skipped directly to an unstable phase");
                shot("07-gradual-stable-drain");
                next();
            }
            case 9 -> {
                if (snapshot.phase() != Phase.DISTENDED || snapshot.phaseTicks() < 20) { return false; }
                shot("08-distended-sun");
                command("astra sun pause");
                next();
            }
            case 10 -> {
                if (snapshot.running() || ticks < 15) { return false; }
                require(snapshot.phase() == Phase.DISTENDED, "Pause did not capture a meaningful mid-event state");
                paused = snapshot;
                next();
            }
            case 11 -> {
                if (ticks < 45) { return false; }
                require(snapshot.equals(paused), "Paused solar state advanced while observed");
                shot("09-paused-distended");
                pending = minecraft.reloadResourcePacks();
                next();
            }
            case 12 -> {
                if (ticks < 35) { return false; }
                require(snapshot.equals(paused), "Resource reload changed server solar evolution");
                shot("10-paused-after-resource-reload");
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 960, 540);
                next();
            }
            case 13 -> {
                if (ticks < 35) { return false; }
                require(minecraft.getWindow().getWidth() == 960 && minecraft.getWindow().getHeight() == 540,
                        "Solar framebuffer resize did not finish");
                shot("11-paused-resized");
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 1280, 720);
                next();
            }
            case 14 -> {
                if (ticks < 35) { return false; }
                tap(GLFW.GLFW_KEY_R);
                next();
            }
            case 15 -> {
                if (!minecraft.level.dimension().location().toString().equals("astraengine:flight") || ticks < 45) {
                    return false;
                }
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 16 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 15) { return false; }
                rocket = map.controller();
                require(rocket.currentSystem().id().equals("sol"), "Shared solar verification entered a different system");
                click(Component.translatable("astraengine.map.local").getString());
                click("Sun");
                approachStartEpoch = rocket.snapshot().navigationEpoch();
                click(Component.translatable("astraengine.map.approach").getString());
                next();
            }
            case 17 -> {
                if (rocket.snapshot().approaching()) {
                    require(rocket.snapshot().navigationEpoch() != approachStartEpoch
                                    && rocket.snapshot().approachBodyId().equals("sun"),
                            "Sun approach did not receive its own navigation ownership");
                    approachObserved = true;
                    approachOwnedEpoch = rocket.snapshot().navigationEpoch();
                    return false;
                }
                if (!approachObserved) {
                    require(ticks < 120, "Map request did not start a Sun approach");
                    return false;
                }
                require(rocket.snapshot().navigationEpoch() != approachOwnedEpoch,
                        "Completed Sun approach did not release its navigation epoch");
                if (approachCompletedFrame < 0) {
                    approachCompletedFrame = renderedFrames;
                    approachCompletedTick = ticks;
                    return false;
                }
                if (ticks < approachCompletedTick + 20 || renderedFrames < approachCompletedFrame + 8) { return false; }
                require(snapshot.equals(paused), "Rocket approach changed the paused solar state");
                SpaceVector towardSun = rocket.currentSystem().bodies().getFirst().positionAt(rocket.timeSeconds())
                        .subtract(rocket.visualPosition()).normalized();
                var actualLook = minecraft.gameRenderer.getMainCamera().getLookVector();
                double facing = towardSun.dot(new SpaceVector(actualLook.x, actualLook.y, actualLook.z).normalized());
                double radiusRatio = rocket.currentSystem().bodies().getFirst().positionAt(rocket.timeSeconds())
                        .distance(rocket.snapshot().position()) / rocket.currentSystem().bodies().getFirst().radiusMeters();
                require(Math.abs(radiusRatio - 4) < 0.2, "Sun approach missed its four-radius observation point");
                require(facing > 0.995, "Shared solar-state camera did not settle toward the approached Sun: dot=" + facing
                        + ", renderedFrames=" + (renderedFrames - approachCompletedFrame));
                shot("12-shared-solar-state-in-rocket");
                tap(GLFW.GLFW_KEY_R);
                next();
            }
            case 18 -> {
                if (!minecraft.level.dimension().equals(Level.OVERWORLD) || ticks < 35) { return false; }
                require(snapshot.equals(paused), "Returning from Rocket mode changed the paused event");
                server(this::verifyWorld);
                next();
            }
            case 19 -> {
                if (checkpointStage == 0) {
                    command("astra-render environment off");
                    checkpointStage = 1; ticks = 0; return false;
                }
                if (checkpointStage == 1) {
                    if (ticks < 30) { return false; }
                    shot("12a-vanilla-environment-off");
                    command("astra-render environment auto");
                    minecraft.options.hideGui = false;
                    checkpointStage = 2; ticks = 0; return false;
                }
                if (checkpointStage == 2) {
                    if (ticks < 30) { return false; }
                    shot("12b-paused-status-hud");
                    minecraft.options.hideGui = true;
                    checkpointStage = 3; ticks = 0; return false;
                }
                if (ticks < 20) { return false; }
                require(snapshot.equals(paused), "Presentation fallback or HUD controls changed the paused solar state");
                Properties values = new Properties();
                values.setProperty("snapshot", snapshot.toString());
                values.setProperty("remaining", Long.toString(snapshot.remaining()));
                values.setProperty("phase", snapshot.phase().name());
                values.setProperty("active_ticks", Long.toString(snapshot.activeTicks()));
                StringWriter text = new StringWriter();
                values.store(text, "Paused solar event in disposable Overworld; exact immutable snapshot");
                Files.writeString(checkpointPath(), text.toString());
                shot("13-paused-checkpoint-overworld");
                return true;
            }
            default -> throw new IllegalStateException("Unexpected solar fixture step " + step);
        }
        return false;
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                if (ticks < 45) { return false; }
                checkpoint = new Properties();
                checkpoint.load(new StringReader(Files.readString(checkpointPath())));
                require(snapshot.toString().equals(checkpoint.getProperty("snapshot")),
                        "Solar snapshot changed during offline time or restart");
                require(!snapshot.running() && snapshot.phase() == Phase.DISTENDED,
                        "Restart did not restore the explicitly paused stellar event");
                paused = snapshot;
                server(this::verifyWorld);
                shot("14-restarted-paused-distended");
                next();
            }
            case 1 -> {
                if (ticks < 45) { return false; }
                require(snapshot.equals(paused), "Restarted paused state advanced with a live observer");
                command("astra sun resume");
                next();
            }
            case 2 -> {
                if (!snapshot.running() || snapshot.activeTicks() <= paused.activeTicks() + 15) { return false; }
                require(snapshot.remaining() < paused.remaining(), "Resumed demo did not continue gradual extraction");
                shot("15-resumed-gradual-drain");
                next();
            }
            case 3 -> {
                if (snapshot.phase() != Phase.CRITICAL || snapshot.phaseTicks() < 20) { return false; }
                shot("16-critical-envelope");
                next();
            }
            case 4 -> {
                if (snapshot.phase() != Phase.COLLAPSING || snapshot.phaseTicks() < 25) { return false; }
                require(snapshot.remaining() == 0 && snapshot.extracted() == StellarEvolutionSnapshot.CAPACITY,
                        "Collapse did not begin at complete extraction");
                shot("17-collapsing-core");
                next();
            }
            case 5 -> {
                if (snapshot.phase() != Phase.SUPERNOVA) { return false; }
                if (!flashCaptured && snapshot.phaseTicks() >= 4) {
                    shot("18-supernova-flash");
                    flashCaptured = true;
                }
                if (!ejectaCaptured && snapshot.phaseTicks() >= 70) {
                    shot("19-supernova-shock-and-ejecta");
                    ejectaCaptured = true;
                }
                if (!lateEjectaCaptured && snapshot.phaseTicks() >= 220) {
                    shot("20-supernova-expanding-remnant");
                    lateEjectaCaptured = true;
                }
                if (lateEjectaCaptured) { next(); }
            }
            case 6 -> {
                if (snapshot.phase() != Phase.REMNANT || ticks < 110) { return false; }
                require(!snapshot.running() && snapshot.remaining() == 0 && flashCaptured && ejectaCaptured,
                        "Supernova did not complete the bounded sequence");
                verifyRemnantLightmap();
                shot("21-persistent-remnant");
                paused = snapshot;
                server(this::verifyWorld);
                next();
            }
            case 7 -> {
                if (ticks < 50) { return false; }
                require(snapshot.equals(paused), "Completed remnant kept advancing its event clocks");
                require(videoFrames >= 40, "Too few real rendered frames captured for the solar-event sequence");
                Files.writeString(minecraft.gameDirectory.toPath().resolve("solar-video-timing.csv"), videoTimes.toString());
                Files.writeString(minecraft.gameDirectory.toPath().resolve("solar-completed.txt"), snapshot.toString() + "\n");
                AstraEngine.LOGGER.info("ASTRA_SOLAR_VIDEO frames={} directory=evidence/solar-sequence", videoFrames);
                command("astra sun reset");
                next();
            }
            case 8 -> {
                if (snapshot.phase() != Phase.STABLE || snapshot.cycle() <= paused.cycle() || ticks < 30) { return false; }
                require(!snapshot.running() && snapshot.remaining() == StellarEvolutionSnapshot.CAPACITY
                                && snapshot.extracted() == 0 && snapshot.revision() > paused.revision(),
                        "Explicit reset did not create a healthy, paused, newer cycle");
                shot("22-reset-healthy-sun");
                server(this::verifyWorld);
                next();
            }
            case 9 -> { return true; }
            default -> throw new IllegalStateException("Unexpected solar restart step " + step);
        }
        return false;
    }

    private void prepareWorld(MinecraftServer server) {
        var level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.setDayTime(1000);
        level.setWeatherParameters(100000, 0, false, false);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
        for (int z : new int[] {-6, 6}) {
            level.setBlockAndUpdate(new BlockPos(-6, 200, z), Blocks.GLOWSTONE.defaultBlockState());
        }
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, 0.5);
    }

    private void wall(MinecraftServer server, boolean enabled) {
        for (int z = -7; z <= 7; z++) {
            for (int y = 199; y <= 222; y++) {
                server.overworld().setBlockAndUpdate(new BlockPos(wallX, y, z),
                        enabled ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
    }

    private void verifyWorld(MinecraftServer server) {
        require(server.overworld().getBlockState(MARKER).is(Blocks.DIAMOND_BLOCK), "Solar evolution damaged the real fixture marker");
        require(server.overworld().getBlockState(new BlockPos(-6, 200, -6)).is(Blocks.GLOWSTONE),
                "Solar evolution damaged a local block light");
        require(server.getPlayerList().getPlayers().getFirst().isAlive(), "Solar visual event killed its observer");
    }

    private void verifyRemnantLightmap() {
        require(minecraft.level.getDayTime() == 1000, "Remnant lightmap assertion requires its fixed daytime fixture");
        Vector3f blockInput = new Vector3f(0.7f, 0.3f, 0.1f);
        Vector3f blockOnly = new Vector3f(blockInput);
        minecraft.level.effects().adjustLightmapColors(minecraft.level, 1, 1, 1, 0, 0, 0, blockOnly);
        require(blockOnly.distance(blockInput) < 0.000001f, "Solar remnant dimmed local block-light emission");
        Vector3f skyOnly = new Vector3f(0.96f, 0.96f, 0.96f);
        minecraft.level.effects().adjustLightmapColors(minecraft.level, 1, 1, 1, 1, 0, 15, skyOnly);
        require(skyOnly.x >= 0 && skyOnly.x < 0.1f && skyOnly.y >= 0 && skyOnly.y < 0.1f
                        && skyOnly.z >= 0 && skyOnly.z < 0.1f,
                "Solar remnant did not attenuate the independent sky-light contribution: " + skyOnly);
        AstraEngine.LOGGER.info("ASTRA_SOLAR_LIGHTMAP effects={} blockInput={} blockResult={} skyInput=(0.96,0.96,0.96) skyResult={}",
                minecraft.level.effects().getClass().getName(), blockInput, blockOnly, skyOnly);
    }

    private SpaceVector sunDirection() {
        if (minecraft.level.effects() instanceof AstralOverworldEffects effects) {
            return effects.skyState().sample(minecraft.level, 1).sunDirection();
        }
        double angle = minecraft.level.getSunAngle(1);
        return new SpaceVector(-Math.sin(angle), Math.cos(angle), 0);
    }

    private void faceCelestial(boolean moon) {
        SpaceVector direction = sunDirection().multiply(moon ? -1 : 1);
        minecraft.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x(), direction.z())));
        minecraft.player.setXRot((float) -Math.toDegrees(Math.asin(direction.y())));
    }

    private void click(String label) {
        require(minecraft.screen instanceof CosmosMapScreen, "Solar shared-state interaction requires the cosmos map");
        Button button = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.getMessage().getString().equals(label)
                        || value.getMessage().getString().equals("> " + label))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing solar verification map button: " + label));
        require(button.active && button.visible, "Solar verification map button is unavailable");
        var screen = minecraft.screen;
        double x = button.getX() + button.getWidth() / 2.0;
        double y = button.getY() + button.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Solar verification map click was not handled");
        screen.mouseReleased(x, y, 0);
    }

    private void shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        try (NativeImage frame = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { frame.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after " + name);
        AstraEngine.LOGGER.info("ASTRA_SOLAR_SCREENSHOT {} world={} dayTime={} sunAngleRadians={} phase={} remaining={} activeTicks={}",
                name, minecraft.level.dimension().location(), minecraft.level.getDayTime(), minecraft.level.getSunAngle(1),
                snapshot.phase(), snapshot.remaining(), snapshot.activeTicks());
    }

    private void videoFrame() throws Exception {
        capturedVideoFrame = renderedFrames;
        if (videoFrames == 0) { videoStarted = System.nanoTime(); }
        String name = String.format(java.util.Locale.ROOT, "solar-sequence/frame-%04d", videoFrames);
        shot(name);
        videoTimes.append(videoFrames).append(',').append((System.nanoTime() - videoStarted) / 1_000_000)
                .append(',').append(snapshot.phase()).append(',').append(snapshot.phaseTicks())
                .append(',').append(snapshot.activeTicks()).append('\n');
        videoFrames++;
    }

    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> {
            action.accept(server);
            snapshot = SolarState.get(server).snapshot();
        }, server);
    }

    private Path checkpointPath() { return minecraft.gameDirectory.toPath().resolve("solar-checkpoint.properties"); }
    private void command(String command) { minecraft.player.connection.sendCommand(command); }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }

    private void next() {
        AstraEngine.LOGGER.info("ASTRA_SOLAR_STEP {} complete phase={}", step, snapshot.phase());
        step++;
        ticks = 0;
    }

    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (solar step " + step + ", ticks " + ticks + ")"); }
    }
}
