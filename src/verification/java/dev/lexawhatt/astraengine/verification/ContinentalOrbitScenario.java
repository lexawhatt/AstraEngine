package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.system.MemoryUtil;

/** Controlled production orbital renderer observations; deliberately not a gameplay landing acceptance test. */
final class ContinentalOrbitScenario {
    private final Minecraft game = Minecraft.getInstance();
    private final ContinentalTerrain terrain = new ContinentalTerrain(ContinentalTerrain.CURRENT_VERSION, ContinentalTerrain.SEED);
    private final Consumer<ViewportEvent.ComputeCameraAngles> camera = event -> {
        if (this.orientation != null) {
            event.setYaw(this.orientation.yaw()); event.setPitch(this.orientation.pitch()); event.setRoll(this.orientation.roll());
        }
    };
    private final Consumer<RenderLevelStageEvent> render = this::render;
    private CosmosRenderer renderer;
    private SpaceVector observer;
    private SpaceVector mountain;
    private FlightOrientation orientation;
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RuntimeException failure;
    private int step;
    private int view;
    private int frames;
    private final StringBuilder evidence = new StringBuilder("Controlled production renderer poses; no pilot or landing overrides.\n");

    ContinentalOrbitScenario() {
        game.options.hideGui = true;
        game.options.fov().set(70);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, camera);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, render);
    }

    boolean tick() throws Exception {
        if (failure != null) { throw failure; }
        if (!pending.isDone()) { return false; }
        pending.join();
        if (step == 0) {
            KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_M));
            step = 1;
            return false;
        }
        if (step == 1) {
            if (!(game.screen instanceof CosmosMapScreen map)) { return false; }
            renderer = (CosmosRenderer) field(map.controller(), "renderer");
            map.onClose();
            renderer.setContinentalEarth(terrain.version());
            var server = game.getSingleplayerServer();
            pending = CompletableFuture.runAsync(() -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(server.getLevel(RocketService.FLIGHT), 0, 200, 0, 0, 0);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            }, server);
            var frame = SurfaceDefinition.byBody("earth").frame(CosmosGenerator.sol(), 0, 0);
            var sun = frame.toBodyDirection(frame.centerMeters().multiply(-1)).normalized();
            double maximum = 0;
            Random random = new Random(41);
            for (int i = 0; i < 20000; i++) {
                SpaceVector direction = new SpaceVector(random.nextDouble() * 2 - 1,
                        random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1).normalized();
                double height = terrain.sample(direction).heightMeters();
                if (direction.dot(sun) > .6 && height > maximum) { maximum = height; mountain = direction; }
            }
            require(mountain != null && maximum > 5000, "No daylight mountain fixture found");
            evidence.append("mountain=").append(mountain).append(" height=").append(maximum).append('\n');
            setPose();
            step = 2;
            return false;
        }
        if (frames < 30 || !ready()) { return false; }
        inspect();
        var path = game.gameDirectory.toPath().resolve("evidence/continental-orbit-" + view + ".png");
        Files.createDirectories(path.getParent());
        try (NativeImage capture = Screenshot.takeScreenshot(game.getMainRenderTarget())) { capture.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Continental orbital frame left a GL error");
        if (view == 3) {
            pending = game.reloadResourcePacks();
        }
        if (++view == 6) {
            Files.writeString(path.getParent().resolve("continental-orbit.txt"), evidence, StandardOpenOption.CREATE_NEW);
            NeoForge.EVENT_BUS.unregister(camera);
            NeoForge.EVENT_BUS.unregister(render);
            return true;
        }
        setPose();
        return false;
    }

    private void setPose() {
        double altitude = switch (view) {
            case 0, 5 -> ContinentalTerrain.RADIUS_METERS * 2;
            case 1 -> 180000;
            case 2 -> 20000;
            default -> terrain.sample(mountain).heightMeters() + 100;
        };
        var frame = SurfaceDefinition.byBody("earth").frame(CosmosGenerator.sol(), 0, 0);
        observer = frame.toSystemPoint(mountain.multiply(ContinentalTerrain.RADIUS_METERS + altitude));
        SpaceVector direction = frame.centerMeters().subtract(observer).normalized();
        if (view == 3 || view == 4) {
            var grid = dev.lexawhatt.astraengine.surface.SurfaceHeightTile.Grid.at(mountain,
                    ContinentalTerrain.RADIUS_METERS, 513, 4);
            direction = frame.toSystemDirection(grid.east().add(mountain.multiply(-.08))).normalized();
        }
        var grid = dev.lexawhatt.astraengine.surface.SurfaceHeightTile.Grid.at(mountain,
                ContinentalTerrain.RADIUS_METERS, 513, 4);
        var tangent = new dev.lexawhatt.astraengine.surface.PlanetaryFrame(mountain, grid.east(), mountain, grid.south());
        var localDirection = tangent.toLocalDirection(frame.toBodyDirection(direction));
        var localOrientation = FlightOrientation.fromAngles(Math.toDegrees(Math.atan2(-localDirection.x(), localDirection.z())),
                -Math.toDegrees(Math.asin(localDirection.y())), 0);
        orientation = frame.toSystemOrientation(tangent.toBodyOrientation(localOrientation));
        frames = 0;
        evidence.append("view=").append(view).append(" altitude=").append(altitude).append('\n');
    }

    private boolean ready() throws ReflectiveOperationException {
        Object cache = field(renderer, "continental");
        return (int) field(cache, "globe") != 0 && (view == 0 || view == 5 || field(cache, "grid") != null);
    }

    private void inspect() throws ReflectiveOperationException {
        var shader = (ShaderInstance) field(renderer, "shader");
        require(shader.getUniform("ContinentalEarth").getIntBuffer().get(0) == 1,
                "Renderer lost server-selected continental geography");
        Object cache = field(renderer, "continental");
        int[] tiles = (int[]) field(cache, "tiles");
        if (view == 0 || view == 5) {
            require(field(cache, "grid") == null, "Far globe retained close terrain tiles");
            for (int texture : tiles) { require(texture == 0, "Far globe retained a tile allocation"); }
            return;
        }
        var grid = (dev.lexawhatt.astraengine.surface.SurfaceHeightTile.Grid) field(cache, "grid");
        require(grid.contains(mountain, .45), "Near cache uses the wrong geographic region");
        FloatBuffer values = MemoryUtil.memAllocFloat(513 * 513 * 4);
        try (FullscreenPass state = new FullscreenPass()) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(tiles[0]);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, values);
            float actual = values.get((256 * 513 + 256) * 4);
            double expected = terrain.sample(grid.up()).heightMeters();
            require(Math.abs(actual - expected) < .001, "GPU height texture diverged from chunk terrain");
            evidence.append("gpuHeight=").append(actual).append(" expected=").append(expected).append('\n');
        } finally { MemoryUtil.memFree(values); }
    }

    private void render(RenderLevelStageEvent event) {
        if (renderer == null || observer == null || failure != null || game.level == null
                || !game.level.dimension().equals(RocketService.FLIGHT)
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) { return; }
        try {
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int[] bindings = new int[8];
            for (int i = 0; i < bindings.length; i++) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                bindings[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            RenderSystem.activeTexture(active);
            renderer.render(event, CosmosGenerator.sol(), observer, 0, 0, 1);
            require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == active, "Orbital pass leaked its active texture unit");
            for (int i = 0; i < bindings.length; i++) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == bindings[i], "Orbital pass leaked sampler unit " + i);
            }
            RenderSystem.activeTexture(active);
            frames++;
        } catch (RuntimeException exception) { failure = exception; }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
