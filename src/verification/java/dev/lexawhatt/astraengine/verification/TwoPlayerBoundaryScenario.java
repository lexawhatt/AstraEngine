package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.network.EarthBoundaryReceivedEvent;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import dev.lexawhatt.astraengine.surface.EarthChart;
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
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

/** Two separate game clients use one canonical neighboring chest through host interaction and menu packets. */
final class TwoPlayerBoundaryScenario {
    private static final BlockPos CHEST = new BlockPos(0, -2031, 0);
    private static final Vec3 HOST_FEET = new Vec3(.5, 2030, -2.5);
    private static final Vec3 GUEST_FEET = new Vec3(.5, -2031, 3.5);
    private static final TicketType<String> TICKET = TicketType.create("astraengine_verify_two_players", String::compareTo);
    private final Minecraft game = Minecraft.getInstance();
    private final boolean host;
    private final boolean restart;
    private final Path peers;
    private final String phase;
    private final Properties checkpoint = new Properties();
    private final StringBuilder evidence = new StringBuilder("Two separate JVMs; normal interaction/menu packets; canonical server inventory.\n");
    private final Consumer<EarthBoundaryReceivedEvent> receiver = event -> this.observation = event.payload().snapshot();
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private CompletableFuture<?> chunks;
    private EarthBoundarySnapshot observation;
    private EarthChart source;
    private EarthChart target;
    private UUID hostId;
    private UUID guestId;
    private int step;
    private int ticks;
    private int port;
    private boolean condition;
    private boolean openCaptured;
    private final long started = System.nanoTime();

    TwoPlayerBoundaryScenario(String phase) throws Exception {
        this.phase = phase; host = phase.contains("-host"); restart = phase.endsWith("-restart");
        Properties config = new Properties();
        try (var input = Files.newInputStream(game.gameDirectory.toPath().resolve("two-player.properties"))) { config.load(input); }
        peers = Path.of(config.getProperty("peerDirectory")).toAbsolutePath();
        require(Files.isDirectory(peers), "Two-player coordination directory is missing");
        game.options.pauseOnLostFocus = false; game.options.hideGui = false; game.options.bobView().set(false);
        game.options.renderDistance().set(4); game.options.simulationDistance().set(4);
        game.options.guiScale().set(2); game.options.enableVsync().set(false); game.options.framerateLimit().set(60);
        game.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 1280, 720);
        game.options.keyAttack.setDown(false); game.options.keyUse.setDown(false);
        NeoForge.EVENT_BUS.addListener(receiver);
    }

    boolean tick() throws Exception {
        require(System.nanoTime() - started < 600_000_000_000L, "Two-player fixture timed out: " + phase + " step " + step);
        if (!pending.isDone()) { return false; }
        pending.join(); ticks++;
        game.options.keyUse.setDown(false);
        if (game.screen instanceof DisconnectedScreen) {
            throw new IllegalStateException("Unexpected network disconnect at " + phase + " step " + step);
        }
        return host ? hostTick() : guestTick();
    }

    private boolean hostTick() throws Exception {
        if (step == 0) {
            require(game.getSingleplayerServer() != null, "Host requires its actual integrated server");
            if (restart) {
                try (var input = Files.newInputStream(game.gameDirectory.toPath()
                        .resolve("earth-two-player-host-checkpoint.properties"))) { checkpoint.load(input); }
            }
            server(server -> {
                int version = EarthWorlds.terrainVersion(server);
                source = new EarthChart(CubeFace.POSITIVE_X, 3, version);
                target = new EarthChart(CubeFace.POSITIVE_X, 4, version);
                var origin = PlanetSurfaceWorlds.ensure(server, source);
                var neighbor = PlanetSurfaceWorlds.ensure(server, target);
                hostId = server.getPlayerList().getPlayers().getFirst().getUUID();
                var player = server.getPlayerList().getPlayer(hostId);
                server.setUsesAuthentication(false);
                for (int z = -1; z <= 0; z++) {
                    neighbor.getChunkSource().addRegionTicket(TICKET, new ChunkPos(0, z), 0, "shared-chest");
                }
                chunks = CompletableFuture.allOf(neighbor.getChunkSource().getChunkFuture(0, 0, ChunkStatus.FULL, true),
                        neighbor.getChunkSource().getChunkFuture(0, -1, ChunkStatus.FULL, true));
                if (restart) {
                    require("1".equals(checkpoint.getProperty("version"))
                            && hostId.toString().equals(checkpoint.getProperty("host"))
                            && source.dimensionId().equals(checkpoint.getProperty("source"))
                            && target.dimensionId().equals(checkpoint.getProperty("target"))
                            && Integer.toString(version).equals(checkpoint.getProperty("terrainVersion"))
                            && "1".equals(checkpoint.getProperty("chestCount"))
                            && "15".equals(checkpoint.getProperty("guestCount")), "Wrong two-client restart checkpoint");
                    guestId = UUID.fromString(checkpoint.getProperty("guest"));
                    assertHostPose(player);
                    require(emeralds(player) == 0, "Host inventory changed during restart");
                } else {
                    initializePlayer(player, origin, HOST_FEET, 0, (float) -Math.toDegrees(Math.atan2(1.88, 3)));
                }
            });
            next(); return false;
        }
        if (step == 1) {
            if (chunks == null || !chunks.isDone()) { return false; } chunks.join();
            server(server -> {
                var level = server.getLevel(EarthWorlds.dimension(target));
                if (restart) {
                    require(level.getBlockEntity(CHEST) instanceof ChestBlockEntity && chest(server).getItem(0).getCount() == 1,
                            "Canonical shared chest did not survive server restart");
                } else {
                    // Real support under each player keeps the persisted pose stable even if login resets flight abilities.
                    server.getLevel(EarthWorlds.dimension(source)).setBlock(new BlockPos(0, 2029, -3), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(new BlockPos(0, -2032, 3), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(CHEST, Blocks.CHEST.defaultBlockState(), 3);
                    chest(server).setItem(0, new ItemStack(Items.EMERALD, 16)); chest(server).setChanged();
                }
            });
            next(); return false;
        }
        if (step == 2) {
            if (game.screen != null || !observedChest()) { return false; }
            try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            require(game.getSingleplayerServer().publishServer(GameType.SURVIVAL, false, port), "LAN publication failed");
            write("address", "127.0.0.1:" + port); write("host", "waiting_guest");
            next(); return false;
        }
        if (step == 3) {
            if (!condition) {
                server(server -> {
                    var guest = server.getPlayerList().getPlayerByName("AstraGuest");
                    if (guest == null) { return; }
                    if (restart) {
                        require(guest.getUUID().equals(guestId), "Restart connected a different guest identity");
                        assertGuestPose(guest); require(emeralds(guest) == 15, "Guest inventory was not persistent");
                    } else {
                        guestId = guest.getUUID();
                        initializePlayer(guest, server.getLevel(EarthWorlds.dimension(target)), GUEST_FEET, 180,
                                (float) Math.toDegrees(Math.atan2(1.12, 3)));
                    }
                    require(!guestId.equals(hostId), "Host and guest did not use independent player identities");
                    condition = true;
                });
                return false;
            }
            if (observedOpen()) { return false; }
            capture("neighbor-lid-closed");
            write("host", "ready"); openChest(); next(); return false;
        }
        if (step == 4) {
            String guestState = read("guest");
            if (!(game.player.containerMenu instanceof ChestMenu) || !(guestState.equals("opened")
                    || restart && guestState.equals("restart_verified"))) { return false; }
            require(slot(0) == (restart ? 1 : 16), "Host did not receive canonical initial chest inventory");
            capture("both-open");
            server(server -> {
                require(server.getPlayerList().getPlayer(hostId).containerMenu instanceof ChestMenu
                        && server.getPlayerList().getPlayer(guestId).containerMenu instanceof ChestMenu,
                        "Both network players did not hold real menus simultaneously");
            });
            if (restart) { step = 12; return false; }
            click(0, 1, ClickType.PICKUP); next(); return false;
        }
        if (step == 5) {
            if (slot(0) != 8 || game.player.containerMenu.getCarried().getCount() != 8) { return false; }
            click(27, 0, ClickType.PICKUP); next(); return false;
        }
        if (step == 6) {
            if (!game.player.containerMenu.getCarried().isEmpty() || slot(27) != 8) { return false; }
            write("host", "half_taken"); next(); return false;
        }
        if (step == 7) {
            if (!read("guest").equals("remaining_taken") || slot(0) != 0) { return false; }
            server(server -> {
                require(emeralds(server.getPlayerList().getPlayer(hostId)) == 8
                        && emeralds(server.getPlayerList().getPlayer(guestId)) == 8 && chest(server).getItem(0).isEmpty(),
                        "Two real menu consumers duplicated or lost canonical items");
                evidence.append("simultaneousMenusSplit16Into8Plus8=true\n");
            });
            click(27, 0, ClickType.PICKUP); next(); return false;
        }
        if (step == 8) {
            if (game.player.containerMenu.getCarried().getCount() != 8) { return false; }
            click(0, 0, ClickType.PICKUP); next(); return false;
        }
        if (step == 9) {
            if (slot(0) != 8 || !game.player.containerMenu.getCarried().isEmpty()) { return false; }
            write("host", "returned"); next(); return false;
        }
        if (step == 10) {
            if (!read("guest").equals("final_items") || slot(0) != 1) { return false; }
            server(this::assertFinalInventory); capture("synchronized-final"); game.player.closeContainer();
            next(); return false;
        }
        if (step == 11) {
            if (!openCaptured) {
                if (ticks < 15 || !observedOpen()) { return false; }
                require(game.screen == null, "Host menu obscured the neighboring open chest view");
                capture("neighbor-lid-open"); evidence.append("unobscuredCanonicalClosedAndOpenChestViews=true\n");
                openCaptured = true; write("host", "rejoin"); return false;
            }
            if (!condition) {
                server(server -> condition = server.getPlayerList().getPlayer(guestId) == null); return false;
            }
            write("host", "rejoin_ready"); next(); return false;
        }
        if (step == 12) {
            if (restart) {
                if (!read("guest").equals("restart_verified")) { return false; }
            } else {
                if (!read("guest").equals("rejoin_verified")) { return false; }
                if (!(game.player.containerMenu instanceof ChestMenu)) { openChest(); return false; }
                if (slot(0) != 1) { return false; }
            }
            server(server -> {
                assertFinalInventory(server);
                var guest = server.getPlayerList().getPlayer(guestId); assertGuestPose(guest);
                require(guest.containerMenu instanceof ChestMenu, "Rejoined guest did not open a real canonical menu");
                evidence.append(restart ? "bothPlayersAndCanonicalChestSurvivedServerRestart=true\n"
                        : "sameGuestRejoinedWith15ItemsAndCanonicalChest1=true\n");
                checkpoint.setProperty("version", "1"); checkpoint.setProperty("host", hostId.toString());
                checkpoint.setProperty("guest", guestId.toString()); checkpoint.setProperty("source", source.dimensionId());
                checkpoint.setProperty("target", target.dimensionId());
                checkpoint.setProperty("terrainVersion", Integer.toString(source.terrainVersion()));
                checkpoint.setProperty("chestCount", "1"); checkpoint.setProperty("guestCount", "15");
                server.getPlayerList().saveAll(); server.saveEverything(false, true, true);
            });
            next(); return false;
        }
        if (step == 13) {
            if (!restart) {
                try (var output = Files.newOutputStream(game.gameDirectory.toPath()
                        .resolve("earth-two-player-host-checkpoint.properties"), StandardOpenOption.CREATE_NEW)) {
                    checkpoint.store(output, "Two real clients and canonical chest after ordinary menu packets");
                }
            }
            write("host", "complete"); next(); return false;
        }
        if (!read("guest").equals("complete")) { return false; }
        return finish();
    }

    private boolean guestTick() throws Exception {
        if (step == 0) {
            if (!(game.screen instanceof TitleScreen) || game.getOverlay() != null || read("address").isEmpty()) { return false; }
            connect(); next(); return false;
        }
        if (step == 1) {
            if (game.player == null || game.level == null || game.screen != null || !read("host").equals("ready")) { return false; }
            if (!game.level.getBlockState(CHEST).is(Blocks.CHEST)) { return false; }
            openChest(); next(); return false;
        }
        if (step == 2) {
            if (!(game.player.containerMenu instanceof ChestMenu)) { return false; }
            require(slot(0) == (restart ? 1 : 16), "Guest received a different initial chest inventory");
            capture("both-open"); write("guest", "opened");
            if (restart) { step = 11; return false; }
            next(); return false;
        }
        if (step == 3) {
            if (!read("host").equals("half_taken") || slot(0) != 8) { return false; }
            click(0, 0, ClickType.QUICK_MOVE); next(); return false;
        }
        if (step == 4) {
            if (slot(0) != 0 || emeralds(game.player) != 8) { return false; }
            write("guest", "remaining_taken"); next(); return false;
        }
        if (step == 5) {
            if (!read("host").equals("returned") || slot(0) != 8) { return false; }
            click(0, 0, ClickType.QUICK_MOVE); next(); return false;
        }
        if (step == 6) {
            if (slot(0) != 0 || emeralds(game.player) != 16) { return false; }
            click(emeraldSlot(), 0, ClickType.PICKUP); next(); return false;
        }
        if (step == 7) {
            if (game.player.containerMenu.getCarried().getCount() != 16) { return false; }
            click(0, 1, ClickType.PICKUP); next(); return false;
        }
        if (step == 8) {
            if (slot(0) != 1 || game.player.containerMenu.getCarried().getCount() != 15) { return false; }
            click(27, 0, ClickType.PICKUP); next(); return false;
        }
        if (step == 9) {
            if (emeralds(game.player) != 15 || !game.player.containerMenu.getCarried().isEmpty()) { return false; }
            write("guest", "final_items"); capture("synchronized-final"); next(); return false;
        }
        if (step == 10) {
            if (!read("host").equals("rejoin")) { return false; }
            game.player.closeContainer(); game.level.disconnect(); game.disconnect(new TitleScreen());
            write("guest", "disconnected"); next(); return false;
        }
        if (step == 11) {
            if (restart) {
                require(emeralds(game.player) == 15 && slot(0) == 1, "Restart client lost shared chest or personal items");
                write("guest", "restart_verified"); step = 14; return false;
            }
            if (!(game.screen instanceof TitleScreen) || !read("host").equals("rejoin_ready")) { return false; }
            connect(); next(); return false;
        }
        if (step == 12) {
            if (game.player == null || game.level == null || game.screen != null) { return false; }
            if (emeralds(game.player) != 15) { return false; }
            if (!game.level.getBlockState(CHEST).is(Blocks.CHEST)) { return false; }
            openChest(); next(); return false;
        }
        if (step == 13) {
            if (!(game.player.containerMenu instanceof ChestMenu) || slot(0) != 1) { return false; }
            capture("rejoined"); write("guest", "rejoin_verified"); next(); return false;
        }
        if (!read("host").equals("complete")) { return false; }
        write("guest", "complete");
        evidence.append(restart ? "restartChest1PersonalInventory15=true\n" : "normalMenuPacketsAndReconnectChest1PersonalInventory15=true\n");
        return finish();
    }

    private void assertFinalInventory(MinecraftServer server) {
        var hostPlayer = server.getPlayerList().getPlayer(hostId); var guestPlayer = server.getPlayerList().getPlayer(guestId);
        require(hostPlayer != null && guestPlayer != null && hostPlayer != guestPlayer, "Expected two actual connected players");
        require(chest(server).getItem(0).is(Items.EMERALD) && chest(server).getItem(0).getCount() == 1
                && emeralds(hostPlayer) == 0 && emeralds(guestPlayer) == 15, "Shared inventory conservation failed");
        assertHostPose(hostPlayer);
    }
    private void assertHostPose(ServerPlayer player) {
        require(player.serverLevel().dimension().equals(EarthWorlds.dimension(source))
                && player.position().distanceTo(HOST_FEET) < .1, "Host interaction moved its canonical chart or pose");
    }
    private void assertGuestPose(ServerPlayer player) {
        require(player.serverLevel().dimension().equals(EarthWorlds.dimension(target))
                && player.position().distanceTo(GUEST_FEET) < .1, "Guest chart or pose did not persist");
    }
    private ChestBlockEntity chest(MinecraftServer server) {
        return (ChestBlockEntity) server.getLevel(EarthWorlds.dimension(target)).getBlockEntity(CHEST);
    }
    private static void initializePlayer(ServerPlayer player, net.minecraft.server.level.ServerLevel level, Vec3 feet, float yaw, float pitch) {
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent(); player.getInventory().selected = 0;
        player.teleportTo(level, feet.x, feet.y, feet.z, yaw, pitch);
        player.getAbilities().mayfly = true; player.getAbilities().flying = true; player.onUpdateAbilities();
        player.setDeltaMovement(Vec3.ZERO); player.containerMenu.broadcastChanges();
    }
    private boolean observedChest() {
        if (observation == null || !observation.complete() || !observation.source().equals(source)) { return false; }
        for (var section : observation.sections()) {
            if (section.chart().equals(target) && section.section().asLong() == SectionPos.asLong(CHEST)) {
                int index = ((CHEST.getY() & 15) * 16 + (CHEST.getZ() & 15)) * 16 + (CHEST.getX() & 15);
                return Block.stateById(section.state(index)).is(Blocks.CHEST);
            }
        }
        return false;
    }
    private boolean observedOpen() {
        if (observation == null) { return false; }
        for (var section : observation.sections()) {
            if (section.chart().equals(target) && section.section().asLong() == SectionPos.asLong(CHEST)) {
                int index = ((CHEST.getY() & 15) * 16 + (CHEST.getZ() & 15)) * 16 + (CHEST.getX() & 15);
                return section.containerOpen(index);
            }
        }
        return false;
    }
    private void openChest() { game.mouseHandler.grabMouse(); game.options.keyUse.setDown(true); KeyMapping.click(game.options.keyUse.getKey()); }
    private void click(int slot, int button, ClickType type) {
        game.gameMode.handleInventoryMouseClick(game.player.containerMenu.containerId, slot, button, type, game.player);
    }
    private int slot(int index) {
        var stack = game.player.containerMenu.getSlot(index).getItem();
        require(stack.isEmpty() || stack.is(Items.EMERALD), "Unexpected fixture menu item"); return stack.getCount();
    }
    private int emeraldSlot() {
        for (int i = 27; i < game.player.containerMenu.slots.size(); i++) { if (slot(i) == 16) { return i; } }
        throw new IllegalStateException("Guest inventory slot with sixteen emeralds is missing");
    }
    private static int emeralds(net.minecraft.world.entity.player.Player player) { return player.getInventory().countItem(Items.EMERALD); }
    private void connect() throws Exception {
        String address = read("address");
        ConnectScreen.startConnecting(new TitleScreen(), game, ServerAddress.parseString(address),
                new ServerData("AstraEngine disposable two-client fixture", address, ServerData.Type.LAN), false, null);
    }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void next() {
        step++; ticks = 0; condition = false;
        AstraEngine.LOGGER.info("ASTRA_TWO_PLAYER_VERIFY role={} restart={} step={}", host ? "host" : "guest", restart, step);
    }
    private String read(String name) throws Exception {
        var path = peers.resolve(name + ".txt"); return Files.isRegularFile(path) ? Files.readString(path).trim() : "";
    }
    private void write(String name, String value) throws Exception {
        var temporary = peers.resolve(name + ".tmp"); Files.writeString(temporary, value + "\n");
        Files.move(temporary, peers.resolve(name + ".txt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private void capture(String name) throws Exception {
        var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(directory.resolve("two-player-" + (restart ? "restart-" : "") + name + ".png"));
        }
    }
    private boolean finish() throws Exception {
        game.options.keyUse.setDown(false); NeoForge.EVENT_BUS.unregister(receiver);
        var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        Files.writeString(directory.resolve(phase + ".txt"), evidence, StandardOpenOption.CREATE_NEW);
        return true;
    }
    private static void require(boolean value, String message) { if (!value) { throw new IllegalStateException(message); } }
}
