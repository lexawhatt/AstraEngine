package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.network.BoundaryInteractPayload;
import dev.lexawhatt.astraengine.network.EarthBoundaryReceivedEvent;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import dev.lexawhatt.astraengine.surface.EarthChart;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/** Actual network rejection, normal survival keys, canonical placement and same-world restart persistence. */
final class BoundaryInteractionScenario {
    private static final BlockPos TARGET = new BlockPos(0, -2031, 0);
    private static final BlockPos DISTANT_TARGET = TARGET.offset(8, 0, 0);
    private static final BlockPos SOURCE_PROBE = new BlockPos(0, 2031, 0);
    private static final Vec3 SOURCE_FEET = new Vec3(.5, 2029, .5);
    private static final TicketType<String> TICKET = TicketType.create("astraengine_verify_neighbor", String::compareTo);
    private final Minecraft game = Minecraft.getInstance();
    private final boolean restart;
    private final Consumer<EarthBoundaryReceivedEvent> receiver = event -> this.observation = event.payload().snapshot();
    private final Properties checkpoint = new Properties();
    private final StringBuilder evidence = new StringBuilder(
            "Actual invalid network probes; all accepted mutations use ordinary host key mappings.\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private CompletableFuture<?> loaded;
    private EarthBoundarySnapshot observation;
    private EarthChart source;
    private EarthChart target;
    private boolean targetGone;
    private boolean placed;
    private int step;
    private int ticks;
    private int stable;
    private int modeProbe;
    private int placementObservedTicks;
    private final long started = System.nanoTime();

    BoundaryInteractionScenario() { this(false); }

    BoundaryInteractionScenario(boolean restart) {
        this.restart = restart;
        game.options.hideGui = false; game.options.bobView().set(false);
        game.options.keyAttack.setDown(false); game.options.keyUse.setDown(false);
        NeoForge.EVENT_BUS.addListener(receiver);
    }

    boolean tick() throws Exception {
        require(System.nanoTime() - started < 240_000_000_000L, "Neighbor interaction timed out at " + step);
        if (!pending.isDone()) { return false; }
        pending.join(); ticks++;
        if (step == 0) {
            if (restart) {
                try (var input = Files.newInputStream(game.gameDirectory.toPath()
                        .resolve("earth-interactions-checkpoint.properties"))) { checkpoint.load(input); }
            }
            server(server -> {
                int version = EarthWorlds.terrainVersion(server);
                source = new EarthChart(CubeFace.POSITIVE_X, 3, version);
                target = new EarthChart(CubeFace.POSITIVE_X, 4, version);
                var origin = PlanetSurfaceWorlds.ensure(server, source);
                var neighbor = PlanetSurfaceWorlds.ensure(server, target);
                neighbor.getChunkSource().addRegionTicket(TICKET, new ChunkPos(TARGET), 0, "interaction");
                loaded = neighbor.getChunkSource().getChunkFuture(0, 0, ChunkStatus.FULL, true);
                var player = player(server);
                if (restart) {
                    require("1".equals(checkpoint.getProperty("version"))
                            && source.dimensionId().equals(checkpoint.getProperty("source"))
                            && target.dimensionId().equals(checkpoint.getProperty("target"))
                            && Integer.toString(version).equals(checkpoint.getProperty("terrainVersion"))
                            && TARGET.toShortString().equals(checkpoint.getProperty("block"))
                            && player.getUUID().toString().equals(checkpoint.getProperty("player"))
                            && "31".equals(checkpoint.getProperty("remainingBlocks")), "Unrelated interaction restart checkpoint");
                    assertPose(player);
                } else {
                    origin.setBlock(new BlockPos(0, 2028, 0), Blocks.STONE.defaultBlockState(), 3);
                    player.setGameMode(GameType.SURVIVAL);
                    player.getInventory().clearContent(); player.getInventory().selected = 0;
                    player.teleportTo(origin, SOURCE_FEET.x, SOURCE_FEET.y, SOURCE_FEET.z, 0, -90);
                    player.getAbilities().mayfly = true; player.getAbilities().flying = true; player.onUpdateAbilities();
                    player.setDeltaMovement(Vec3.ZERO);
                }
            });
            next(); return false;
        }
        if (step == 1) {
            if (loaded == null || !loaded.isDone()) { return false; } loaded.join();
            server(server -> {
                var neighbor = server.getLevel(EarthWorlds.dimension(target));
                if (restart) {
                    assertPersisted(server); evidence.append("samePlayerCanonicalBlockAndInventorySurvivedRestart=true\n");
                } else {
                    neighbor.setBlock(TARGET, Blocks.STONE.defaultBlockState(), 3);
                    neighbor.setBlock(TARGET.above(), Blocks.STONE.defaultBlockState(), 3);
                    neighbor.setBlock(DISTANT_TARGET, Blocks.STONE.defaultBlockState(), 3);
                    require(neighbor.getBlockState(TARGET.below()).isAir(), "Invalid-use destination was not initially empty");
                    require(server.getLevel(EarthWorlds.dimension(source)).getBlockState(SOURCE_PROBE).isAir(),
                            "Source probe was not initially empty");
                    player(server).getInventory().setItem(0, new ItemStack(Items.RED_CONCRETE, 32));
                    player(server).containerMenu.broadcastChanges();
                }
            });
            next(); return false;
        }
        if (step == 2) {
            if (game.screen != null || game.level == null || !game.level.dimension().location().toString().equals(source.dimensionId())
                    || observation == null || !observation.complete() || !observation.source().equals(source)
                    || !observed(restart ? Blocks.RED_CONCRETE : Blocks.STONE) || ++stable < 30) { return false; }
            game.mouseHandler.grabMouse();
            if (restart) {
                require(game.player.getMainHandItem().is(Items.RED_CONCRETE)
                        && game.player.getMainHandItem().getCount() == 31, "Restart client inventory disagrees with canonical player");
                capture("restart"); evidence.append("freshNeighborObservationAfterRestart=true revision=")
                        .append(observation.revision()).append('\n');
                step = 10; return false;
            }
            if (!game.player.getMainHandItem().is(Items.RED_CONCRETE)
                    || game.player.getMainHandItem().getCount() != 32) { return false; }
            long revision = observation.revision();
            invalidUse(target, TARGET, Long.MAX_VALUE);
            invalidUse(source, SOURCE_PROBE, revision);
            invalidUse(target, DISTANT_TARGET, revision);
            next(); return false;
        }
        if (step == 3) {
            if (ticks < 16) { return false; }
            server(server -> {
                var neighbor = server.getLevel(EarthWorlds.dimension(target));
                require(neighbor.getBlockState(TARGET).is(Blocks.STONE)
                        && neighbor.getBlockState(TARGET.above()).is(Blocks.STONE)
                        && neighbor.getBlockState(TARGET.below()).isAir()
                        && neighbor.getBlockState(DISTANT_TARGET).is(Blocks.STONE)
                        && neighbor.getBlockState(DISTANT_TARGET.below()).isAir()
                        && server.getLevel(EarthWorlds.dimension(source)).getBlockState(SOURCE_PROBE).isAir(),
                        "Rejected interaction mutated canonical blocks");
                var player = player(server); assertPose(player);
                require(player.getMainHandItem().is(Items.RED_CONCRETE) && player.getMainHandItem().getCount() == 32,
                        "Rejected interaction consumed canonical inventory");
                evidence.append("futureRevisionSameOwnerAndOutOfReachRejected=true unchangedInventory=32\n");
                player.getInventory().clearContent(); player.containerMenu.broadcastChanges();
            });
            next(); return false;
        }
        if (step == 4) {
            if (!game.player.getMainHandItem().isEmpty() || ticks < 10) { return false; }
            if (modeProbe == 0) {
                press(game.options.keyAttack); modeProbe = 1; ticks = 0; return false;
            }
            if (modeProbe == 1) {
                if (ticks < 12) { return false; }
                server(server -> player(server).setGameMode(GameType.ADVENTURE));
                modeProbe = 2; ticks = 0; return false;
            }
            if (modeProbe == 2) {
                if (ticks < 120) { return false; }
                server(server -> {
                    require(server.getLevel(EarthWorlds.dimension(target)).getBlockState(TARGET).is(Blocks.STONE),
                            "Survival mining retained stale permissions after entering Adventure mode");
                    player(server).setGameMode(GameType.SURVIVAL);
                    evidence.append("gameModeChangeRetiresHeldSurvivalMining=true\n");
                });
                game.options.keyAttack.setDown(false); modeProbe = 3; ticks = 0; return false;
            }
            if (ticks < 15) { return false; }
            capture("outline"); press(game.options.keyAttack); next(); return false;
        }
        if (step == 5) {
            if (ticks < 6) { return false; }
            game.options.keyAttack.setDown(false); next(); return false;
        }
        if (step == 6) {
            if (ticks < 15) { return false; }
            server(server -> {
                require(server.getLevel(EarthWorlds.dimension(target)).getBlockState(TARGET).is(Blocks.STONE),
                        "Released survival mining still removed canonical neighbor block");
                evidence.append("abortPreservedCanonicalBlock=true\n");
            });
            press(game.options.keyAttack); next(); return false;
        }
        if (step == 7) {
            if (!targetGone) {
                server(server -> {
                    targetGone = server.getLevel(EarthWorlds.dimension(target)).getBlockState(TARGET).isAir();
                    assertPose(player(server));
                });
                return false;
            }
            require(ticks > 10, "Survival block disappeared without required held mining");
            game.options.keyAttack.setDown(false); capture("mined");
            evidence.append("heldMiningTicks=").append(ticks).append(" canonicalBlockRemoved=true\n");
            server(server -> {
                var player = player(server);
                player.getInventory().setItem(0, new ItemStack(Items.RED_CONCRETE, 32)); player.containerMenu.broadcastChanges();
            });
            next(); return false;
        }
        if (step == 8) {
            if (!game.player.getMainHandItem().is(Items.RED_CONCRETE) || observed(Blocks.STONE) || ticks < 10) { return false; }
            press(game.options.keyUse); next(); return false;
        }
        if (step == 9) {
            game.options.keyUse.setDown(false);
            if (!placed) {
                server(server -> placed = server.getLevel(EarthWorlds.dimension(target)).getBlockState(TARGET).is(Blocks.RED_CONCRETE));
                return false;
            }
            if (!observed(Blocks.RED_CONCRETE) || game.player.getMainHandItem().getCount() == 32) { return false; }
            require(game.player.getMainHandItem().getCount() == 31, "Placement inventory did not follow the authoritative use");
            if (++placementObservedTicks < 20) { return false; }
            capture("placed"); evidence.append("canonicalPlacement=true remainingBlocks=31\n");
            server(server -> {
                assertPersisted(server);
                checkpoint.setProperty("version", "1"); checkpoint.setProperty("source", source.dimensionId());
                checkpoint.setProperty("target", target.dimensionId());
                checkpoint.setProperty("terrainVersion", Integer.toString(source.terrainVersion()));
                checkpoint.setProperty("block", TARGET.toShortString());
                checkpoint.setProperty("player", player(server).getUUID().toString());
                checkpoint.setProperty("remainingBlocks", "31");
                server.getPlayerList().saveAll(); server.saveEverything(false, true, true);
            });
            next(); return false;
        }
        if (!restart) {
            try (var output = Files.newOutputStream(game.gameDirectory.toPath()
                    .resolve("earth-interactions-checkpoint.properties"), StandardOpenOption.CREATE_NEW)) {
                checkpoint.store(output, "Actual canonical neighboring interaction and player save");
            }
        }
        game.options.keyAttack.setDown(false); game.options.keyUse.setDown(false); NeoForge.EVENT_BUS.unregister(receiver);
        var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        Files.writeString(directory.resolve(restart ? "earth-interactions-restart.txt" : "earth-interactions.txt"),
                evidence, StandardOpenOption.CREATE_NEW);
        return true;
    }

    private void assertPersisted(MinecraftServer server) {
        var neighbor = server.getLevel(EarthWorlds.dimension(target));
        require(neighbor.getBlockState(TARGET).is(Blocks.RED_CONCRETE)
                && neighbor.getBlockState(TARGET.above()).is(Blocks.STONE)
                && neighbor.getBlockState(TARGET.below()).isAir()
                && neighbor.getBlockState(DISTANT_TARGET).is(Blocks.STONE), "Canonical neighboring landmark changed");
        require(server.getLevel(EarthWorlds.dimension(source)).getBlockState(SOURCE_PROBE).isAir(),
                "Neighbor action left a source-world alias block");
        var player = player(server); assertPose(player);
        require(player.getMainHandItem().is(Items.RED_CONCRETE) && player.getMainHandItem().getCount() == 31,
                "Canonical placed-block inventory did not persist");
    }
    private void assertPose(ServerPlayer player) {
        require(player.serverLevel().dimension().equals(EarthWorlds.dimension(source)), "Interaction changed the player's host chart");
        require(player.position().distanceTo(SOURCE_FEET) < .1, "Interaction moved the authoritative player");
    }
    private static ServerPlayer player(MinecraftServer server) { return server.getPlayerList().getPlayers().getFirst(); }
    private static void invalidUse(EarthChart owner, BlockPos position, long revision) {
        PacketDistributor.sendToServer(new BoundaryInteractPayload(BoundaryInteractPayload.Action.USE,
                owner, position, InteractionHand.MAIN_HAND, revision));
    }
    private boolean observed(Block block) {
        if (observation == null) { return false; }
        for (var section : observation.sections()) {
            if (section.chart().equals(target) && section.section().asLong() == SectionPos.asLong(TARGET)) {
                int index = ((TARGET.getY() & 15) * 16 + (TARGET.getZ() & 15)) * 16 + (TARGET.getX() & 15);
                return Block.stateById(section.state(index)).is(block);
            }
        }
        return false;
    }
    private void next() {
        step++; ticks = 0; AstraEngine.LOGGER.info("ASTRA_INTERACTION_VERIFY step={} restart={}", step, restart);
    }
    private static void press(KeyMapping key) { key.setDown(true); KeyMapping.click(key.getKey()); }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void capture(String name) throws Exception {
        var path = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(path);
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(path.resolve("boundary-interaction-" + name + ".png"));
        }
    }
    private static void require(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
