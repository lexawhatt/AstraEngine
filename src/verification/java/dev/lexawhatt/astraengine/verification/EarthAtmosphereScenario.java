package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.client.render.OverworldSkyRenderer;
import dev.lexawhatt.astraengine.client.solar.AstralOverworldEffects;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

/** Real Earth chart changes exercise geographic sky orientation and the uploaded physical cloud altitude. */
final class EarthAtmosphereScenario {
    private static final List<GeographicPosition> OBSERVERS = List.of(
            new GeographicPosition(0, 0, 800), new GeographicPosition(0, 0, 2400),
            new GeographicPosition(0, 0, 4300), new GeographicPosition(Math.PI / 2, 0, 4300),
            new GeographicPosition(-Math.PI / 2, 0, 4300), new GeographicPosition(0, -Math.PI, 4300),
            new GeographicPosition(0, Math.PI / 2, 4300), new GeographicPosition(0, -Math.PI / 2, 4300),
            new GeographicPosition(0, 0, 4300));
    private final Minecraft game = Minecraft.getInstance();
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private int index;
    private int step;
    private int frames;
    private GeographicPosition observer;
    private final Consumer<RenderFrameEvent.Post> frame = event -> {
        if (game.level != null && game.screen == null && game.getOverlay() == null) { frames++; }
        else { frames = 0; }
    };

    EarthAtmosphereScenario() {
        game.options.cloudStatus().set(net.minecraft.client.CloudStatus.FANCY);
        game.player.connection.sendCommand("astra-render cloud-cover 0.65");
        NeoForge.EVENT_BUS.addListener(frame);
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        if (step == 0) {
            var selected = OBSERVERS.get(index);
            double ground = new ContinentalTerrain(ContinentalTerrain.CURRENT_VERSION, ContinentalTerrain.SEED)
                    .sample(selected.normal()).heightMeters();
            observer = new GeographicPosition(selected.latitudeRadians(), selected.longitudeRadians(),
                    Math.max(selected.altitudeMeters(), ground + 40));
            server(server -> {
                EarthWorlds.validate(server);
                var chart = EarthChart.owner(observer, ContinentalTerrain.CURRENT_VERSION).orElseThrow();
                var level = server.getLevel(EarthWorlds.dimension(chart));
                var feet = chart.resolve(observer).orElseThrow();
                var player = server.getPlayerList().getPlayers().getFirst();
                server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                server.overworld().getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                server.overworld().setDayTime(6000);
                server.overworld().setWeatherParameters(100000, 0, false, false);
                player.teleportTo(level, feet.x(), feet.y(), feet.z(), 36, index == OBSERVERS.size() - 1 ? 90 : -8);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            });
            frames = 0;
            step = 1;
            return false;
        }
        var chart = EarthChart.owner(observer, ContinentalTerrain.CURRENT_VERSION).orElseThrow();
        if (frames < 35 || !game.level.dimension().equals(EarthWorlds.dimension(chart))) { return false; }
        require(game.level.effects() instanceof AstralOverworldEffects, "Earth chart lost its shared atmosphere effects");
        var effects = (AstralOverworldEffects) game.level.effects();
        var sky = effects.skyState();
        require(effects.ownsClouds(game.level), "Earth chart delegated to vanilla block clouds");
        require(Math.abs(sky.localProfile(game.level).latitudeDegrees() - Math.toDegrees(observer.latitudeRadians())) < 1e-6,
                "Atmosphere latitude is not the actual geographic observer");
        require(Math.abs(sky.altitudeMeters(game.level, game.player.getY()) - observer.altitudeMeters()) < 1e-6,
                "Altitude band changed the atmosphere's physical observer height");
        var renderer = (OverworldSkyRenderer) field(effects, "renderer");
        Object landscape = field(renderer, "landscape");
        if (field(landscape, "vertices") == null || field(landscape, "frameDepth") == null) { return false; }
        require(!(boolean) field(landscape, "failed"), "Distant surface request failed");
        if (index == OBSERVERS.size() - 1) {
            var target = (com.mojang.blaze3d.pipeline.RenderTarget) field(landscape, "target");
            int previous = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
            int pack = GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
                org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
                float[] depth = {1};
                GL11.glReadPixels(target.width / 2, target.height / 2, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
                require(Float.isFinite(depth[0]) && depth[0] < 1,
                        "Looking straight down from an upper storage band left a hole in distant terrain");
            } finally {
                org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, previous);
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, pack);
            }
        }
        Object volumes = field(renderer, "volumes");
        var shader = (ShaderInstance) field(volumes, "transport");
        require(shader.getUniform("DistantReady").getIntBuffer().get(0) == 1,
                "Cloud geometry pass did not receive current-frame distant depth");
        if (shader.getUniform("SkyAccess").getFloatBuffer().get(0) <= 0) { return false; }
        var layer = shader.getUniform("CloudLayer").getFloatBuffer();
        var origin = shader.getUniform("ObserverKm").getFloatBuffer();
        require(Math.abs(layer.get(0) - 1.8) < 1e-6 && Math.abs(layer.get(1) - 3.2) < 1e-6,
                "Cloud shader did not receive physical Earth layer bounds");
        require(Math.abs(origin.get(1) * 1000 - observer.altitudeMeters() - game.player.getEyeHeight()) < .01,
                "Cloud shader used local band Y rather than physical eye altitude");
        var output = game.gameDirectory.toPath().resolve("evidence");
        Files.createDirectories(output);
        String name = String.format(java.util.Locale.ROOT, "earth-atmosphere-%02d", index);
        String depthEvidence = "";
        if (index == OBSERVERS.size() - 1) {
            var distantTarget = (com.mojang.blaze3d.pipeline.RenderTarget) field(landscape, "target");
            var scene = (com.mojang.blaze3d.pipeline.RenderTarget) field(volumes, "scene");
            Object geometry = field(volumes, "geometry");
            float hostDepth = readPixel(scene.frameBufferId, scene.width / 2, scene.height / 2, true)[0];
            float farDepth = readPixel(distantTarget.frameBufferId, distantTarget.width / 2, distantTarget.height / 2, true)[0];
            var hostPoint = new org.joml.Matrix4f(shader.getUniform("InverseViewProjection").getFloatBuffer())
                    .transform(new org.joml.Vector4f(0, 0, hostDepth * 2 - 1, 1));
            var farPoint = new org.joml.Matrix4f(shader.getUniform("DistantInverseViewProjection").getFloatBuffer())
                    .transform(new org.joml.Vector4f(0, 0, farDepth * 2 - 1, 1));
            float[] transport = readPixel((int) field(geometry, "framebuffer"), (int) field(geometry, "width") / 2,
                    (int) field(geometry, "height") / 2, false);
            require(hostDepth == 1, "Fabulous fullscreen depth replaced the preserved empty upper-band terrain depth");
            require(transport[3] < .9, "The visible cloud layer did not attenuate the distant ground below it");
            depthEvidence = "\nhostDepth=" + hostDepth + "\nfarDepth=" + farDepth
                    + "\nhostPoint=" + hostPoint.div(hostPoint.w) + "\nfarPoint=" + farPoint.div(farPoint.w)
                    + "\ntransport=" + java.util.Arrays.toString(transport);
        }
        Files.writeString(output.resolve(name + ".txt"), "chart=" + chart + "\nobserver=" + observer
                + "\nsky=" + sky.sample(game.level, 0) + "\nlandscape_draws=" + field(landscape, "draws")
                + "\nskyAccess=" + shader.getUniform("SkyAccess").getFloatBuffer().get(0)
                + "\ncloudCover=" + shader.getUniform("CloudParams").getFloatBuffer().get(0) + depthEvidence + "\n", StandardOpenOption.CREATE_NEW);
        try (NativeImage capture = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            capture.writeToFile(output.resolve(name + ".png"));
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Earth atmosphere left an OpenGL error");
        index++;
        if (index == OBSERVERS.size()) { NeoForge.EVENT_BUS.unregister(frame); return true; }
        step = 0;
        return false;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static float[] readPixel(int framebuffer, int x, int y, boolean depth) {
        int previous = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        int pack = GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, framebuffer);
            float[] result = new float[depth ? 1 : 4];
            GL11.glReadPixels(x, y, 1, 1, depth ? GL11.GL_DEPTH_COMPONENT : GL11.GL_RGBA, GL11.GL_FLOAT, result);
            return result;
        } finally {
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, previous);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, pack);
        }
    }
    private void server(Consumer<MinecraftServer> operation) {
        var server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> operation.accept(server), server);
    }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
