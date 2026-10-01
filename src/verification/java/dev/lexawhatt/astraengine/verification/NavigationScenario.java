package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.map.NavigationMapAccess;
import dev.lexawhatt.astraengine.client.map.NavigationMapOpeningEvent;
import dev.lexawhatt.astraengine.client.map.NavigationMapSnapshot;
import dev.lexawhatt.astraengine.cosmos.NavigationPolicy;
import dev.lexawhatt.astraengine.network.ExplorationPayload;
import dev.lexawhatt.astraengine.network.ExplorationReceivedEvent;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.opengl.GL11;

/** A consumer uses only the public map handle; production packets and actual login/reload exercise authority. */
final class NavigationScenario {
    private final Minecraft game = Minecraft.getInstance();
    private final Consumer<NavigationMapOpeningEvent> opening = this::opening;
    private final Consumer<ExplorationReceivedEvent> snapshots = this::receive;
    private final StringBuilder evidence = new StringBuilder("step,clock,system,remaining,target,visited\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private NavigationMapAccess access;
    private NavigationMapAccess retired;
    private ExplorationPayload latest;
    private RuntimeException failure;
    private int step;
    private int ticks;
    private int openingCount;
    private int completedTimedRoutes;
    private long routeStart;
    private String timedTarget = "";
    private String routeIdentity = "";

    NavigationScenario() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, opening);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, snapshots);
    }

    boolean reconnecting() { return step >= 22; }

    boolean tick() throws Exception {
        if (failure != null) { throw failure; }
        if (!pending.isDone()) { return false; }
        pending.join();
        require(++ticks < 1800, "Navigation step timed out");
        switch (step) {
            case 0 -> { command("astra-flight map"); next(); }
            case 1 -> {
                if (!consumerVisible() || access.snapshot().isEmpty() || ticks < 5) { return false; }
                require(view().policy().equals(NavigationPolicy.DEFAULT), "Fresh policy is not default");
                require(!access.request(NavigationMapAccess.Action.JUMP_SYSTEM, "u_0_0"), "Inactive jump allowed");
                shot("navigation-01-consumer-map"); game.setScreen(null); command("astra-flight"); next();
            }
            case 2 -> {
                if (!view().flying() || ticks < 20) { return false; }
                require(!access.request(NavigationMapAccess.Action.JUMP_SYSTEM, "u_0_0"), "Unvisited jump queued");
                // Deliberately bypass consumer-side eligibility to exercise the actual server permission gate.
                PacketDistributor.sendToServer(new FlightActionPayload(FlightActionPayload.Action.JUMP_SYSTEM, "u_0_0"));
                next();
            }
            case 3 -> {
                if (ticks < 15) { return false; }
                require(view().system().id().equals("sol") && view().remainingTicks() == 0
                        && !view().visitedSystems().contains("u_0_0"), "Server accepted an unauthorized jump");
                rules(true, 1); next();
            }
            case 4 -> {
                if (!view().policy().equals(new NavigationPolicy(true, 1))) { return false; }
                require(view().visitedSystems().size() == 1, "Enabling cheats fabricated visits");
                require(!access.request(NavigationMapAccess.Action.JUMP_SYSTEM, "missing:system"), "Missing custom ID queued");
                PacketDistributor.sendToServer(new FlightActionPayload(FlightActionPayload.Action.JUMP_SYSTEM, "missing:system"));
                next();
            }
            case 5 -> {
                if (ticks < 10) { return false; }
                require(view().system().id().equals("sol") && view().remainingTicks() == 0, "Missing custom ID generated a fallback");
                timedTarget = "sol/saturn";
                require(access.request(NavigationMapAccess.Action.APPROACH_BODY, "saturn"), "Consumer approach unavailable");
                next();
            }
            case 6 -> {
                if (completedTimedRoutes < 1 || !view().canStartRoute() || ticks < 25) { return false; }
                timedTarget = "u_0_0";
                require(access.request(NavigationMapAccess.Action.JUMP_SYSTEM, timedTarget), "Cheat jump unavailable"); next();
            }
            case 7 -> {
                if (completedTimedRoutes < 2 || !view().system().id().equals("u_0_0")) { return false; }
                require(view().visitedSystems().contains("u_0_0"), "Arrival did not record a visit");
                require(access.open(NavigationMapAccess.View.ATLAS), "Consumer atlas switch failed"); next();
            }
            case 8 -> {
                if (ticks < 6 || !consumerVisible()) { return false; }
                require(game.screen instanceof ConsumerMap screen && screen.view == NavigationMapAccess.View.ATLAS,
                        "Atlas bypassed map replacement");
                shot("navigation-02-consumer-atlas");
                retired = access;
                pending = game.reloadResourcePacks(); next();
            }
            case 9 -> {
                require(retired.snapshot().isPresent() && retired.snapshot().orElseThrow().policy().freeNavigation(),
                        "Resource reload lost connection policy or handle");
                require(access.open(NavigationMapAccess.View.SYSTEM), "Post-reload map switch failed");
                rules(true, 3); game.setScreen(null); next();
            }
            case 10 -> {
                if (view().policy().travelSeconds() != 3 || ticks < 8) { return false; }
                require(access.request(NavigationMapAccess.Action.JUMP_SYSTEM, "u_1_0"), "Cancel fixture route unavailable"); next();
            }
            case 11 -> {
                if (view().remainingTicks() == 0 || ticks < 6) { return false; }
                require(!view().visitedSystems().contains("u_1_0"), "Pending route recorded a visit");
                require(access.request(NavigationMapAccess.Action.CANCEL_ROUTE, ""), "Cancel request unavailable"); next();
            }
            case 12 -> {
                if (ticks < 12 || view().remainingTicks() != 0) { return false; }
                require(view().system().id().equals("u_0_0") && !view().visitedSystems().contains("u_1_0"),
                        "Canceled route arrived or fabricated a visit");
                rules(true, 1); next();
            }
            case 13 -> {
                if (view().policy().travelSeconds() != 1 || ticks < 6) { return false; }
                timedTarget = "sol";
                require(access.request(NavigationMapAccess.Action.JUMP_SYSTEM, "sol"), "Return jump unavailable"); next();
            }
            case 14 -> {
                if (view().remainingTicks() == 0) { return false; }
                // Accepted routes retain their captured duration when a live operator changes the rule.
                rules(false, 3); next();
            }
            case 15 -> {
                if (completedTimedRoutes < 3 || !view().system().id().equals("sol") || ticks < 10) { return false; }
                require(view().policy().equals(new NavigationPolicy(false, 3)), "Rule update not synchronized");
                require(!access.request(NavigationMapAccess.Action.JUMP_SYSTEM, "u_1_0"), "Disabled cheat remained active");
                pending = CompletableFuture.runAsync(() -> {
                    boolean rejected = false;
                    try { access.snapshot(); } catch (IllegalStateException expected) { rejected = true; }
                    require(rejected, "Worker thread obtained a live map view");
                }); next();
            }
            case 16 -> {
                require(access.open(NavigationMapAccess.View.SYSTEM), "Consumer map could not reopen"); next();
            }
            case 17 -> {
                if (ticks < 8 || !consumerVisible()) { return false; }
                shot("navigation-03-returned"); game.setScreen(null); command("astra-flight"); next();
            }
            case 18 -> {
                if (view().flying() || ticks < 12) { return false; }
                rules(true, 1); next();
            }
            case 19 -> {
                if (!view().policy().equals(new NavigationPolicy(true, 1))) { return false; }
                require(openingCount >= 4, "Engine entry points did not emit replacement events");
                retired = access; next();
            }
            case 20 -> { next(); }
            case 21 -> {
                next(); game.level.disconnect(); game.disconnect(new TitleScreen());
            }
            case 22 -> {
                if (game.getOverlay() != null || game.level != null || ticks < 10) { return false; }
                require(retired.snapshot().isEmpty() && !retired.open(NavigationMapAccess.View.SYSTEM)
                        && !retired.request(NavigationMapAccess.Action.SCAN, ""), "Logout left a usable map handle");
                game.createWorldOpenFlows().openWorld("first-slice", () -> { throw new IllegalStateException("Reopen failed"); });
                next();
            }
            case 23 -> {
                if (game.screen instanceof BackupConfirmScreen screen) {
                    var button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(b -> b.getMessage().getString().equals(Component.translatable(
                                    "selectWorld.backupJoinSkipButton").getString())).findFirst().orElseThrow();
                    screen.mouseClicked(button.getX() + 2, button.getY() + 2, 0);
                    screen.mouseReleased(button.getX() + 2, button.getY() + 2, 0);
                }
                if (game.player == null || game.getOverlay() != null) { return false; }
                command("astra-flight map"); next();
            }
            case 24 -> {
                if (!consumerVisible() || access == retired || ticks < 8) { return false; }
                require(retired.snapshot().isEmpty() && !retired.request(NavigationMapAccess.Action.SCAN, ""),
                        "An old connection handle revived after reconnect");
                require(view().policy().equals(new NavigationPolicy(true, 1)), "Saved rules did not survive reopen");
                require(view().visitedSystems().contains("u_0_0") && !view().visitedSystems().contains("u_1_0"),
                        "Visited/canceled destinations changed after reopen");
                shot("navigation-04-reconnected");
                Files.writeString(game.gameDirectory.toPath().resolve("evidence/navigation.csv"), evidence.toString());
                NeoForge.EVENT_BUS.unregister(opening); NeoForge.EVENT_BUS.unregister(snapshots);
                return true;
            }
            default -> throw new IllegalStateException("Unknown navigation fixture step");
        }
        return false;
    }

    private void opening(NavigationMapOpeningEvent event) {
        access = event.access(); openingCount++;
        event.setScreen(new ConsumerMap(access, event.view()));
    }

    private void receive(ExplorationReceivedEvent event) {
        latest = event.payload();
        evidence.append(step).append(',').append(latest.clockTicks()).append(',').append(latest.systemId())
                .append(',').append(latest.jumpTicks()).append(',').append(latest.jumpTarget()).append(',')
                .append(latest.visitedSystems()).append('\n');
        try {
            if (!timedTarget.isEmpty() && latest.jumpTarget().equals(timedTarget) && routeIdentity.isEmpty()) {
                require(latest.jumpTicks() == 20, "Accepted one-second route did not start at 20 ticks");
                routeStart = latest.clockTicks(); routeIdentity = timedTarget;
            }
            if (!routeIdentity.isEmpty() && latest.jumpTicks() == 0) {
                require(latest.clockTicks() - routeStart == 20, "Route changed its fixed 20-tick duration");
                completedTimedRoutes++; routeIdentity = ""; timedTarget = "";
            }
        } catch (RuntimeException error) { failure = error; }
    }

    private NavigationMapSnapshot view() { return access.snapshot().orElseThrow(); }
    private boolean consumerVisible() {
        return game.getOverlay() == null && game.screen instanceof ConsumerMap screen && screen.frames >= 3;
    }
    private void command(String command) { game.player.connection.sendCommand(command); }
    private void rules(boolean free, int seconds) {
        MinecraftServer server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> {
            var source = server.createCommandSourceStack().withPermission(2).withSuppressedOutput();
            try {
                server.getCommands().getDispatcher().execute("gamerule astraFreeNavigation " + free, source);
                server.getCommands().getDispatcher().execute("gamerule astraTravelSeconds " + seconds, source);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) { throw new IllegalStateException(error); }
        }, server);
    }
    private void shot(String name) throws Exception {
        var path = game.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(path);
            int crispText = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getPixelRGBA(x, y);
                    if ((pixel & 255) >= 240 && ((pixel >>> 8) & 255) >= 240 && ((pixel >>> 16) & 255) >= 240) {
                        crispText++;
                    }
                }
            }
            require(crispText > 100, "Consumer map text is absent or blurred by its background");
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error in consumer map");
    }
    private void next() { AstraEngine.LOGGER.info("ASTRA_NAVIGATION_STEP {} complete", step++); ticks = 0; }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " at navigation step " + step); }
    }

    private static final class ConsumerMap extends Screen {
        private final NavigationMapAccess access;
        private final NavigationMapAccess.View view;
        private int frames;
        private ConsumerMap(NavigationMapAccess access, NavigationMapAccess.View view) {
            super(Component.literal("Consumer navigation API fixture")); this.access = access; this.view = view;
        }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            frames++;
            graphics.fill(0, 0, width, height, 0xE8102030);
            graphics.drawCenteredString(font, title, width / 2, 25, 0xFFFFFF);
            graphics.drawString(font, "View: " + view, 20, 60, 0x80D0FF);
            access.snapshot().ifPresent(snapshot -> {
                graphics.drawString(font, "System: " + snapshot.system().name(), 20, 85, 0xFFFFFF);
                graphics.drawString(font, "Navigation: " + snapshot.policy(), 20, 110, 0xFFFFFF);
                graphics.drawString(font, "Visited: " + snapshot.visitedSystems(), 20, 135, 0xFFFFFF);
            });
            super.render(graphics, mouseX, mouseY, partialTick);
        }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }
    }
}
