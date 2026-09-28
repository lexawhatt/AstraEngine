package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Native production rendering at controlled presentation poses, plus ordinary catalog navigation and opaque blocks. */
final class CelestialScenario {
    private static final String NEIGHBOR = "s_-1_-1_0";
    private static final String BLACK_HOLE = "s_-1_-2_0";
    private static final BlockPos HOME_MARKER = new BlockPos(0, 199, 0);
    private final Minecraft minecraft = Minecraft.getInstance();
    private final StringBuilder metadata = new StringBuilder("capture\tscene\tvirtual_camera_meters\tview_quaternion\tbloom\tquality\tbodies\tnote\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private CelestialRendererProbe renderer;
    private RuntimeException renderFailure;
    private List<Pose> poses = List.of();
    private Pose pose;
    private Pose referencePose;
    private SpaceVector authoritativePosition;
    private int[] referencePixels;
    private int referenceWidth;
    private int referenceHeight;
    private int step;
    private int ticks;
    private int poseIndex;
    private int poseFrames;
    private int controlledFrames;

    CelestialScenario() {
        minecraft.options.hideGui = true;
        minecraft.options.fov().set(70);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::camera);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::render);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
        AstraEngine.LOGGER.info("ASTRA_CELESTIAL_BEGIN graphics={} transparency={} controlledFramesUseExtraSkyDraw=true",
                minecraft.options.graphicsMode().get(), Minecraft.useShaderTransparency());
    }

    boolean tick() throws Exception {
        if (minecraft.screen instanceof ReceivingLevelScreen) { return false; }
        if (renderFailure != null) { throw renderFailure; }
        if (!pending.isDone()) { return false; }
        pending.join();
        ticks++;
        require(ticks < 1200, "Celestial fixture step timed out");
        switch (step) {
            case 0 -> {
                server(this::prepareHome);
                command("astra-render quality high");
                command("astra-render bloom true");
                command("astra-render exposure 1");
                next();
            }
            case 1 -> {
                if (ticks < 30) { return false; }
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 8) { return false; }
                controller = map.controller();
                renderer = new CelestialRendererProbe(controller);
                map.onClose();
                tap(GLFW.GLFW_KEY_R);
                next();
            }
            case 3 -> {
                if (!active() || ticks < 30) { return false; }
                tap(GLFW.GLFW_KEY_C);
                next();
            }
            case 4 -> {
                if (ticks < 10 || !controller.snapshot().discoveredSystems().contains(NEIGHBOR)) { return false; }
                controller.action(FlightActionPayload.Action.JUMP_SYSTEM, NEIGHBOR);
                next();
            }
            case 5 -> {
                if (!settled(90) || !controller.snapshot().systemId().equals(NEIGHBOR)) { return false; }
                tap(GLFW.GLFW_KEY_C);
                next();
            }
            case 6 -> {
                if (ticks < 10 || !controller.snapshot().discoveredSystems().contains(BLACK_HOLE)) { return false; }
                require(controller.system(BLACK_HOLE).kind() == CosmosSystem.Kind.BLACK_HOLE,
                        "The known procedural black-hole fixture changed");
                controller.action(FlightActionPayload.Action.JUMP_SYSTEM, BLACK_HOLE);
                next();
            }
            case 7 -> {
                if (!settled(90) || !controller.snapshot().systemId().equals(BLACK_HOLE)) { return false; }
                CelestialBody hole = controller.currentSystem().bodies().getFirst();
                require(Math.abs(controller.snapshot().position().distance(hole.positionAt(controller.timeSeconds()))
                                / hole.radiusMeters() - 24) < 0.001,
                        "Ordinary catalog approach did not use its physical 24-radius observation point");
                verifyLook(hole.positionAt(controller.timeSeconds()).subtract(controller.visualPosition()).normalized());
                authoritativePosition = controller.snapshot().position();
                shot("celestial-01-catalog-approach", false, false);
                poses = createPoses(controller.currentSystem());
                referencePose = poses.getFirst();
                select(referencePose);
                next();
            }
            case 8 -> {
                if (!drawn()) { return false; }
                shot(pose.name(), false, false);
                if (++poseIndex < poses.size()) { select(poses.get(poseIndex)); return false; }
                select(referencePose);
                next();
            }
            case 9 -> {
                if (!drawn()) { return false; }
                shot("celestial-29-before-reload", true, false);
                pending = minecraft.reloadResourcePacks();
                poseFrames = 0;
                next();
            }
            case 10 -> {
                if (!drawn()) { return false; }
                shot("celestial-30-after-reload", false, true);
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 960, 540);
                poseFrames = 0;
                next();
            }
            case 11 -> {
                if (!drawn() || minecraft.getWindow().getWidth() != 960 || minecraft.getWindow().getHeight() != 540) {
                    return false;
                }
                shot("celestial-31-resized", false, false);
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 1280, 720);
                poseFrames = 0;
                next();
            }
            case 12 -> {
                if (!drawn() || minecraft.getWindow().getWidth() != 1280 || minecraft.getWindow().getHeight() != 720) {
                    return false;
                }
                shot("celestial-32-size-restored", false, true);
                CelestialBody hole = controller.currentSystem().bodies().getFirst();
                CosmosSystem wallScene = scene("wall_hole", List.of(hole(hole.radiusMeters(), 0, 0, 0)));
                select(lookAt("celestial-33-real-wall", wallScene,
                        new SpaceVector(0, 0.15, -Math.sqrt(1 - 0.15 * 0.15)).multiply(24 * hole.radiusMeters()),
                        SpaceVector.ZERO, true, "Real glowstone wall; virtual body data remain presentation-only"));
                server(server -> wall(server, true));
                next();
            }
            case 13 -> {
                if (!drawn() || ticks < 20) { return false; }
                var camera = minecraft.gameRenderer.getMainCamera();
                var direction = camera.getLookVector();
                Vec3 from = camera.getPosition();
                HitResult hit = minecraft.level.clip(new ClipContext(from,
                        from.add(direction.x * 40, direction.y * 40, direction.z * 40),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
                require(hit.getType() == HitResult.Type.BLOCK, "Real wall does not intersect the actual rendered camera ray");
                shot("celestial-33-real-wall", false, false);
                server(server -> wall(server, false));
                poseFrames = 0;
                next();
            }
            case 14 -> {
                if (!drawn() || ticks < 20) { return false; }
                shot("celestial-34-wall-removed", false, false);
                pose = null;
                next();
            }
            case 15 -> {
                if (ticks < 20) { return false; }
                require(controller.snapshot().position().equals(authoritativePosition),
                        "Controlled presentation poses mutated authoritative flight position");
                require(controller.snapshot().systemId().equals(BLACK_HOLE),
                        "Controlled presentation poses changed the authoritative system");
                CelestialBody hole = controller.currentSystem().bodies().getFirst();
                verifyLook(hole.positionAt(controller.timeSeconds()).subtract(controller.visualPosition()).normalized());
                shot("celestial-35-returned-production-view", false, false);
                server(server -> require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK),
                        "Celestial rendering damaged the real home marker"));
                next();
            }
            case 16 -> {
                Path evidence = minecraft.gameDirectory.toPath().resolve("evidence");
                Files.writeString(evidence.resolve("celestial-poses.tsv"), metadata.toString());
                Files.writeString(evidence.resolve("celestial-scope.txt"),
                        "The first and final frames use ordinary catalog navigation and the production camera.\n"
                        + "Intermediate frames override only the final camera angles and draw inputs of the existing CosmosRenderer.\n"
                        + "The production renderer, CPU extraction, shaders, HDR targets and composition are reused.\n"
                        + "A verification-only reflection bridge avoids split Java packages and a shipped test API.\n"
                        + "Controlled frames add a second sky draw after the normal draw; these are not performance measurements.\n"
                        + "Virtual poses, descriptor overlaps, and center/inside views are diagnostic presentation inputs, not discovered worlds.\n"
                        + "No authoritative position, discoveries, canonical body size, collision rules or evolution are overridden.\n"
                        + "Render quality: high unless an explicit low/balanced comparison pose is named.\n"
                        + "Graphics: " + minecraft.options.graphicsMode().get() + ".\n"
                        + "Controlled production draws: " + controlledFrames + ".\n");
                AstraEngine.LOGGER.info("ASTRA_CELESTIAL_PASSED poses={} controlledFrames={}", poses.size(), controlledFrames);
                return true;
            }
            default -> throw new IllegalStateException("Unexpected celestial step " + step);
        }
        return false;
    }

    private List<Pose> createPoses(CosmosSystem catalog) {
        List<Pose> result = new ArrayList<>();
        CelestialBody primary = catalog.bodies().getFirst();
        double radius = primary.radiusMeters();
        double tilt = primary.axialTiltRadians();
        SpaceVector normal = new SpaceVector(0, Math.cos(tilt), Math.sin(tilt));
        SpaceVector tangent = new SpaceVector(0, -Math.sin(tilt), Math.cos(tilt));
        SpaceVector oblique = normal.multiply(0.15).subtract(tangent.multiply(Math.sqrt(1 - 0.15 * 0.15)));
        result.add(lookAt("celestial-02-oblique-bloom-on", catalog, oblique.multiply(24 * radius), SpaceVector.ZERO, true,
                "Disk inclination cosine +0.15, physical horizon radius, bloom on"));
        result.add(lookAt("celestial-03-oblique-bloom-off", catalog, oblique.multiply(24 * radius), SpaceVector.ZERO, false,
                "Exactly the same pose/time with bloom off"));
        result.add(lookAt("celestial-04-other-disk-side", catalog,
                normal.multiply(-0.15).subtract(tangent.multiply(Math.sqrt(1 - 0.15 * 0.15))).multiply(24 * radius),
                SpaceVector.ZERO, true, "Disk inclination cosine -0.15"));
        result.add(lookAt("celestial-05-face-on", catalog, normal.multiply(24 * radius), SpaceVector.ZERO, true, "Disk normal +1"));
        result.add(lookAt("celestial-06-face-on-opposite", catalog, normal.multiply(-24 * radius), SpaceVector.ZERO, true, "Disk normal -1"));
        result.add(lookAt("celestial-07-edge-on", catalog, tangent.multiply(-24 * radius), SpaceVector.ZERO, true, "Exact edge-on disk"));
        result.add(lookAt("celestial-08-edge-on-opposite", catalog, tangent.multiply(24 * radius), SpaceVector.ZERO, true, "Opposite edge-on axis"));
        result.add(lookAt("celestial-09-positive-x", catalog, new SpaceVector(24 * radius, 0, 0), SpaceVector.ZERO, true, "Positive X axis"));
        result.add(lookAt("celestial-10-negative-x", catalog, new SpaceVector(-24 * radius, 0, 0), SpaceVector.ZERO, true, "Negative X axis"));
        result.add(lookAt("celestial-11-far-subpixel", catalog, oblique.multiply(100_000 * radius), SpaceVector.ZERO, true,
                "Physical horizon far below one pixel; no radius inflation"));
        result.add(lookAt("celestial-12-near-three-radii", catalog, oblique.multiply(3 * radius), SpaceVector.ZERO, true,
                "Finite-distance shadow near the photon region"));
        result.add(lookAt("celestial-13-horizon-exterior", catalog, oblique.multiply(1.04 * radius), SpaceVector.ZERO, true,
                "Presentation-only pose just outside the horizon; navigation guard is unchanged"));
        result.add(lookAt("celestial-14-inside-horizon", catalog, oblique.multiply(0.5 * radius), SpaceVector.ZERO, true,
                "Unreachable navigation diagnostic; finite dark interior"));
        result.add(lookAt("celestial-15-exact-center", catalog, SpaceVector.ZERO, new SpaceVector(0, 0, 1), true,
                "Exact-center finite fallback; no divide-by-zero"));
        SpaceVector straightCamera = new SpaceVector(0, 0, -24 * radius);
        result.add(lookAt("celestial-16-foreground-planet", scene("foreground_planet", List.of(hole(radius, 0, 0, 0),
                        planet("foreground", 3 * radius, 8 * radius, -Math.PI / 2, 0), lamp(radius))),
                straightCamera, SpaceVector.ZERO, true, "Planet between the observer and the hole must remain unwarped"));
        result.add(lookAt("celestial-17-behind-planet", scene("background_planet", List.of(hole(radius, 0, 0, 0),
                        planet("background", 3 * radius, 12 * radius, Math.PI / 2, 0), lamp(radius))),
                straightCamera, SpaceVector.ZERO, true, "Planet behind the hole can contribute to the lensed image"));
        result.add(lookAt("celestial-18-nearer-surface-farther-center", scene("surface_order", List.of(
                        hole(radius, 12 * radius, -Math.PI / 2, 0), planet("large_occluder", 18 * radius, 0, 0, 0), lamp(radius))),
                new SpaceVector(0, 0, -36 * radius), new SpaceVector(0, 0, -12 * radius), true,
                "Adversarial overlap: planet center is farther, but its near surface lies in front of the hole"));
        CosmosSystem sol = CosmosGenerator.sol();
        CelestialBody sun = body(sol, "sun"), earth = body(sol, "earth"), saturn = body(sol, "saturn");
        result.add(observe("celestial-19-sol-sun", sol, sun, "Canonical Sol radius and shared healthy stellar material"));
        result.add(observe("celestial-20-earth", sol, earth, "Opaque planet/atmosphere regression"));
        result.add(observe("celestial-21-saturn-oblique", sol, saturn, "Ring shadow regression"));
        SpaceVector saturnNormal = new SpaceVector(0, Math.cos(saturn.axialTiltRadians()), Math.sin(saturn.axialTiltRadians()));
        result.add(lookAt("celestial-22-saturn-face-on", sol,
                saturn.positionAt(0).add(saturnNormal.multiply(8 * saturn.radiusMeters())), saturn.positionAt(0), true,
                "Ordinary planet rings, face-on"));
        result.add(lookAt("celestial-23-saturn-edge-on", sol,
                saturn.positionAt(0).add(new SpaceVector(8 * saturn.radiusMeters(), 0, 0)), saturn.positionAt(0), true,
                "Ordinary planet rings, exact edge-on"));
        Pose obliquePose = result.getFirst();
        result.add(new Pose("celestial-24-oblique-low", obliquePose.system(), obliquePose.camera(),
                obliquePose.orientation(), true, "low", "Identical oblique pose with Detail=3 and low bloom budget"));
        result.add(new Pose("celestial-25-oblique-balanced", obliquePose.system(), obliquePose.camera(),
                obliquePose.orientation(), true, "balanced", "Identical oblique pose with Detail=4 and balanced bloom budget"));
        return List.copyOf(result);
    }

    private Pose observe(String name, CosmosSystem system, CelestialBody body, String note) {
        var observation = FlightDynamics.observation(system, body, 0);
        return new Pose(name, system, observation.position(), observation.orientation(), true, "high", note);
    }

    private Pose lookAt(String name, CosmosSystem system, SpaceVector camera, SpaceVector target, boolean bloom, String note) {
        SpaceVector direction = target.subtract(camera).normalized();
        FlightOrientation orientation = FlightOrientation.fromAngles(Math.toDegrees(Math.atan2(-direction.x(), direction.z())),
                -Math.toDegrees(Math.asin(Math.clamp(direction.y(), -1, 1))), 0);
        return new Pose(name, system, camera, orientation, bloom, "high", note);
    }

    private CelestialBody body(CosmosSystem system, String id) {
        return system.bodies().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    private CosmosSystem scene(String id, List<CelestialBody> bodies) {
        return new CosmosSystem(id, "Diagnostic " + id, 20260928, CosmosSystem.Kind.BLACK_HOLE, SpaceVector.ZERO, bodies);
    }

    private CelestialBody hole(double radius, double orbit, double phase, double tilt) {
        return new CelestialBody("hole", "Diagnostic hole", CelestialBody.Kind.BLACK_HOLE, radius, orbit,
                orbit == 0 ? 0 : 1.0e9, phase, 0, 0, new SpaceVector(1, 0.7, 0.4), 0, 0, 0, tilt);
    }

    private CelestialBody planet(String id, double radius, double orbit, double phase, double tilt) {
        return new CelestialBody(id, "Diagnostic planet", CelestialBody.Kind.ROCKY, radius, orbit,
                orbit == 0 ? 0 : 1.0e9, phase, 0, 0, new SpaceVector(0.15, 0.38, 0.8), 0, 0, 0, tilt);
    }

    private CelestialBody lamp(double radius) {
        return new CelestialBody("lamp", "Diagnostic illumination", CelestialBody.Kind.STAR, radius,
                100 * radius, 1.0e9, -Math.PI / 2, 0, 0, new SpaceVector(1, 1, 1), 0, 0, 0, 0);
    }

    private void select(Pose nextPose) {
        pose = nextPose;
        poseFrames = 0;
        ticks = 0;
        command("astra-render quality " + pose.quality());
        command("astra-render bloom " + pose.bloom());
        AstraEngine.LOGGER.info("ASTRA_CELESTIAL_POSE {} cameraMeters={} orientation={}",
                pose.name(), pose.camera(), pose.orientation());
    }

    private void camera(ViewportEvent.ComputeCameraAngles event) {
        if (pose == null || !active()) { return; }
        event.setYaw(pose.orientation().yaw());
        event.setPitch(pose.orientation().pitch());
        event.setRoll(pose.orientation().roll());
    }

    private void render(RenderLevelStageEvent event) {
        if (pose == null || !active() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || renderFailure != null) { return; }
        try {
            int count = renderer.draw(event, pose.system(), pose.camera(), 0, controller.exposure());
            require(count == pose.system().bodies().size(), "Production extraction dropped fixture bodies");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Native OpenGL error while rendering " + pose.name());
            poseFrames++;
            controlledFrames++;
        } catch (RuntimeException failure) {
            renderFailure = failure;
        }
    }

    private void shot(String name, boolean remember, boolean compare) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        minecraft.gui.getChat().clearMessages(false);
        if (pose != null) { verifyLook(pose.orientation().forward()); }
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            image.writeToFile(path);
            if (remember) {
                referenceWidth = image.getWidth(); referenceHeight = image.getHeight();
                referencePixels = pixels(image);
            }
            if (compare) {
                require(image.getWidth() == referenceWidth && image.getHeight() == referenceHeight,
                        "Deterministic comparison framebuffer size changed");
                int[] actual = pixels(image);
                long difference = 0;
                for (int i = 0; i < actual.length; i++) {
                    for (int shift = 0; shift < 24; shift += 8) {
                        difference += Math.abs(((actual[i] >>> shift) & 255) - ((referencePixels[i] >>> shift) & 255));
                    }
                }
                double mean = difference / (actual.length * 3.0);
                require(mean < 2, "Reload/resize changed the deterministic celestial image: mean channel error=" + mean);
                AstraEngine.LOGGER.info("ASTRA_CELESTIAL_IMAGE_COMPARISON name={} meanChannelError={}", name, mean);
            }
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after capture " + name);
        String bodies = pose == null ? "catalog" : pose.system().bodies().stream()
                .map(value -> value.id() + ":" + value.kind() + ":radius=" + value.radiusMeters()).reduce((a, b) -> a + "," + b).orElse("");
        metadata.append(name).append('\t').append(pose == null ? controller.snapshot().systemId() : pose.system().id())
                .append('\t').append(pose == null ? controller.snapshot().position() : pose.camera())
                .append('\t').append(pose == null ? controller.orientation() : pose.orientation())
                .append('\t').append(pose == null || pose.bloom()).append('\t').append(pose == null ? "high" : pose.quality())
                .append('\t').append(bodies).append('\t')
                .append(pose == null ? "Unmodified native catalog flight path" : pose.note()).append('\n');
        AstraEngine.LOGGER.info("ASTRA_CELESTIAL_SCREENSHOT {}", name);
    }

    private int[] pixels(NativeImage image) {
        int[] result = new int[image.getWidth() * image.getHeight()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) { result[y * image.getWidth() + x] = image.getPixelRGBA(x, y); }
        }
        return result;
    }

    private void verifyLook(SpaceVector expected) {
        var actual = minecraft.gameRenderer.getMainCamera().getLookVector();
        require(expected.dot(new SpaceVector(actual.x, actual.y, actual.z)) > 0.99999,
                "Actual host camera does not match the intended celestial view");
    }

    private void wall(MinecraftServer server, boolean enabled) {
        var level = server.getPlayerList().getPlayers().getFirst().serverLevel();
        for (int x = 3; x <= 13; x++) {
            for (int y = 76; y <= 86; y++) {
                level.setBlockAndUpdate(new BlockPos(x, y, 18), enabled ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
    }

    private void prepareHome(MinecraftServer server) {
        server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        server.overworld().setDayTime(6000);
        server.overworld().setWeatherParameters(100000, 0, false, false);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                server.overworld().setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        server.overworld().setBlockAndUpdate(HOME_MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, 0.5);
    }

    private boolean active() { return controller != null && controller.active(); }
    private boolean settled(int minimum) { return active() && ticks >= minimum && controller.snapshot().jumpTicks() == 0; }
    private boolean drawn() { return ticks >= 4 && poseFrames >= 3; }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void command(String command) { minecraft.player.connection.sendCommand(command); }
    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void next() { AstraEngine.LOGGER.info("ASTRA_CELESTIAL_STEP {} complete", step); step++; ticks = 0; }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (celestial step " + step + ", ticks " + ticks + ")"); }
    }

    private record Pose(String name, CosmosSystem system, SpaceVector camera, FlightOrientation orientation,
            boolean bloom, String quality, String note) {}
}
