package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.EarthBoundaryReceivedEvent;
import dev.lexawhatt.astraengine.server.EarthBoundaryCapture;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthBoundarySection;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.EarthChartTransform;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;

/** Actual server prefetch, wire delivery, immutable edit observations and owned-ticket retirement at real seams. */
final class EarthBoundaryScenario {
    private static final EarthChart SOURCE = new EarthChart(CubeFace.POSITIVE_X, 2, 2);
    private static final double RADIUS = EarthChart.RADIUS_METERS;
    private static final List<SpaceVector> ANCHORS = List.of(new SpaceVector(0, 2028, 0),
            new SpaceVector(RADIUS - 4, 1500, 0), new SpaceVector(RADIUS - 4, 2028, RADIUS - 4));
    private final Minecraft game = Minecraft.getInstance();
    private final Consumer<EarthBoundaryReceivedEvent> receiver = event -> received = event.payload().snapshot();
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private EarthBoundarySnapshot received;
    private EarthBoundarySection golden;
    private BlockPos marker;
    private boolean reloaded;
    private final boolean pack = System.getProperty("astraengine.verify.phase", "").endsWith("-pack");
    private int[] goldenPixel;
    private int index;
    private int stage;
    private long waitingSince = System.nanoTime();
    private long changedAfter;
    private int stableTicks;
    private final StringBuilder evidence = new StringBuilder();

    EarthBoundaryScenario() {
        game.options.cloudStatus().set(CloudStatus.OFF);
        game.options.renderDistance().set(3);
        game.options.hideGui = true;
        game.options.bobView().set(false);
        NeoForge.EVENT_BUS.addListener(receiver);
    }

    boolean tick() throws Exception {
        require(System.nanoTime() - waitingSince < 180_000_000_000L,
                "Timed out at boundary observation " + index + "/" + stage + ": " + received);
        if (!pending.isDone()) { return false; }
        pending.join();
        if (stage == 5) { return finishAfterPending(); }
        if (stage == 0) {
            require(dev.lexawhatt.astraengine.client.compat.RenderCompatibility.shaderPackActive() == pack,
                    "Boundary material fixture does not match actual shader-pack ownership");
            received = null;
            var anchor = ANCHORS.get(index);
            server(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(server.getLevel(EarthWorlds.dimension(SOURCE)), anchor.x(), anchor.y(), anchor.z(), 0, 0);
                player.getAbilities().flying = true; player.onUpdateAbilities();
            });
            advance(1); return false;
        }
        if (stage == 1) {
            if (received == null || !received.complete() || received.sections().isEmpty()
                    || received.anchorFeet().distance(ANCHORS.get(index)) > 1) { return false; }
            require(received.source().equals(SOURCE), "Observation lost its canonical source");
            var neighbor = received.sections().stream().filter(section -> !section.chart().equals(SOURCE))
                    .findFirst().orElseThrow();
            marker = null;
            var corner = neighbor.section().origin();
            for (int y = 4; y < 12 && marker == null; y++) {
                for (int z = 4; z < 12 && marker == null; z++) {
                    for (int x = 4; x < 12 && marker == null; x++) {
                        var point = corner.offset(x, y, z);
                        if (neighbor.chart().contains(new SpaceVector(point.getX() + .5, point.getY() + .5, point.getZ() + .5))) {
                            marker = point;
                        }
                    }
                }
            }
            require(marker != null, "Native marker requires a canonical neighboring voxel");
            changedAfter = received.revision();
            server(server -> {
                require(ownedTickets(server) > 0, "Actual production owner did not prefetch the observed charts");
                var level = server.getLevel(EarthWorlds.dimension(neighbor.chart()));
                require(level.getChunkSource().getChunkNow(marker.getX() >> 4, marker.getZ() >> 4) != null,
                        "Observation did not represent an actually loaded canonical chunk");
                level.setBlockAndUpdate(marker, Blocks.GOLD_BLOCK.defaultBlockState());
                var player = server.getPlayerList().getPlayers().getFirst();
                var point = new EarthChartTransform(neighbor.chart(), SOURCE)
                        .position(new SpaceVector(marker.getX() + .5, marker.getY() + .5, marker.getZ() + .5));
                double dx = point.x() - player.getX(), dy = point.y() - player.getEyeY(), dz = point.z() - player.getZ();
                player.teleportTo(player.getX(), player.getY(), player.getZ());
                player.connection.teleport(player.getX(), player.getY(), player.getZ(),
                        (float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
            });
            advance(2); return false;
        }
        if (stage == 2) {
            var found = observed(Blocks.GOLD_BLOCK);
            if (found == null || received.revision() <= changedAfter) { return false; }
            if (++stableTicks < 35) { return false; }
            if (index == 0 && !reloaded) {
                reloaded = true; stableTicks = 0; pending = game.reloadResourcePacks(); return false;
            }
            goldenPixel = capture("gold");
            require(goldenPixel[0] > goldenPixel[2] + 25 && goldenPixel[1] > goldenPixel[2] + 25,
                    "Neighboring gold block was not visible at screen center: " + java.util.Arrays.toString(goldenPixel));
            golden = found;
            changedAfter = received.revision();
            server(server -> {
                var level = server.getLevel(EarthWorlds.dimension(golden.chart()));
                var actual = EarthBoundaryCapture.capture(level, golden.chart(), golden.section()).orElseThrow();
                require(actual.sameContents(golden), "Delivered state/light/biomes differ from actual saved chunks");
                level.setBlockAndUpdate(marker, Blocks.EMERALD_BLOCK.defaultBlockState());
            });
            advance(3); return false;
        }
        if (stage == 3) {
            if (observed(Blocks.EMERALD_BLOCK) == null || received.revision() <= changedAfter) { return false; }
            if (++stableTicks < 35) { return false; }
            int[] emerald = capture("emerald");
            require(emerald[1] > emerald[0] + 25 && emerald[1] > emerald[2] + 15,
                    "Updated neighboring emerald block was not visible: " + java.util.Arrays.toString(emerald));
            require(golden.state(cell(marker)) == Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()),
                    "Later block changes mutated an earlier immutable observation");
            evidence.append("case=").append(index).append(" anchor=").append(received.anchorFeet())
                    .append(" sections=").append(received.sections().size()).append(" marker=").append(marker)
                    .append(" goldRGB=").append(java.util.Arrays.toString(goldenPixel)).append(" emeraldRGB=").append(java.util.Arrays.toString(emerald))
                    .append(" neighbor=").append(golden.chart()).append(" revision=").append(received.revision()).append('\n');
            server(server -> {
                var anchor = ANCHORS.get(index);
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(server.getLevel(EarthWorlds.dimension(SOURCE)), Math.min(anchor.x(), RADIUS - 100),
                        Math.min(anchor.y(), 1800), Math.min(anchor.z(), RADIUS - 100), 0, 0);
            });
            advance(4); return false;
        }
        if (received == null || !received.sections().isEmpty()) { return false; }
        if (++stableTicks < 10) { return false; }
        server(server -> require(ownedTickets(server) == 0, "Leaving the boundary retained owned loading tickets"));
        if (++index < ANCHORS.size()) { advance(0); return false; }
        advance(5);
        return finishAfterPending();
    }

    private boolean finishAfterPending() throws Exception {
        // Completion is checked by a separate client callback once the final server assertion finishes.
        if (!pending.isDone()) { return false; }
        pending.join();
        var output = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(output);
        Files.writeString(output.resolve("earth-boundary-observations.txt"), evidence.toString(), StandardOpenOption.CREATE_NEW);
        NeoForge.EVENT_BUS.unregister(receiver);
        return true;
    }

    private int[] capture(String material) throws Exception {
        var output = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(output);
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(output.resolve("boundary-" + index + "-" + material + ".png"));
            int value = image.getPixelRGBA(image.getWidth() / 2, image.getHeight() / 2);
            return new int[] {value & 255, value >> 8 & 255, value >> 16 & 255};
        }
    }

    private EarthBoundarySection observed(Block block) {
        if (received == null) { return null; }
        return received.sections().stream().filter(section -> section.section().equals(net.minecraft.core.SectionPos.of(marker))
                && section.state(cell(marker)) == Block.getId(block.defaultBlockState())).findFirst().orElse(null);
    }

    private static int cell(BlockPos point) { return ((point.getY() & 15) * 16 + (point.getZ() & 15)) * 16 + (point.getX() & 15); }

    private static int ownedTickets(MinecraftServer server) {
        int count = 0;
        try {
            for (ServerLevel level : server.getAllLevels()) {
                Object distance = field(level.getChunkSource(), "distanceManager");
                var tickets = (Map<?, ?>) field(distance, "tickets");
                for (Object value : tickets.values()) {
                    for (Object ticket : (Iterable<?>) value) {
                        if (((Ticket<?>) ticket).getType().toString().equals("astraengine_boundary")) { count++; }
                    }
                }
            }
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
        return count;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
            catch (NoSuchFieldException ignored) { /* Continue to the declaring host superclass. */ }
        }
        throw new NoSuchFieldException(name);
    }
    private void advance(int value) { stage = value; stableTicks = 0; waitingSince = System.nanoTime(); }
    private void server(Consumer<MinecraftServer> operation) {
        var server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> operation.accept(server), server);
    }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalStateException(message); } }
}
