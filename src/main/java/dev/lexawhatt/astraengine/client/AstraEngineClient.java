package dev.lexawhatt.astraengine.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.environment.EnvironmentProfiles;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.client.render.RenderOptions;
import dev.lexawhatt.astraengine.client.render.OverworldSkyRenderer;
import dev.lexawhatt.astraengine.client.solar.AstralOverworldEffects;
import dev.lexawhatt.astraengine.client.solar.SolarStateClient;
import dev.lexawhatt.astraengine.client.solar.SolarAudioController;
import dev.lexawhatt.astraengine.client.surface.SurfaceEffects;
import dev.lexawhatt.astraengine.client.surface.SurfaceDebugOverlay;
import dev.lexawhatt.astraengine.client.surface.EarthStateClient;
import dev.lexawhatt.astraengine.client.surface.EarthBoundaryClient;
import dev.lexawhatt.astraengine.client.surface.EarthBoundaryRenderer;
import dev.lexawhatt.astraengine.client.surface.HorizonRenderer;
import dev.lexawhatt.astraengine.client.surface.HorizonEffects;
import dev.lexawhatt.astraengine.client.sky.SkyStateClient;
import dev.lexawhatt.astraengine.client.editor.SceneEditor;
import dev.lexawhatt.astraengine.client.ship.ShipRenderer;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.compat.DistantCloudCompatibility;
import dev.lexawhatt.astraengine.compat.construction.ArchivedConstruction;
import dev.lexawhatt.astraengine.surface.HorizonScene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Physical-client entry point; all Minecraft rendering dependencies stay behind this boundary. */
@Mod(value = AstraEngine.MOD_ID, dist = Dist.CLIENT)
public final class AstraEngineClient {
    private static final ShipRenderer SHIPS = new ShipRenderer();
    private static final HorizonRenderer HORIZON = new HorizonRenderer();
    private static DistantCloudCompatibility distantClouds;

    /** Render-thread optional cloud diagnostics, available after physical-client initialization. */
    public static DistantCloudCompatibility distantCloudCompatibility() {
        RenderSystem.assertOnRenderThread();
        if (distantClouds == null) { throw new IllegalStateException("Client cloud integration is not initialized"); }
        return distantClouds;
    }

    /** Render-thread calibration controls; host-owned instance survives reload while session values reset on logout. */
    public static HorizonRenderer horizonRenderer() {
        RenderSystem.assertOnRenderThread();
        return HORIZON;
    }

    /**
     * Engine-owned visual renderer for client consumers. Call on the render thread;
     * readiness follows resource loading. Consumers must not register or close it.
     * The handle remains stable across reload and disconnect; frame inputs do not.
     */
    public static ShipRenderer shipRenderer() {
        RenderSystem.assertOnRenderThread();
        return SHIPS;
    }

    /** Registers physical-client rendering, resource lifecycle, and presentation handlers. */
    public AstraEngineClient(IEventBus modEventBus) {
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::registerEffects);
        modEventBus.addListener(HORIZON::registerShaders);
        NeoForge.EVENT_BUS.addListener(HORIZON::render);
        NeoForge.EVENT_BUS.addListener(HORIZON::registerCommands);
        NeoForge.EVENT_BUS.addListener(HORIZON::logout);
        NeoForge.EVENT_BUS.addListener(HORIZON::fogColor);
        modEventBus.addListener((RegisterDimensionSpecialEffectsEvent event) -> event.register(
                ResourceLocation.parse(HorizonScene.DIMENSION_ID), new HorizonEffects(HORIZON)));
        EnvironmentProfiles profiles = new EnvironmentProfiles();
        RenderOptions options = new RenderOptions(profiles);
        SolarStateClient solar = new SolarStateClient();
        EarthStateClient earth = new EarthStateClient();
        NeoForge.EVENT_BUS.addListener(earth::receive);
        NeoForge.EVENT_BUS.addListener(earth::logout);
        EarthBoundaryClient boundaries = new EarthBoundaryClient(earth);
        NeoForge.EVENT_BUS.addListener(boundaries::receive);
        NeoForge.EVENT_BUS.addListener(boundaries::tick);
        NeoForge.EVENT_BUS.addListener(boundaries::logout);
        EarthBoundaryRenderer boundaryRenderer = new EarthBoundaryRenderer(earth, boundaries);
        modEventBus.addListener(boundaryRenderer::registerShaders);
        NeoForge.EVENT_BUS.addListener(boundaryRenderer::render);
        NeoForge.EVENT_BUS.addListener(boundaryRenderer::logout);
        SkyStateClient seasons = new SkyStateClient(earth);
        NeoForge.EVENT_BUS.addListener(seasons::receive);
        NeoForge.EVENT_BUS.addListener(seasons::tick);
        NeoForge.EVENT_BUS.addListener(seasons::logout);
        OverworldSkyRenderer sky = new OverworldSkyRenderer(solar, options, seasons, earth);
        modEventBus.addListener(sky::registerShaders);
        NeoForge.EVENT_BUS.addListener(sky::renderDistant);
        NeoForge.EVENT_BUS.addListener(sky::distantFog);
        NeoForge.EVENT_BUS.addListener(sky::captureTerrainDepth);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            if (RenderSystem.isOnRenderThread()) { sky.close(); }
            else { RenderSystem.recordRenderCall(sky::close); }
        });
        AstralOverworldEffects overworld = new AstralOverworldEffects(sky, solar, options, seasons);
        distantClouds = new DistantCloudCompatibility(
                () -> overworld.ownsClouds(Minecraft.getInstance().level) || HORIZON.active());
        NeoForge.EVENT_BUS.addListener(distantClouds::frame);
        NeoForge.EVENT_BUS.addListener(distantClouds::logout);
        modEventBus.addListener((RegisterDimensionSpecialEffectsEvent event) -> event.register(
                BuiltinDimensionTypes.OVERWORLD_EFFECTS, overworld));
        NeoForge.EVENT_BUS.addListener(overworld::fogColor);
        NeoForge.EVENT_BUS.addListener(solar::receive);
        NeoForge.EVENT_BUS.addListener(solar::hud);
        NeoForge.EVENT_BUS.addListener(solar::logout);
        NeoForge.EVENT_BUS.addListener(solar::registerCommands);
        modEventBus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(profiles));
        NeoForge.EVENT_BUS.addListener(options::registerCommands);
        NeoForge.EVENT_BUS.addListener(RenderCompatibility::registerCommands);
        RocketController rocket = new RocketController(options, solar, earth);
        SurfaceDebugOverlay surfaceDebug = new SurfaceDebugOverlay(earth);
        NeoForge.EVENT_BUS.addListener(surfaceDebug::debugText);
        NeoForge.EVENT_BUS.addListener(rocket::receiveSurface);
        SurfaceEffects lunarEffects = new SurfaceEffects(rocket.surfaceState(), solar, false);
        SurfaceEffects earthEffects = new SurfaceEffects(rocket.surfaceState(), solar, true);
        NeoForge.EVENT_BUS.addListener(earthEffects::fogColor);
        modEventBus.addListener((RegisterDimensionSpecialEffectsEvent event) -> {
            event.register(ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "surface_moon"),
                    lunarEffects);
            event.register(ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "surface_earth"),
                    earthEffects);
        });
        SolarAudioController audio = new SolarAudioController(solar, rocket);
        NeoForge.EVENT_BUS.addListener(audio::tick);
        NeoForge.EVENT_BUS.addListener(audio::logout);
        NeoForge.EVENT_BUS.addListener(audio::registerCommands);
        modEventBus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(audio));
        modEventBus.addListener(rocket::registerKeys);
        modEventBus.addListener(rocket::registerShaders);
        NeoForge.EVENT_BUS.addListener(rocket::receive);
        NeoForge.EVENT_BUS.addListener(rocket::receiveCustomSystems);
        NeoForge.EVENT_BUS.addListener(rocket::registerCommands);
        NeoForge.EVENT_BUS.addListener(rocket::tick);
        NeoForge.EVENT_BUS.addListener(rocket::movement);
        NeoForge.EVENT_BUS.addListener(rocket::camera);
        NeoForge.EVENT_BUS.addListener(rocket::mouseTurn);
        NeoForge.EVENT_BUS.addListener(rocket::scroll);
        NeoForge.EVENT_BUS.addListener(rocket::keyInput);
        NeoForge.EVENT_BUS.addListener(rocket::interaction);
        NeoForge.EVENT_BUS.addListener(rocket::hand);
        NeoForge.EVENT_BUS.addListener(rocket::highlight);
        NeoForge.EVENT_BUS.addListener(rocket::player);
        NeoForge.EVENT_BUS.addListener(rocket::living);
        NeoForge.EVENT_BUS.addListener(rocket::render);
        NeoForge.EVENT_BUS.addListener(rocket::hud);
        NeoForge.EVENT_BUS.addListener(rocket::logout);
        SceneEditor editor = new SceneEditor(options);
        modEventBus.addListener(editor::registerKeys);
        NeoForge.EVENT_BUS.addListener(editor::registerCommands);
        NeoForge.EVENT_BUS.addListener(editor::tick);
        NeoForge.EVENT_BUS.addListener(editor::logout);
        NeoForge.EVENT_BUS.addListener(editor::collectLights);
        NeoForge.EVENT_BUS.addListener(editor::hideHud);
        modEventBus.addListener(SHIPS::registerShaders);
        modEventBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(ArchivedConstruction.ASSEMBLY.get(), NoopRenderer::new));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            if (RenderSystem.isOnRenderThread()) { SHIPS.close(); }
            else { RenderSystem.recordRenderCall(SHIPS::close); }
        });
        SpaceRenderer renderer = new SpaceRenderer(profiles, options, editor, SHIPS, sky);
        modEventBus.addListener(renderer::registerShaders);
        NeoForge.EVENT_BUS.addListener(renderer::receive);
        NeoForge.EVENT_BUS.addListener(renderer::logout);
        NeoForge.EVENT_BUS.addListener(renderer::render);
        NeoForge.EVENT_BUS.addListener(renderer::renderHud);
    }

    private void registerEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "space"), new SpaceEffects());
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        AstraEngine.LOGGER.info("AstraEngine client initialized");
    }
}
