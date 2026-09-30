package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.AstraSystems;
import dev.lexawhatt.astraengine.api.ExtractionResult;
import dev.lexawhatt.astraengine.api.StellarStage;
import dev.lexawhatt.astraengine.api.SystemSnapshot;
import dev.lexawhatt.astraengine.client.editor.SceneEditorScreen;
import dev.lexawhatt.astraengine.client.editor.ShaderEditorScreen;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.UniverseAtlasScreen;
import dev.lexawhatt.astraengine.network.SystemSnapshotReceivedEvent;
import dev.lexawhatt.astraengine.network.SurfacePayload;
import dev.lexawhatt.astraengine.network.SurfaceReceivedEvent;
import dev.lexawhatt.astraengine.server.SystemWorlds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Drives an isolated native Minecraft fixture. No verification code is shipped in the mod jar. */
@Mod(value = "astraengine_verify", dist = Dist.CLIENT)
public final class ClientScenario {
    private static final UUID EXTRACTION = UUID.fromString("dea00000-0000-4000-8000-000000000001");
    private static final BlockPos MARKER = new BlockPos(6, 79, 6);
    private final Minecraft minecraft = Minecraft.getInstance();
    private final String phase = System.getProperty("astraengine.verify.phase", "create");
    private final long startedAt = System.nanoTime();
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private SystemSnapshot received;
    private SystemSnapshot pausedAlpha;
    private int step;
    private int ticks;
    private boolean opened;
    private boolean onboardingContinued;
    private boolean experimentalConfirmationContinued;
    private boolean finished;
    private LightingScenario lightingScenario;
    private EditorScenario editorScenario;
    private CosmosScenario cosmosScenario;
    private SolarScenario solarScenario;
    private CameraScenario cameraScenario;
    private SolarAudioScenario solarAudioScenario;
    private CelestialScenario celestialScenario;
    private ApproachScenario approachScenario;
    private CelestialApiScenario celestialApiScenario;
    private GalacticScenario galacticScenario;
    private AtlasScenario atlasScenario;
    private ShipVisualScenario shipVisualScenario;
    private SeasonalScenario seasonalScenario;
    private SolarCloudScenario solarCloudScenario;
    private VolumetricScenario volumetricScenario;
    private RenderCompatibilityScenario renderCompatibilityScenario;
    private CelestialPolishScenario celestialPolishScenario;
    private SurfaceScenario surfaceScenario;
    private TerrainScenario terrainScenario;
    private SurfacePayload latestSurface;

    public ClientScenario() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener((SystemSnapshotReceivedEvent event) -> received = event.payload().snapshot());
        NeoForge.EVENT_BUS.addListener((SurfaceReceivedEvent event) -> latestSurface = event.payload());
    }

    private void tick(ClientTickEvent.Post event) {
        if (finished) { return; }
        try {
            require((System.nanoTime() - startedAt) < (phase.startsWith("surface-") || phase.startsWith("terrain-") ? 900_000_000_000L
                    : phase.startsWith("seasonal") || phase.equals("volumetric")
                    || phase.equals("render-compat") || phase.equals("celestial-polish")
                    ? 600_000_000_000L : 240_000_000_000L),
                    "Native fixture timed out at step " + step);
            if (!opened && !onboardingContinued && minecraft.getOverlay() == null
                    && minecraft.screen instanceof AccessibilityOnboardingScreen screen) {
                Button proceed = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> button.getMessage().getString().equals(CommonComponents.GUI_CONTINUE.getString()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("Onboarding Continue button is missing"));
                require(proceed.active && proceed.visible, "Onboarding Continue button is unavailable");
                onboardingContinued = true;
                double x = proceed.getX() + proceed.getWidth() / 2.0;
                double y = proceed.getY() + proceed.getHeight() / 2.0;
                require(screen.mouseClicked(x, y, 0), "Onboarding Continue button did not handle its click");
                screen.mouseReleased(x, y, 0);
                AstraEngine.LOGGER.info("ASTRA_VERIFY_ONBOARDING_CONTINUED");
                return;
            }
            if (opened && !experimentalConfirmationContinued
                    && (phase.equals("cosmos-restart") || phase.equals("solar-restart") || phase.equals("camera-restart")
                            || phase.equals("celestial-api-restart") || phase.equals("galactic-restart") || phase.equals("atlas-restart")
                            || phase.equals("seasonal-restart") || phase.equals("surface-restart") || phase.equals("surface-recover")
                            || phase.equals("surface-upgrade") || phase.equals("terrain-restart"))
                    && minecraft.player == null && minecraft.getOverlay() == null
                    && minecraft.screen instanceof BackupConfirmScreen screen
                    && screen.getTitle().getString().equals(Component.translatable("selectWorld.backupQuestion.experimental").getString())) {
                // This verification-only flow can reopen only its successfully completed disposable fixture.
                Path fixture = minecraft.gameDirectory.toPath();
                if (phase.equals("surface-upgrade")) {
                    SurfaceScenario.upgradeManifest(fixture);
                } else {
                    String scenario = phase.equals("surface-recover") ? "surface-interrupt"
                            : phase.substring(0, phase.length() - "-restart".length());
                    String completedPhase = phase.equals("surface-restart") ? "surface-create"
                            : phase.equals("terrain-restart") ? "terrain-create" : scenario;
                    require(Files.isRegularFile(fixture.resolve("verified-" + completedPhase + ".txt"))
                                    && Files.isRegularFile(fixture.resolve(scenario + "-checkpoint.properties"))
                                    && Files.isRegularFile(fixture.resolve("saves/first-slice/level.dat")),
                            "Experimental confirmation is restricted to a completed verification world");
                }
                Button proceed = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> button.getMessage().getString().equals(
                                Component.translatable("selectWorld.backupJoinSkipButton").getString()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("Experimental fixture Continue button is missing"));
                require(proceed.active && proceed.visible, "Experimental fixture Continue button is unavailable");
                shot("00-experimental-reopen-confirmation");
                experimentalConfirmationContinued = true;
                double x = proceed.getX() + proceed.getWidth() / 2.0;
                double y = proceed.getY() + proceed.getHeight() / 2.0;
                require(screen.mouseClicked(x, y, 0), "Experimental fixture Continue did not handle its click");
                screen.mouseReleased(x, y, 0);
                AstraEngine.LOGGER.info("ASTRA_VERIFY_EXPERIMENTAL_FIXTURE_CONTINUED");
                return;
            }
            if (!opened && minecraft.screen instanceof TitleScreen && minecraft.getOverlay() == null) {
                opened = true;
                minecraft.options.pauseOnLostFocus = false;
                minecraft.options.renderDistance().set(6);
                minecraft.options.simulationDistance().set(5);
                minecraft.options.enableVsync().set(false);
                minecraft.options.framerateLimit().set(60);
                minecraft.getTutorial().setStep(TutorialSteps.NONE);
                minecraft.options.graphicsMode().set(System.getProperty("astraengine.verify.graphics", "fancy").equals("fabulous")
                        ? net.minecraft.client.GraphicsStatus.FABULOUS : net.minecraft.client.GraphicsStatus.FANCY);
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 1280, 720);
                if (phase.equals("editor") || phase.equals("editor-restart")
                        || phase.equals("cosmos") || phase.equals("cosmos-restart")
                        || phase.equals("solar") || phase.equals("solar-restart")
                        || phase.equals("seasonal") || phase.equals("seasonal-restart")
                        || phase.equals("camera") || phase.equals("camera-restart") || phase.equals("celestial") || phase.equals("approach")
                        || phase.equals("celestial-api") || phase.equals("celestial-api-restart")
                        || phase.equals("galactic") || phase.equals("galactic-restart")
                        || phase.equals("atlas") || phase.equals("atlas-restart")
                        || phase.equals("ship-visual") || phase.equals("render-compat") || phase.equals("celestial-polish")
                        || phase.startsWith("surface-") || phase.startsWith("terrain-")) {
                    minecraft.options.guiScale().set(2);
                }
                if (phase.equals("create") || phase.equals("lighting") || phase.equals("editor")
                        || phase.equals("cosmos") || phase.equals("solar") || phase.equals("camera") || phase.equals("audio")
                        || phase.equals("celestial") || phase.equals("approach") || phase.equals("celestial-api") || phase.equals("galactic") || phase.equals("atlas")
                        || phase.equals("ship-visual") || phase.equals("render-compat") || phase.equals("seasonal")
                        || phase.equals("solar-clouds") || phase.equals("volumetric") || phase.equals("celestial-polish")
                        || phase.equals("surface-create") || phase.equals("surface-cancel") || phase.equals("surface-interrupt")
                        || phase.equals("surface-failures") || phase.equals("surface-boundaries")
                        || phase.equals("terrain-create") || phase.equals("terrain-dh")) {
                    require(!Files.exists(minecraft.gameDirectory.toPath().resolve("saves/first-slice")),
                            "Create phase refuses to overwrite an existing fixture");
                    minecraft.createWorldOpenFlows().createFreshLevel("first-slice",
                            new LevelSettings("AstraEngine verification", GameType.CREATIVE, false,
                                    Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                            new WorldOptions(20260927L, false, false), WorldPresets::createNormalWorldDimensions,
                            minecraft.screen);
                } else {
                    if (phase.equals("surface-upgrade")) {
                        SurfaceScenario.upgradeManifest(minecraft.gameDirectory.toPath());
                    }
                    minecraft.createWorldOpenFlows().openWorld("first-slice",
                            () -> { throw new IllegalStateException("Could not reopen fixture"); });
                }
                return;
            }
            if (!pending.isDone()) { return; }
            pending.join();
            if (solarAudioScenario != null && solarAudioScenario.disconnected()
                    && minecraft.level == null && minecraft.getOverlay() == null) {
                if (solarAudioScenario.tick()) { finish(); }
                return;
            }
            boolean editorPhase = phase.equals("editor") || phase.equals("editor-restart");
            boolean cosmosPhase = phase.equals("cosmos") || phase.equals("cosmos-restart");
            boolean solarPhase = phase.equals("solar") || phase.equals("solar-restart");
            boolean cameraPhase = phase.equals("camera") || phase.equals("camera-restart");
            boolean celestialPhase = phase.equals("celestial");
            boolean celestialPolishPhase = phase.equals("celestial-polish");
            boolean surfacePhase = phase.startsWith("surface-");
            if (phase.equals("surface-boundaries")) {
                require(!(minecraft.screen instanceof AdvancementsScreen),
                        "Flight landing key opened vanilla advancements during the boundary fixture");
            }
            boolean approachPhase = phase.equals("approach");
            boolean atlasPhase = phase.equals("atlas") || phase.equals("atlas-restart");
            boolean shipVisualPhase = phase.equals("ship-visual") || phase.equals("render-compat");
            boolean galacticPhase = phase.equals("galactic") || phase.equals("galactic-restart");
            boolean celestialApiPhase = phase.equals("celestial-api") || phase.equals("celestial-api-restart");
            if (minecraft.player == null || minecraft.level == null || minecraft.getOverlay() != null
                    || (minecraft.screen != null && !cameraPhase && !(editorPhase && (minecraft.screen instanceof SceneEditorScreen
                            || minecraft.screen instanceof ShaderEditorScreen))
                            && !(phase.equals("surface-failures") && minecraft.screen instanceof DeathScreen)
                            && !((cosmosPhase || solarPhase || cameraPhase || celestialPhase || celestialPolishPhase || surfacePhase
                                    || approachPhase || celestialApiPhase || galacticPhase)
                                    && minecraft.screen instanceof CosmosMapScreen)
                            && !(atlasPhase && (minecraft.screen instanceof CosmosMapScreen
                                    || minecraft.screen instanceof UniverseAtlasScreen))
                            && !(shipVisualPhase && (minecraft.screen instanceof ShipVisualScenario.PreviewScreen
                                    || minecraft.screen instanceof CosmosMapScreen)))) {
                return;
            }
            ticks++;
            if (phase.startsWith("terrain-")) {
                if (terrainScenario == null) { terrainScenario = new TerrainScenario(phase); }
                if (terrainScenario.tick()) { finish(); }
                return;
            }
            if (surfacePhase) {
                if (surfaceScenario == null) { surfaceScenario = new SurfaceScenario(phase, latestSurface); }
                if (surfaceScenario.tick()) { finish(); }
                return;
            }
            if (celestialPolishPhase) {
                if (celestialPolishScenario == null) { celestialPolishScenario = new CelestialPolishScenario(); }
                if (celestialPolishScenario.tick()) { finish(); }
                return;
            }
            if (phase.equals("render-compat")) {
                if (renderCompatibilityScenario == null) { renderCompatibilityScenario = new RenderCompatibilityScenario(); }
                if (renderCompatibilityScenario.tick()) { finish(); }
                return;
            }
            if (phase.equals("volumetric")) {
                if (volumetricScenario == null) { volumetricScenario = new VolumetricScenario(); }
                if (volumetricScenario.tick()) { finish(); }
                return;
            }
            if (phase.equals("solar-clouds")) {
                if (solarCloudScenario == null) { solarCloudScenario = new SolarCloudScenario(); }
                if (solarCloudScenario.tick()) { finish(); }
                return;
            }
            if (phase.equals("seasonal") || phase.equals("seasonal-restart")) {
                if (seasonalScenario == null) { seasonalScenario = new SeasonalScenario(phase.endsWith("-restart")); }
                if (seasonalScenario.tick()) { finish(); }
                return;
            }
            if (shipVisualPhase) {
                if (shipVisualScenario == null) { shipVisualScenario = new ShipVisualScenario(); }
                if (shipVisualScenario.tick()) { finish(); }
                return;
            }
            if (atlasPhase) {
                if (atlasScenario == null) { atlasScenario = new AtlasScenario(phase.endsWith("-restart")); }
                if (atlasScenario.tick()) { finish(); }
                return;
            }
            if (galacticPhase) {
                if (galacticScenario == null) { galacticScenario = new GalacticScenario(phase.endsWith("-restart")); }
                if (galacticScenario.tick()) { finish(); }
                return;
            }
            if (celestialApiPhase) {
                if (celestialApiScenario == null) { celestialApiScenario = new CelestialApiScenario(phase.endsWith("-restart")); }
                if (celestialApiScenario.tick()) { finish(); }
                return;
            }
            if (approachPhase) {
                if (approachScenario == null) { approachScenario = new ApproachScenario(); }
                if (approachScenario.tick()) { finish(); }
                return;
            }
            if (celestialPhase) {
                if (celestialScenario == null) { celestialScenario = new CelestialScenario(); }
                if (celestialScenario.tick()) { finish(); }
                return;
            }
            if (phase.equals("audio")) {
                if (solarAudioScenario == null) { solarAudioScenario = new SolarAudioScenario(); }
                if (solarAudioScenario.tick()) { finish(); }
                return;
            }
            if (cameraPhase) {
                if (cameraScenario == null) { cameraScenario = new CameraScenario(phase.equals("camera-restart")); }
                if (cameraScenario.tick()) { finish(); }
                return;
            }
            if (solarPhase) {
                if (solarScenario == null) { solarScenario = new SolarScenario(phase.equals("solar-restart")); }
                if (solarScenario.tick()) { finish(); }
                return;
            }
            if (cosmosPhase) {
                if (cosmosScenario == null) { cosmosScenario = new CosmosScenario(phase.equals("cosmos-restart")); }
                if (cosmosScenario.tick()) { finish(); }
                return;
            }
            if (editorPhase) {
                if (editorScenario == null) { editorScenario = new EditorScenario(phase.equals("editor-restart")); }
                if (editorScenario.tick()) { finish(); }
                return;
            }
            if (phase.equals("lighting")) {
                if (lightingScenario == null) { lightingScenario = new LightingScenario(); }
                if (lightingScenario.tick()) { finish(); }
                return;
            }
            switch (phase) {
                case "create" -> createScenario();
                case "restart" -> restartScenario();
                case "interrupt" -> interruptScenario();
                case "recover" -> recoverScenario();
                default -> throw new IllegalArgumentException("Unknown verification phase: " + phase);
            }
        } catch (Exception failure) {
            finished = true;
            AstraEngine.LOGGER.error("ASTRA_VERIFY_FAILED phase={} step={}", phase, step, failure);
            minecraft.stop();
        }
    }

    private void createScenario() throws Exception {
        switch (step) {
            case 0 -> {
                if (ticks < 40) { return; }
                command("astra visit alpha");
                next();
            }
            case 1 -> {
                if (!in("transit")) { return; }
                next();
            }
            case 2 -> {
                if (ticks < 25) { return; }
                shot("01-transit");
                next();
            }
            case 3 -> {
                if (!in("alpha")) { return; }
                server(server -> {
                    var player = player(server);
                    var level = player.serverLevel();
                    level.setBlockAndUpdate(MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(9, 81, 12), Blocks.GOLD_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(9, 80, 12), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                    player.teleportTo(8.5, 80, 8.5);
                });
                next();
            }
            case 4 -> {
                look();
                if (ticks < 60) { return; }
                require(received != null && received.stage() == StellarStage.ACTIVE, "Missing active client snapshot");
                shot("02-active");
                server(server -> AstraSystems.extract(server, "alpha", "primary", EXTRACTION, 700_000));
                next();
            }
            case 5 -> {
                look();
                if (ticks < 240) { return; }
                require(received.stage() == StellarStage.UNSTABLE && received.burstCount() >= 1,
                        "Unstable burst did not reach client");
                shot("03-unstable");
                command("astra extract 300000");
                next();
            }
            case 6 -> {
                look();
                if (ticks < 50) { return; }
                require(received.stage() == StellarStage.BLACK_HOLE, "Remnant did not reach client");
                command("astra status");
                shot("04-black-hole");
                pending = minecraft.reloadResourcePacks();
                next();
            }
            case 7 -> {
                look();
                if (ticks < 40) { return; }
                shot("05-resource-reload");
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 960, 540);
                next();
            }
            case 8 -> {
                look();
                if (ticks < 40) { return; }
                shot("06-resized");
                command("astra visit beta");
                next();
            }
            case 9 -> {
                if (!in("beta")) { return; }
                server(server -> {
                    pausedAlpha = AstraSystems.snapshot(server, "alpha");
                    require(AstraSystems.snapshot(server, "beta").remainingResource() == 1_000_000,
                            "Systems shared resource state");
                    require(!player(server).serverLevel().getBlockState(MARKER).is(Blocks.DIAMOND_BLOCK),
                            "Systems shared blocks");
                });
                next();
            }
            case 10 -> {
                look();
                if (ticks < 80) { return; }
                shot("07-beta");
                server(server -> require(AstraSystems.snapshot(server, "alpha").equals(pausedAlpha),
                        "Unoccupied alpha evolved while beta was active"));
                command("astra visit alpha");
                next();
            }
            case 11 -> {
                if (!in("alpha") || ticks < 100) { return; }
                server(server -> verifySavedState(server));
                next();
            }
            case 12 -> {
                look();
                if (ticks < 30) { return; }
                shot("08-returned-alpha");
                finish();
            }
            default -> throw new IllegalStateException("Unexpected fixture step");
        }
    }

    private void restartScenario() throws Exception {
        switch (step) {
            case 0 -> {
                if (ticks < 60) { return; }
                require(in("alpha"), "Saved system dimension not restored on restart");
                server(this::verifySavedState);
                next();
            }
            case 1 -> {
                look();
                if (ticks < 30) { return; }
                shot("09-restart-alpha");
                command("astra leave");
                next();
            }
            case 2 -> {
                if (!minecraft.level.dimension().location().toString().equals("minecraft:overworld")) { return; }
                server(server -> pausedAlpha = AstraSystems.snapshot(server, "alpha"));
                next();
            }
            case 3 -> {
                if (ticks < 60) { return; }
                server(server -> require(AstraSystems.snapshot(server, "alpha").equals(pausedAlpha),
                        "Alpha evolved after expedition ended"));
                shot("10-returned-home");
                next();
            }
            case 4 -> finish();
            default -> throw new IllegalStateException("Unexpected fixture step");
        }
    }

    private void verifySavedState(MinecraftServer server) {
        require(player(server).serverLevel().getBlockState(MARKER).is(Blocks.DIAMOND_BLOCK),
                "Player-edited landing was regenerated or lost");
        require(AstraSystems.snapshot(server, "alpha").stage() == StellarStage.BLACK_HOLE,
                "Saved evolution was lost");
        require(AstraSystems.extract(server, "alpha", "primary", EXTRACTION, 700_000).status()
                == ExtractionResult.Status.REPLAY, "Saved receipt was lost");
    }

    private void interruptScenario() throws Exception {
        if (step == 0 && ticks >= 40) {
            require(minecraft.level.dimension() == net.minecraft.world.level.Level.OVERWORLD,
                    "Restart phase must leave the fixture at home");
            command("astra visit beta");
            next();
        } else if (step == 1 && in("transit")) {
            // Stop with a real connected player in transit. The next JVM must recover from disk.
            shot("11-interrupted-transit");
            finish();
        }
    }

    private void recoverScenario() throws Exception {
        if (step == 0 && ticks >= 60) {
            require(minecraft.level.dimension() == net.minecraft.world.level.Level.OVERWORLD,
                    "Interrupted traveler was not recovered to the origin on login");
            shot("12-recovered-home");
            server(server -> {
                require(player(server).getPersistentData().contains("astraengine_recovery") == false,
                        "Recovery marker survived successful recovery");
                server.getLevel(SystemWorlds.TRANSIT).setBlockAndUpdate(new BlockPos(8, 80, 8),
                        Blocks.STONE.defaultBlockState());
            });
            next();
        } else if (step == 1) {
            command("astra visit alpha");
            next();
        } else if (step == 2 && ticks >= 100) {
            require(minecraft.level.dimension() == net.minecraft.world.level.Level.OVERWORLD,
                    "Obstructed transit moved the player into blocks");
            server(server -> server.getLevel(SystemWorlds.TRANSIT).removeBlock(new BlockPos(8, 80, 8), false));
            shot("13-obstructed-transit");
            next();
        } else if (step == 3) {
            finish();
        }
    }

    private boolean in(String name) {
        return minecraft.level.dimension().location().toString().equals("astraengine:" + name);
    }

    private void look() {
        minecraft.player.setYRot(-14);
        minecraft.player.setXRot(-6);
    }

    private void command(String command) { minecraft.player.connection.sendCommand(command); }

    private void server(Consumer<MinecraftServer> action) {
        var server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }

    private ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private void next() { step++; ticks = 0; }

    private void shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        try (NativeImage frame = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            frame.writeToFile(path);
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after " + name);
        AstraEngine.LOGGER.info("ASTRA_VERIFY_SCREENSHOT {}", name);
    }

    private void finish() throws Exception {
        finished = true;
        Files.writeString(minecraft.gameDirectory.toPath().resolve("verified-" + phase + ".txt"),
                "Native fixture completed: " + phase + "\n");
        AstraEngine.LOGGER.info("ASTRA_VERIFY_PASSED phase={}", phase);
        minecraft.stop();
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
