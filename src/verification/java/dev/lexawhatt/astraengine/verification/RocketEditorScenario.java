package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.rocket.RocketEditorClient;
import dev.lexawhatt.astraengine.client.rocket.RocketEditorScreen;
import dev.lexawhatt.astraengine.network.RocketEditorCommandPayload;
import dev.lexawhatt.astraengine.network.RocketEditorStatePayload;
import dev.lexawhatt.astraengine.network.RocketEditorStateReceivedEvent;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import dev.lexawhatt.astraengine.rocket.RocketPart;
import dev.lexawhatt.astraengine.rocket.RocketPlacement;
import dev.lexawhatt.astraengine.server.rocket.RocketAssemblyEntity;
import dev.lexawhatt.astraengine.server.rocket.RocketBlueprintCodec;
import dev.lexawhatt.astraengine.server.rocket.RocketEditorBlockEntity;
import dev.lexawhatt.astraengine.server.rocket.RocketEditorSessions;
import dev.lexawhatt.astraengine.server.rocket.RocketWorkshop;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Real construction widgets and own-player packets, deployed analytic geometry, collision and exact restart data. */
final class RocketEditorScenario {
    private static final BlockPos HOST = new BlockPos(0, 200, 0);
    private static final String CUSTOM = "verification:wormhole_core";
    private static final String MODULE = "verification:wormhole";
    private final Minecraft minecraft = Minecraft.getInstance();
    private final boolean restart;
    private final StringBuilder observations = new StringBuilder("step\tstatus\trevision\tparts\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private CompletableFuture<?> observedCompletion = pending;
    private RocketEditorStatePayload received;
    private RocketEditorClient editor;
    private RocketBlueprint expected;
    private Properties checkpoint;
    private UUID assemblyId;
    private AABB collisionBox;
    private int[] previewPixels;
    private int[] reloadPixels;
    private int starterCount;
    private int customId;
    private int step;
    private int ticks;
    private double resizedWidth;
    private long renderedFrames;
    private long minimumPresentedFrame;
    private long acknowledgementFrame;
    private int presentationStep = -1;
    private long captureAfterFrame;
    private long chainedRevision;
    private boolean chainedDeployment;
    private RocketEditorStatePayload chainedSave;
    private static final String COOLDOWN_OBSERVER = "astra_verify_rocket_cooldown";
    private final AtomicInteger cooldownSavePackets = new AtomicInteger();
    private final AtomicInteger cooldownDeployPackets = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> cooldownPackets = new ConcurrentLinkedQueue<>();
    private Channel cooldownChannel;
    private boolean cooldownRegression;
    private int cooldownRejections;
    private long cooldownRevision;

    RocketEditorScenario(boolean restart) {
        this.restart = restart;
        minecraft.options.hideGui = false;
        minecraft.options.fov().set(70);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (RenderFrameEvent.Post event) -> {
            if (minecraft.level != null && minecraft.getOverlay() == null) { renderedFrames++; }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (RocketEditorStateReceivedEvent event) -> {
            received = event.payload();
            acknowledgementFrame = renderedFrames;
            if (chainedDeployment && received.status() == RocketEditorStatePayload.Status.SAVED) { chainedSave = received; }
            if (cooldownRegression && received.status() == RocketEditorStatePayload.Status.RATE_LIMITED) { cooldownRejections++; }
            observations.append(step).append('\t').append(received.status()).append('\t')
                    .append(received.revision()).append('\t').append(received.blueprint().parts().size()).append('\n');
            AstraEngine.LOGGER.info("ASTRA_ROCKET_EDITOR_ACK step={} status={} revision={} parts={} frame={}",
                    step, received.status(), received.revision(), received.blueprint().parts().size(), renderedFrames);
        });
        AstraEngine.LOGGER.info("ASTRA_ROCKET_EDITOR_BEGIN restart={} graphics={}", restart, minecraft.options.graphicsMode().get());
    }

    boolean tick() throws Exception {
        try {
            if (!pending.isDone()) { return false; }
            pending.join();
            require(++ticks < 300, "Rocket editor step timed out: " + diagnostics());
            if (observedCompletion != pending) {
                observedCompletion = pending;
                minimumPresentedFrame = renderedFrames + 2;
            }
            if (renderedFrames < minimumPresentedFrame) { return false; }
            return restart ? restartTick() : createTick();
        } catch (Exception failure) {
            hold(GLFW.GLFW_KEY_W, false);
            removeCooldownObserver();
            AstraEngine.LOGGER.error("ASTRA_ROCKET_EDITOR_FAILURE {}", diagnostics(), failure);
            try {
                writeEvidence();
                Files.writeString(minecraft.gameDirectory.toPath().resolve("evidence/"
                        + (restart ? "rocket-editor-restart-failure.txt" : "rocket-editor-failure.txt")),
                        failure + "\n" + diagnostics() + "\n");
            } catch (Exception evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            throw failure;
        }
    }

    private String diagnostics() {
        return "step=" + step + ", ticks=" + ticks + ", frame=" + renderedFrames
                + ", received=" + (received == null ? "none" : received.status() + " revision=" + received.revision())
                + ", editor=" + (editor == null ? "none" : "feedback=" + editor.feedback().getString()
                        + ", busy=" + editor.busy() + ", dirty=" + editor.dirty());
    }

    private boolean createTick() throws Exception {
        switch (step) {
            case 0 -> { server(this::prepareWorld); next(); }
            case 1 -> { if (ticks < 25) { return false; } openHost(); next(); }
            case 2 -> {
                if (!(minecraft.screen instanceof RocketEditorScreen) || ticks < 8) { return false; }
                editor = screen().controller();
                require(editor.renderer().ready(), "Registered rocket shader is unavailable");
                RocketPickingChecks.verify(editor.renderer());
                observations.append(step).append("\tpicking_checks\t98\t0\n");
                require(editor.blueprint().parts().isEmpty(), "Fresh editor unexpectedly contains a saved draft");
                clickKey("starter"); starterCount = editor.blueprint().parts().size();
                require(starterCount >= 3, "Starter did not create a connected assembly"); next();
            }
            case 3 -> {
                if (ticks < 8 || !presented()) { return false; }
                shot("rocket-editor-01-starter"); previewPixels = viewportPixels();
                RocketEditorScreen screen = screen(); double[] center = viewportCenter(screen);
                int revision = editor.revision();
                require(screen.mouseClicked(center[0], center[1], 0), "Preview selection was not handled");
                screen.mouseReleased(center[0], center[1], 0);
                require(editor.selected() != null && editor.revision() > revision, "Preview click did not select a real part");
                require(screen.mouseClicked(center[0], center[1], 1), "Orbit did not capture the right mouse button");
                require(screen.mouseDragged(center[0] + 65, center[1] - 20, 1, 65, -20), "Orbit drag was not handled");
                screen.mouseReleased(center[0] + 65, center[1] - 20, 1);
                require(screen.mouseScrolled(center[0], center[1], 0, 1), "Preview zoom was not handled"); next();
            }
            case 4 -> {
                if (ticks < 8 || !presented()) { return false; }
                require(pixelDifference(previewPixels, viewportPixels()) > 100, "Orbit/zoom did not change the rendered preview");
                shot("rocket-editor-02-orbit");
                selectAssemblyPart(editor.blueprint().parts().stream()
                        .filter(part -> part.definitionId().equals("astraengine:fuel_tank")).findFirst().orElseThrow());
                showLibrary(); input(key("search"), "solar"); selectDefinition("astraengine:solar_panel");
                chooseAttachment("RADIAL"); clickKey("symmetry", 1); clickKey("symmetry", 2); clickKey("add");
                require(editor.blueprint().parts().size() == starterCount + 4, "Four-way radial symmetry did not add four modules");
                next();
            }
            case 5 -> {
                clickKey("delete"); require(editor.blueprint().parts().size() == starterCount + 3, "Delete did not remove the selected leaf");
                clickKey("undo"); require(editor.blueprint().parts().size() == starterCount + 4, "Undo did not restore the removed module");
                clickKey("redo"); require(editor.blueprint().parts().size() == starterCount + 3, "Redo did not reapply removal");
                clickKey("undo");
                selectAssemblyPart(editor.blueprint().parts().getFirst()); showLibrary();
                input(key("search"), "wormhole"); selectDefinition(CUSTOM); chooseAttachment("TOP");
                clickKey("symmetry", 4); clickKey("add");
                require(editor.selected().definitionId().equals(CUSTOM), "Generic library failed to add the consumer definition");
                customId = editor.selected().id();
                clickKey("page.1"); resizedWidth = editor.selected().size().x() * 1.25;
                input(key("field.width"), Double.toString(resizedWidth)); clickKey("apply");
                require(editor.selected().size().x() == resizedWidth, "Geometry width was not applied");
                clickKey("page.0"); input(key("field.yaw"), "1"); clickKey("apply");
                require(editor.selected().yawQuarterTurns() == 1, "Part quarter-turn yaw was not applied"); next();
            }
            case 6 -> {
                clickKey("page.2");
                var definition = editor.catalog().requireDefinition(CUSTOM).modules().stream()
                        .filter(module -> module.id().equals(MODULE)).findFirst().orElseThrow();
                for (int attempt = 0; attempt < 8 && !hasLabel(definition.displayName()); attempt++) {
                    Button module = buttons().stream().filter(button -> button.getY() == 98 && button.getX() > 140).findFirst().orElseThrow();
                    click(module);
                }
                require(hasLabel(definition.displayName()), "Consumer module page is unavailable");
                var number = definition.parameters().stream().filter(parameter -> parameter.key().equals("range_ly")).findFirst().orElseThrow();
                String numberLabel = number.displayName() + (number.unit().isEmpty() ? "" : " (" + number.unit() + ")");
                RocketBlueprint before = editor.blueprint(); input(numberLabel, "NaN"); clickKey("apply");
                require(editor.blueprint().equals(before), "Rejected nonfinite input changed the draft");
                input(numberLabel, "123.5");
                var flag = definition.parameters().stream().filter(parameter -> parameter.key().equals("stabilized")).findFirst().orElseThrow();
                var choice = definition.parameters().stream().filter(parameter -> parameter.key().equals("transit_mode")).findFirst().orElseThrow();
                clickLabel(flag.displayName() + ": false"); clickLabel(choice.displayName() + ": warp"); clickKey("apply");
                verifyConsumer(editor.selected());
                input(key("name"), "Verified modular explorer"); clickKey("save"); next();
            }
            case 7 -> {
                if (!ack(RocketEditorStatePayload.Status.SAVED) || ticks < 8 || !presented()) { return false; }
                require(!editor.dirty(), "Save acknowledgement left the unchanged draft dirty");
                expected = editor.blueprint(); verifyConsumer(expected.requirePart(customId));
                shot("rocket-editor-03-consumer-module"); reloadPixels = viewportPixels();
                pending = minecraft.reloadResourcePacks(); next();
            }
            case 8 -> {
                if (ticks < 12 || !editor.renderer().ready() || !presented()) { return false; }
                require(editor.blueprint().equals(expected), "Resource reload lost the connection-owned draft");
                require(pixelDifference(reloadPixels, viewportPixels()) == 0, "Resource reload changed the deterministic editor viewport");
                shot("rocket-editor-04-reloaded");
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 960, 540); go(80);
            }
            case 80 -> {
                if (ticks < 8 || !windowSize(960, 540) || !presented()) { return false; }
                require(screen().width < 600 || screen().height < 360, "Reduced window did not enter compact UI");
                require(hasLabel(key("smaller_ui")) && !hasLabel(key("deploy")), "Compact UI did not expose its recovery control");
                require(editor.blueprint().equals(expected), "Compact resize changed the draft");
                assertWidgetBounds(); shot("rocket-editor-04a-compact"); clickKey("smaller_ui"); go(81);
            }
            case 81 -> {
                if (ticks < 8 || minecraft.getWindow().getGuiScale() != 1 || !presented()) { return false; }
                require(screen().width >= 600 && screen().height >= 360 && hasLabel(key("deploy")),
                        "Smaller UI control failed to restore the authoring interface");
                require(editor.blueprint().equals(expected), "GUI scale recovery changed the draft");
                assertWidgetBounds(); shot("rocket-editor-04b-resized");
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 1280, 720);
                minecraft.options.guiScale().set(2); minecraft.resizeDisplay(); go(82);
            }
            case 82 -> {
                if (ticks < 8 || !windowSize(1280, 720) || minecraft.getWindow().getGuiScale() != 2 || !presented()) { return false; }
                require(editor.blueprint().equals(expected), "Restoring the window changed the draft");
                assertWidgetBounds();
                require(pixelDifference(reloadPixels, viewportPixels()) == 0, "Restored preview attachments changed the deterministic image");
                shot("rocket-editor-04c-restored");
                clickKey("page.1"); resizedWidth += 0.25;
                input(key("field.width"), Double.toString(resizedWidth)); clickKey("apply");
                require(editor.dirty() && editor.selected().size().x() == resizedWidth,
                        "Geometry edit did not create a dirty draft for chained deployment");
                expected = editor.blueprint(); go(83);
            }
            case 83 -> {
                if (ticks < 8 || !presented()) { return false; }
                shot("rocket-editor-04d-dirty");
                chainedRevision = received.revision(); chainedDeployment = true; chainedSave = null;
                clickKey("deploy"); go(9);
            }
            case 9 -> {
                if (!ack(RocketEditorStatePayload.Status.DEPLOYED) || ticks < 15 || !presented()) { return false; }
                require(chainedSave != null && chainedSave.revision() == chainedRevision + 1
                                && chainedSave.blueprint().equals(expected) && received.revision() == chainedSave.revision()
                                && !editor.dirty(), "Dirty deployment did not wait for the exact saved revision");
                shot("rocket-editor-04e-deployed");
                screen().onClose(); server(server -> {
                    var host = host(server); assemblyId = host.deployedAssembly().orElseThrow();
                    RocketAssemblyEntity assembly = assembly(server);
                    require(assembly.blueprint().equals(expected), "Deployed server assembly differs from the saved draft");
                    require(assembly.activeHitboxCount() > expected.parts().size(), "Frustums lack conservative collision slices");
                    viewAssembly(server, assembly);
                }); next();
            }
            case 10 -> {
                if (ticks < 25 || !presented()) { return false; }
                require(editor.renderer().worldAssemblyCount() == 1
                                && editor.renderer().worldPartCount() == expected.parts().size(),
                        "World pass did not submit the synchronized assembly");
                require(clientAssembly().blueprint().equals(expected), "Client entity did not receive exact module values");
                shot("rocket-editor-05-world"); pending = minecraft.reloadResourcePacks(); next();
            }
            case 11 -> {
                if (ticks < 15 || !presented()) { return false; }
                require(editor.renderer().ready() && editor.renderer().worldPartCount() == expected.parts().size(),
                        "Reload failed to rebuild the world shader pass");
                shot("rocket-editor-06-world-reloaded");
                server(server -> prepareCollision(server, assembly(server))); next();
            }
            case 12 -> {
                if (ticks < 15) { return false; }
                minecraft.player.setYRot(-90); minecraft.player.setXRot(0);
                hold(GLFW.GLFW_KEY_W, true); next();
            }
            case 13 -> {
                minecraft.player.setYRot(-90); minecraft.player.setXRot(0);
                if (ticks < 45) { return false; }
                hold(GLFW.GLFW_KEY_W, false);
                if (!presented()) { return false; }
                require(minecraft.player.getBoundingBox().maxX <= collisionBox.minX + 0.02
                                && minecraft.player.getBoundingBox().maxX >= collisionBox.minX - 0.15,
                        "Held forward movement did not stop at a close collision slice: " + minecraft.player.getBoundingBox());
                shot("rocket-editor-07-close-collision");
                server(server -> { verifyPersisted(server); viewAssembly(server, assembly(server)); }); next();
            }
            case 14 -> {
                if (ticks < 15) { return false; }
                checkpoint = new Properties(); checkpoint.setProperty("blueprint", RocketBlueprintCodec.encode(expected).toString());
                checkpoint.setProperty("assembly", assemblyId.toString()); checkpoint.setProperty("custom_id", Integer.toString(customId));
                server(server -> checkpoint.setProperty("revision", Long.toString(host(server).revision()))); next();
            }
            case 15 -> {
                StringWriter text = new StringWriter(); checkpoint.store(text, "Saved modular rocket and persistent deployed entity identity");
                Files.writeString(checkpointPath(), text.toString()); writeEvidence();
                AstraEngine.LOGGER.info("ASTRA_ROCKET_EDITOR_PASSED restart=false"); return true;
            }
            default -> throw new IllegalStateException("Unexpected rocket editor step " + step);
        }
        return false;
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                if (ticks < 20) { return false; }
                checkpoint = new Properties(); checkpoint.load(new StringReader(Files.readString(checkpointPath())));
                expected = RocketBlueprintCodec.decode(TagParser.parseTag(checkpoint.getProperty("blueprint")));
                assemblyId = UUID.fromString(checkpoint.getProperty("assembly")); customId = Integer.parseInt(checkpoint.getProperty("custom_id"));
                server(server -> { verifyPersisted(server); viewAssembly(server, assembly(server)); }); next();
            }
            case 1 -> {
                if (ticks < 25 || !presented()) { return false; }
                require(clientAssembly().blueprint().equals(expected), "Restart client assembly differs from the saved design");
                shot("rocket-editor-restart-01-world");
                server(server -> positionAtHost(server.getPlayerList().getPlayers().getFirst())); next();
            }
            case 2 -> { if (ticks < 15) { return false; } openHost(); next(); }
            case 3 -> {
                if (!(minecraft.screen instanceof RocketEditorScreen) || ticks < 10) { return false; }
                editor = screen().controller();
                require(editor.blueprint().equals(expected) && !editor.dirty(), "Restart lost saved authoring parameters");
                require(received.revision() == Long.parseLong(checkpoint.getProperty("revision")), "Restart changed saved revision");
                verifyConsumer(editor.blueprint().requirePart(customId));
                selectAssemblyPart(editor.blueprint().requirePart(customId)); clickKey("page.2"); next();
            }
            case 4 -> {
                if (ticks < 8 || !presented()) { return false; }
                shot("rocket-editor-restart-02-consumer-module");
                cooldownRevision = received.revision(); cooldownRegression = true;
                injectCooldownAndDeploy(); next();
            }
            case 5 -> {
                if (!ack(RocketEditorStatePayload.Status.DEPLOYED) || !presented()) { return false; }
                require(cooldownRejections >= 1, "Synthetic cooldown did not exercise a RATE_LIMITED response");
                require(cooldownSavePackets.get() == 0 && cooldownDeployPackets.get() >= 2 && cooldownDeployPackets.get() <= 3,
                        "Cooldown recovery must emit only the bounded DEPLOY attempts");
                require(received.revision() == cooldownRevision && editor.blueprint().equals(expected) && !editor.dirty(),
                        "Cooldown recovery changed the saved revision or draft");
                shot("rocket-editor-restart-03-cooldown-retried"); screen().onClose();
                server(server -> {
                    verifyPersisted(server);
                    require(host(server).revision() == cooldownRevision, "Cooldown recovery saved a new revision");
                });
                pending = pending.thenRunAsync(this::removeCooldownObserverNow, cooldownChannel.eventLoop()); next();
            }
            case 6 -> { writeEvidence(); AstraEngine.LOGGER.info("ASTRA_ROCKET_EDITOR_PASSED restart=true"); return true; }
            default -> throw new IllegalStateException("Unexpected rocket restart step " + step);
        }
        return false;
    }

    private void injectCooldownAndDeploy() {
        MinecraftServer server = minecraft.getSingleplayerServer();
        cooldownChannel = minecraft.getConnection().getConnection().channel();
        pending = CompletableFuture.runAsync(() -> cooldownChannel.pipeline().addLast(COOLDOWN_OBSERVER,
                new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof ServerboundCustomPayloadPacket packet
                                && packet.payload() instanceof RocketEditorCommandPayload command) {
                            if (command.action() == RocketEditorCommandPayload.Action.SAVE) { cooldownSavePackets.incrementAndGet(); }
                            if (command.action() == RocketEditorCommandPayload.Action.DEPLOY) { cooldownDeployPackets.incrementAndGet(); }
                            cooldownPackets.add(command.action() + "\t" + command.revision());
                            AstraEngine.LOGGER.info("ASTRA_ROCKET_EDITOR_OUTBOUND action={} revision={}", command.action(), command.revision());
                        }
                        super.write(context, message, promise);
                    }
                }), cooldownChannel.eventLoop()).thenRunAsync(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    var current = player.getData(RocketWorkshop.EDIT_SESSION).orElseThrow();
                    // Explicit fault injection: extend only this session's cooldown; never advance the world's clock.
                    long injected = player.serverLevel().getGameTime() + 20;
                    player.setData(RocketWorkshop.EDIT_SESSION, Optional.of(new RocketEditorSessions.Session(
                            current.token(), current.dimension(), current.position(), current.host(), injected)));
                    AstraEngine.LOGGER.info("ASTRA_ROCKET_EDITOR_COOLDOWN_INJECTED offset_ticks=20 revision={}", cooldownRevision);
                }, server).thenRunAsync(() -> clickKey("deploy"), minecraft);
    }

    private void removeCooldownObserver() {
        if (cooldownChannel != null) { cooldownChannel.eventLoop().execute(this::removeCooldownObserverNow); }
    }

    private void removeCooldownObserverNow() {
        if (cooldownChannel != null && cooldownChannel.pipeline().get(COOLDOWN_OBSERVER) != null) {
            cooldownChannel.pipeline().remove(COOLDOWN_OBSERVER);
        }
    }

    private void verifyConsumer(RocketPart part) {
        var values = part.moduleValues().get(MODULE);
        require(values != null && values.get("range_ly").number() == 123.5 && values.get("stabilized").flag()
                        && values.get("transit_mode").choice().equals("wormhole"), "Generic typed module values differ from the submitted form");
    }

    private void prepareWorld(MinecraftServer server) {
        var level = server.overworld();
        for (int x = -10; x <= 24; x++) {
            for (int z = -10; z <= 10; z++) { level.setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.SMOOTH_STONE.defaultBlockState()); }
        }
        level.setBlockAndUpdate(HOST, RocketWorkshop.EDITOR.get().defaultBlockState());
        level.setDayTime(6000);
        positionAtHost(server.getPlayerList().getPlayers().getFirst());
    }

    private void positionAtHost(ServerPlayer player) {
        player.getAbilities().flying = false; player.onUpdateAbilities();
        player.connection.teleport(-2.5, 200, 2.5, -135, 15);
    }

    private void openHost() {
        require(minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(HOST), Direction.UP, HOST, false)).consumesAction(),
                "Native workshop block interaction was not accepted");
    }

    private RocketEditorBlockEntity host(MinecraftServer server) {
        require(server.overworld().getBlockEntity(HOST) instanceof RocketEditorBlockEntity, "Persistent workshop block is missing");
        return (RocketEditorBlockEntity) server.overworld().getBlockEntity(HOST);
    }

    private RocketAssemblyEntity assembly(MinecraftServer server) {
        require(server.overworld().getEntity(assemblyId) instanceof RocketAssemblyEntity, "Persistent assembly UUID is missing");
        return (RocketAssemblyEntity) server.overworld().getEntity(assemblyId);
    }

    private RocketAssemblyEntity clientAssembly() {
        return minecraft.level.getEntitiesOfClass(RocketAssemblyEntity.class, new AABB(HOST).inflate(96)).stream()
                .filter(entity -> entity.getUUID().equals(assemblyId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Synchronized rocket assembly is missing"));
    }

    private void verifyPersisted(MinecraftServer server) {
        require(host(server).blueprint().equals(expected) && host(server).deployedAssembly().orElseThrow().equals(assemblyId),
                "Saved host blueprint or owned entity identity changed");
        var assembly = assembly(server);
        require(assembly.blueprint().equals(expected) && assembly.hostPosition().equals(HOST), "Persistent deployed blueprint or owner changed");
        require(server.overworld().getEntitiesOfClass(RocketAssemblyEntity.class, new AABB(HOST).inflate(64)).size() == 1,
                "Workshop produced a duplicate deployed assembly");
        require(Arrays.stream(assembly.getParts()).filter(part -> part.active()).count() == assembly.activeHitboxCount(),
                "Multipart collision lifecycle differs from its active bounds");
        verifyConsumer(assembly.blueprint().requirePart(customId));
    }

    private void viewAssembly(MinecraftServer server, RocketAssemblyEntity assembly) {
        AABB bounds = assembly.getBoundingBox(); Vec3 center = bounds.getCenter();
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        double distance = Math.max(10, bounds.getYsize() * 1.5);
        Vec3 eye = center.add(-distance * 0.65, 2.0, distance);
        Vec3 direction = center.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        player.getAbilities().flying = true; player.onUpdateAbilities();
        player.connection.teleport(eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
    }

    private void prepareCollision(MinecraftServer server, RocketAssemblyEntity assembly) {
        collisionBox = RocketAssemblyEntity.collisionBoxes(assembly.blueprint(), assembly.position()).stream()
                .min(Comparator.comparingDouble(box -> box.minX)).orElseThrow();
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        player.getAbilities().flying = true; player.onUpdateAbilities();
        player.connection.teleport(collisionBox.minX - 1.25, collisionBox.minY + 0.02,
                (collisionBox.minZ + collisionBox.maxZ) * 0.5, -90, 0);
    }

    private void selectAssemblyPart(RocketPart part) {
        if (hasLabel(key("catalog"))) { clickKey("catalog"); }
        String label = part.id() + " " + editor.catalog().requireDefinition(part.definitionId()).displayName();
        for (int page = 0; page < 16 && !hasLabel(label); page++) { clickLabel(">"); }
        clickLabel(label); require(editor.selected().id() == part.id(), "Assembly list selected the wrong stable part identity");
    }

    private void showLibrary() { if (hasLabel(key("assembly"))) { clickKey("assembly"); } }
    private void selectDefinition(String id) {
        // The screen applies a typed search at its ordinary UI update boundary.
        screen().tick();
        clickLabel(editor.catalog().requireDefinition(id).displayName());
    }

    private void chooseAttachment(String name) {
        String wanted = key("attach." + name.toLowerCase(java.util.Locale.ROOT));
        for (int attempt = 0; attempt < RocketPlacement.Attachment.values().length && !hasLabel(wanted); attempt++) {
            Button current = buttons().stream().filter(button -> Arrays.stream(RocketPlacement.Attachment.values())
                    .anyMatch(value -> button.getMessage().getString().equals(key("attach." + value.name().toLowerCase(java.util.Locale.ROOT)))))
                    .findFirst().orElseThrow();
            click(current);
        }
        require(hasLabel(wanted), "Attachment selector cannot reach " + name);
    }

    private boolean ack(RocketEditorStatePayload.Status status) {
        return received != null && received.status() == status && !editor.busy()
                && renderedFrames >= acknowledgementFrame + 2;
    }

    private boolean presented() {
        // Arm only after this phase's async/window/ack condition is true. Several logic ticks can
        // run before one frame, so a tick delay cannot establish that the screen was presented.
        if (presentationStep != step) {
            presentationStep = step;
            captureAfterFrame = renderedFrames + 2;
        }
        return renderedFrames >= captureAfterFrame;
    }

    private boolean windowSize(int width, int height) {
        return minecraft.getWindow().getWidth() == width && minecraft.getWindow().getHeight() == height;
    }

    private void assertWidgetBounds() {
        for (var child : screen().children()) {
            if (child instanceof AbstractWidget widget && widget.visible) {
                require(widget.getX() >= 0 && widget.getY() >= 0
                                && widget.getX() + widget.getWidth() <= screen().width
                                && widget.getY() + widget.getHeight() <= screen().height,
                        "Resized widget escaped the screen: " + widget.getMessage().getString());
            }
        }
    }
    private RocketEditorScreen screen() { return (RocketEditorScreen) minecraft.screen; }
    private List<Button> buttons() { return minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList(); }
    private boolean hasLabel(String label) { return buttons().stream().anyMatch(button -> matches(button, label)); }
    private boolean matches(Button button, String label) { return button.getMessage().getString().equals(label) || button.getMessage().getString().equals("> " + label); }
    private String key(String suffix, Object... args) { return Component.translatable("astraengine.rocket_editor." + suffix, args).getString(); }
    private void clickKey(String suffix, Object... args) { clickLabel(key(suffix, args)); }
    private void clickLabel(String label) {
        click(buttons().stream().filter(button -> matches(button, label)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing rocket widget " + label + " at step " + step)));
    }

    private void click(AbstractWidget widget) {
        require(widget.active && widget.visible, "Rocket widget is unavailable: " + widget.getMessage().getString());
        Screen screen = minecraft.screen;
        double x = widget.getX() + widget.getWidth() * 0.5, y = widget.getY() + widget.getHeight() * 0.5;
        require(screen.mouseClicked(x, y, 0), "Rocket widget did not handle a real click"); screen.mouseReleased(x, y, 0);
    }

    private void input(String label, String value) {
        EditBox box = field(label); click(box);
        int length = box.getValue().length(); minecraft.screen.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0);
        for (int index = 0; index < length; index++) { minecraft.screen.keyPressed(GLFW.GLFW_KEY_DELETE, 0, 0); }
        for (int index = 0; index < value.length(); index++) { minecraft.screen.charTyped(value.charAt(index), 0); }
        require(field(label).getValue().equals(value), "Typed input did not reach " + label);
    }

    private EditBox field(String label) {
        return minecraft.screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .filter(box -> box.getMessage().getString().equals(label)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing rocket field " + label));
    }

    private double[] viewportCenter(RocketEditorScreen screen) {
        return new double[] {146 + (screen.width - 192 - 146 - 8) * 0.5, 68 + (screen.height - 68 - 112) * 0.5};
    }

    private int[] viewportPixels() {
        var screen = screen(); double scale = minecraft.getWindow().getGuiScale();
        int x = (int) Math.round(146 * scale), y = (int) Math.round(68 * scale);
        int width = (int) Math.round((screen.width - 192 - 146 - 8) * scale);
        int height = (int) Math.round((screen.height - 68 - 112) * scale);
        int[] pixels = new int[width * height];
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) { pixels[row * width + column] = image.getPixelRGBA(x + column, y + row); }
            }
        }
        return pixels;
    }

    private int pixelDifference(int[] first, int[] second) {
        require(first.length == second.length, "Viewport extent changed unexpectedly");
        int changed = 0; for (int index = 0; index < first.length; index++) { if (first[index] != second[index]) { changed++; } }
        observations.append(step).append("\tviewport_changed_pixels\t").append(changed).append("\t0\n");
        return changed;
    }

    private void shot(String name) throws Exception {
        require(renderedFrames >= minimumPresentedFrame && presentationStep == step
                && renderedFrames >= captureAfterFrame, "Capture has no completed presentation frame");
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png"); Files.createDirectories(path.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { image.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Native GL error after " + name);
        observations.append(step).append("\tpresented_frame\t").append(renderedFrames).append("\t0\n");
        AstraEngine.LOGGER.info("ASTRA_ROCKET_EDITOR_SCREENSHOT {} frame={}", name, renderedFrames);
    }

    private void writeEvidence() throws Exception {
        Path folder = minecraft.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(folder);
        Files.writeString(folder.resolve(restart ? "rocket-editor-restart.tsv" : "rocket-editor.tsv"), observations.toString());
        if (restart) {
            Files.writeString(folder.resolve("rocket-editor-restart-outbound.tsv"), "action\trevision\n"
                    + String.join("\n", cooldownPackets) + "\n");
        }
        Files.writeString(folder.resolve(restart ? "rocket-editor-restart-scope.txt" : "rocket-editor-scope.txt"),
                "Real editor widgets and schema text input, ordinary own-player save/deploy packets, shader preview/world screenshots, reload and multipart collision.\n"
                + "Captures wait for completed RenderFrameEvent.Post frames; dirty deployment requires SAVED before DEPLOYED at the same revision.\n"
                + "Resize covers compact UI, its actual Smaller UI button, restored window size, widget bounds and exact preview restoration.\n"
                + (restart ? "Synthetic restart-only fault: owning server thread sets the current session cooldown to gameTime +20 ticks; world time is unchanged.\n"
                        + "A temporary passive outbound observer verifies zero SAVE packets, 2..3 DEPLOY attempts, RATE_LIMITED then DEPLOYED, unchanged revision and entity UUID.\n" : "")
                + "Disposable platform and initial player poses are fixture setup. No production draft, renderer or deployment override.\n"
                + "Restart=" + restart + ", graphics=" + minecraft.options.graphicsMode().get() + ".\n");
    }

    private Path checkpointPath() { return minecraft.gameDirectory.toPath().resolve("rocket-editor-checkpoint.properties"); }
    private void hold(int key, boolean down) { KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(key), down); }
    private void server(Consumer<MinecraftServer> action) { MinecraftServer server = minecraft.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server); }
    private void next() { go(step + 1); }
    private void go(int target) {
        AstraEngine.LOGGER.info("ASTRA_ROCKET_EDITOR_STEP {} complete restart={}", step, restart);
        step = target; ticks = 0; minimumPresentedFrame = renderedFrames + 2;
    }
    private void require(boolean condition, String message) { if (!condition) { throw new IllegalStateException(message + " (rocket editor step " + step + ", ticks " + ticks + ")"); } }
}
