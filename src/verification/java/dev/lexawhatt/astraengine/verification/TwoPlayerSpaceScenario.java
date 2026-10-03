package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.AstraCosmos;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.server.SystemWorlds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Two actual clients qualify independent virtual pilots, private discoveries and disconnect recovery. */
final class TwoPlayerSpaceScenario {
    private static final String PRIVATE_SYSTEM = "astraengine:verification_private_peer";
    private final Minecraft game = Minecraft.getInstance();
    private final boolean host;
    private final String phase;
    private final Path peers;
    private final StringBuilder evidence = new StringBuilder("Two actual network clients; normal R/W/S controls; no virtual peer rendering claim.\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private UUID hostId;
    private UUID guestId;
    private SpaceVector hostStart;
    private SpaceVector guestStart;
    private SpaceVector hostEnd;
    private SpaceVector guestEnd;
    private SpaceVector reconnectPosition;
    private boolean condition;
    private int step;
    private int ticks;
    private final long started = System.nanoTime();

    TwoPlayerSpaceScenario(String phase) throws Exception {
        this.phase = phase; host = phase.endsWith("-host");
        var config = new Properties();
        try (var stream = Files.newInputStream(game.gameDirectory.toPath().resolve("two-player.properties"))) { config.load(stream); }
        peers = Path.of(config.getProperty("peerDirectory")).toAbsolutePath();
        require(Files.isDirectory(peers), "Missing two-player coordination directory");
        game.options.pauseOnLostFocus = false; game.options.bobView().set(false);
        game.options.renderDistance().set(4); game.options.simulationDistance().set(4);
        game.options.guiScale().set(2); game.options.enableVsync().set(false); game.options.framerateLimit().set(60);
        game.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 1280, 720);
    }

    boolean tick() throws Exception {
        require(System.nanoTime() - started < 600_000_000_000L, "Two-player space timed out at " + phase + "/" + step);
        if (!pending.isDone()) { return false; }
        pending.join(); ticks++;
        if (game.screen instanceof DisconnectedScreen) { throw new IllegalStateException("Unexpected disconnect at " + phase + "/" + step); }
        return host ? hostTick() : guestTick();
    }

    private boolean hostTick() throws Exception {
        if (step == 0) {
            if (game.screen != null) { return false; }
            server(server -> {
                server.setUsesAuthentication(false);
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                var player = server.getPlayerList().getPlayers().getFirst(); hostId = player.getUUID();
                initialize(server, player, 4);
            });
            next(); return false;
        }
        if (step == 1) {
            if (game.screen != null || !game.level.dimension().equals(SystemWorlds.dimension("alpha"))) { return false; }
            int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            require(game.getSingleplayerServer().publishServer(GameType.CREATIVE, false, port), "LAN publication failed");
            write("address", "127.0.0.1:" + port); next(); return false;
        }
        if (step == 2) {
            if (!condition) {
                server(server -> {
                    var guest = server.getPlayerList().getPlayerByName("AstraGuest"); if (guest == null) { return; }
                    guestId = guest.getUUID(); require(!guestId.equals(hostId), "Expected distinct pilot identities");
                    initialize(server, guest, 12); condition = true;
                });
                return false;
            }
            write("host", "enter"); next(); return false;
        }
        if (step == 3) {
            if (!obtainController()) { return false; }
            tap(GLFW.GLFW_KEY_R); next(); return false;
        }
        if (step == 4) {
            if (!controller.active() || ticks < 12) { return false; }
            controller.setSpeed(100); next(); return false;
        }
        if (step == 5) {
            if (controller.snapshot().speedMetersPerSecond() != 100 || !read("guest").equals("ready")) { return false; }
            server(server -> {
                var catalog = ExplorationCatalog.get(server);
                require(RocketService.isFlightWorld(server.getPlayerList().getPlayer(hostId))
                        && RocketService.isFlightWorld(server.getPlayerList().getPlayer(guestId)), "Both real players did not enter staging");
                hostStart = catalog.player(hostId).position(); guestStart = catalog.player(guestId).position();
            });
            next(); return false;
        }
        if (step == 6) {
            write("host", "move"); game.options.keyUp.setDown(true); next(); return false;
        }
        if (step == 7) {
            if (ticks < 120 || !read("guest").equals("moving")) { return false; }
            release(); write("host", "stop"); next(); return false;
        }
        if (step == 8) {
            if (ticks < 20 || !read("guest").equals("stopped")) { return false; }
            var own = controller.snapshot().position(); var peer = vector(read("guest-position"));
            server(server -> {
                var catalog = ExplorationCatalog.get(server);
                var hp = catalog.player(hostId); var gp = catalog.player(guestId);
                hostEnd = hp.position(); guestEnd = gp.position();
                var hd = hostEnd.subtract(hostStart); var gd = guestEnd.subtract(guestStart);
                require(hd.length() > 200 && gd.length() > hd.length() * 1.5
                        && hd.normalized().dot(gd.normalized()) < -.95, "Pilots did not obey independent opposite controls");
                require(hostEnd.distance(own) < 1 && guestEnd.distance(peer) < 1,
                        "A private client navigation snapshot does not match its authoritative pilot");
                require(hp.speedMetersPerSecond() == 100 && gp.speedMetersPerSecond() == 300, "One pilot changed another's speed");
                var sol = CosmosGenerator.sol();
                var custom = new CosmosSystem(PRIVATE_SYSTEM, "Private verification anchor", 98421, sol.kind(),
                        new SpaceVector(10_000, 10_000, 10_000), sol.bodies());
                require(AstraCosmos.create(server, custom) == AstraCosmos.CreateResult.CREATED, "Private fixture system already existed");
                require(AstraCosmos.discover(server.getPlayerList().getPlayer(hostId), PRIVATE_SYSTEM)
                        == AstraCosmos.DiscoverResult.DISCOVERED, "Private discovery failed");
                require(!gp.discoveredSystems().contains(PRIVATE_SYSTEM), "Server leaked one pilot's discovery");
                evidence.append("independentOppositeControls=true hostMeters=").append(hd.length())
                        .append(" guestMeters=").append(gd.length()).append(" authoritativePrivateSnapshots=true\n");
            });
            next(); return false;
        }
        if (step == 9) {
            if (!controller.snapshot().discoveredSystems().contains(PRIVATE_SYSTEM)) { return false; }
            require(controller.system(PRIVATE_SYSTEM).id().equals(PRIVATE_SYSTEM), "Host missed its private descriptor");
            capture("both-flying"); write("host", "privacy"); next(); return false;
        }
        if (step == 10) {
            if (!read("guest").equals("private")) { return false; }
            tap(GLFW.GLFW_KEY_R); write("host", "continue"); next(); return false;
        }
        if (step == 11) {
            if (controller.active() || ticks < 60 || !read("guest").equals("continued")) { return false; }
            server(server -> {
                var catalog = ExplorationCatalog.get(server); var hp = catalog.player(hostId); var gp = catalog.player(guestId);
                require(server.getPlayerList().getPlayer(hostId).serverLevel().dimension().equals(SystemWorlds.dimension("alpha"))
                        && RocketService.isFlightWorld(server.getPlayerList().getPlayer(guestId)), "Exiting one pilot retired the other session");
                require(hp.position().distance(hostEnd) < 1 && gp.position().distance(guestEnd) > 100,
                        "Remaining pilot stopped or departed pilot moved");
                evidence.append("onePilotExitRetainsOtherFlight=true privateDiscoveryIsolated=true\n");
            });
            write("host", "disconnect"); next(); return false;
        }
        if (step == 12) {
            if (!read("guest").equals("disconnected")) { return false; }
            if (!condition) {
                server(server -> {
                    if (server.getPlayerList().getPlayer(guestId) != null) { return; }
                    reconnectPosition = ExplorationCatalog.get(server).player(guestId).position(); condition = true;
                });
                return false;
            }
            write("host", "rejoin"); next(); return false;
        }
        if (step == 13) {
            if (!read("guest").equals("rejoined")) { return false; }
            server(server -> {
                var player = server.getPlayerList().getPlayer(guestId); var pilot = ExplorationCatalog.get(server).player(guestId);
                require(player != null && player.serverLevel().dimension().equals(SystemWorlds.dimension("alpha"))
                        && pilot.position().distance(reconnectPosition) < 1 && !pilot.discoveredSystems().contains(PRIVATE_SYSTEM),
                        "Guest recovery changed navigation, privacy or original source");
                evidence.append("guestReconnectRecoversRealSourceAndRetainsVirtualPosition=true\n");
            });
            write("host", "reenter"); next(); return false;
        }
        if (step == 14) {
            if (!read("guest").equals("complete")) { return false; }
            server(server -> {
                var catalog = ExplorationCatalog.get(server);
                require(catalog.player(guestId).position().distance(reconnectPosition) < 1
                        && catalog.player(hostId).position().distance(hostEnd) < 1, "Reentry reset one of the virtual pilots");
                require(catalog.player(hostId).discoveredSystems().contains(PRIVATE_SYSTEM)
                        && !catalog.player(guestId).discoveredSystems().contains(PRIVATE_SYSTEM), "Reconnect mixed private discoveries");
                evidence.append("guestReentryPreservesVirtualPosition=true\n");
            });
            next(); return false;
        }
        write("host", "complete"); return finish();
    }

    private boolean guestTick() throws Exception {
        if (step == 0) {
            if (!(game.screen instanceof TitleScreen) || game.getOverlay() != null || read("address").isEmpty()) { return false; }
            connect(); next(); return false;
        }
        if (step == 1) {
            if (!connected() || !read("host").equals("enter") || !game.level.dimension().equals(SystemWorlds.dimension("alpha"))) { return false; }
            if (!obtainController()) { return false; }
            tap(GLFW.GLFW_KEY_R); next(); return false;
        }
        if (step == 2) {
            if (!controller.active() || ticks < 12) { return false; }
            controller.setSpeed(300); next(); return false;
        }
        if (step == 3) {
            if (controller.snapshot().speedMetersPerSecond() != 300) { return false; }
            write("guest", "ready"); next(); return false;
        }
        if (step == 4) {
            if (!read("host").equals("move")) { return false; }
            game.options.keyDown.setDown(true); write("guest", "moving"); next(); return false;
        }
        if (step == 5) {
            if (!read("host").equals("stop")) { return false; }
            release(); next(); return false;
        }
        if (step == 6) {
            if (ticks < 20) { return false; }
            write("guest-position", vector(controller.snapshot().position())); write("guest", "stopped"); next(); return false;
        }
        if (step == 7) {
            if (!read("host").equals("privacy") || ticks < 30) { return false; }
            assertPrivate(); capture("both-flying"); write("guest", "private"); next(); return false;
        }
        if (step == 8) {
            if (!read("host").equals("continue")) { return false; }
            require(controller.active(), "Other pilot exit stopped guest"); game.options.keyUp.setDown(true); next(); return false;
        }
        if (step == 9) {
            if (ticks < 45) { return false; }
            release(); write("guest", "continued"); next(); return false;
        }
        if (step == 10) {
            if (!read("host").equals("disconnect") || ticks < 20) { return false; }
            reconnectPosition = controller.snapshot().position(); assertPrivate();
            game.level.disconnect(); game.disconnect(new TitleScreen()); controller = null;
            write("guest", "disconnected"); next(); return false;
        }
        if (step == 11) {
            if (!(game.screen instanceof TitleScreen) || !read("host").equals("rejoin")) { return false; }
            connect(); next(); return false;
        }
        if (step == 12) {
            if (!connected() || !game.level.dimension().equals(SystemWorlds.dimension("alpha")) || !obtainController()) { return false; }
            if (controller.snapshot() == null) { return false; }
            require(!controller.active() && controller.snapshot().position().distance(reconnectPosition) < 1,
                    "Reconnect did not recover inactive original source while retaining virtual position");
            assertPrivate(); write("guest", "rejoined"); next(); return false;
        }
        if (step == 13) {
            if (!read("host").equals("reenter")) { return false; }
            tap(GLFW.GLFW_KEY_R); next(); return false;
        }
        if (step == 14) {
            if (!controller.active() || ticks < 20) { return false; }
            require(controller.snapshot().position().distance(reconnectPosition) < 1, "Reentry reset guest virtual position");
            assertPrivate(); capture("reentered"); tap(GLFW.GLFW_KEY_R); next(); return false;
        }
        if (step == 15) {
            if (controller.active() || !game.level.dimension().equals(SystemWorlds.dimension("alpha"))) { return false; }
            write("guest", "complete"); next(); return false;
        }
        if (!read("host").equals("complete")) { return false; }
        evidence.append("independentControlsPrivateDiscoveryAndReconnect=true\n"); return finish();
    }

    private void initialize(MinecraftServer server, ServerPlayer player, int x) {
        var level = server.getLevel(SystemWorlds.dimension("alpha")); require(level != null, "Fixture source world is missing");
        level.setBlock(new BlockPos(x, 79, 8), Blocks.STONE.defaultBlockState(), 3);
        player.setGameMode(GameType.CREATIVE); player.teleportTo(level, x + .5, 80, 8.5, 0, 0);
        player.setDeltaMovement(Vec3.ZERO); player.getAbilities().flying = true; player.onUpdateAbilities();
    }
    private boolean obtainController() {
        if (controller != null) { return true; }
        if (game.screen instanceof CosmosMapScreen map) { controller = map.controller(); map.onClose(); return true; }
        if (game.screen == null) { game.player.connection.sendCommand("astra-flight map"); }
        return false;
    }
    private void assertPrivate() {
        require(!controller.snapshot().discoveredSystems().contains(PRIVATE_SYSTEM), "Guest received host's private discovery");
        try { controller.system(PRIVATE_SYSTEM); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Guest received undiscovered custom descriptor");
    }
    private boolean connected() {
        return game.player != null && game.level != null && (game.screen == null || game.screen instanceof CosmosMapScreen);
    }
    private void connect() throws Exception {
        String address = read("address");
        ConnectScreen.startConnecting(new TitleScreen(), game, ServerAddress.parseString(address),
                new ServerData("AstraEngine two-pilot verification", address, ServerData.Type.LAN), false, null);
    }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void next() {
        step++; ticks = 0; condition = false;
        AstraEngine.LOGGER.info("ASTRA_TWO_SPACE_VERIFY role={} step={}", host ? "host" : "guest", step);
    }
    private void release() { game.options.keyUp.setDown(false); game.options.keyDown.setDown(false); }
    private void tap(int key) { game.mouseHandler.grabMouse(); KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private String read(String name) throws Exception {
        var file = peers.resolve(name + ".txt"); return Files.isRegularFile(file) ? Files.readString(file).trim() : "";
    }
    private void write(String name, String value) throws Exception {
        var temporary = peers.resolve(name + ".tmp"); Files.writeString(temporary, value + "\n");
        Files.move(temporary, peers.resolve(name + ".txt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static String vector(SpaceVector value) { return value.x() + "," + value.y() + "," + value.z(); }
    private static SpaceVector vector(String text) {
        var parts = text.split(","); require(parts.length == 3, "Malformed fixture snapshot");
        return new SpaceVector(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
    }
    private void capture(String name) throws Exception {
        var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(directory.resolve("two-space-" + name + ".png"));
        }
    }
    private boolean finish() throws Exception {
        release(); var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        Files.writeString(directory.resolve(phase + ".txt"), evidence, StandardOpenOption.CREATE_NEW); return true;
    }
    private static void require(boolean value, String message) { if (!value) { throw new IllegalStateException(message); } }
}
