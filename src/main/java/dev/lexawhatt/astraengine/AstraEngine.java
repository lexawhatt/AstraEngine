package dev.lexawhatt.astraengine;

import dev.lexawhatt.astraengine.network.EarthWeatherReceivedEvent;
import dev.lexawhatt.astraengine.network.EarthWeatherPayload;
import com.mojang.logging.LogUtils;
import dev.lexawhatt.astraengine.network.CustomSystemsPayload;
import dev.lexawhatt.astraengine.network.CustomSystemsReceivedEvent;
import dev.lexawhatt.astraengine.network.ExplorationPayload;
import dev.lexawhatt.astraengine.network.ExplorationReceivedEvent;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.network.EarthLandingPayload;
import dev.lexawhatt.astraengine.network.FlightControlPayload;
import dev.lexawhatt.astraengine.network.FlightSpeedPayload;
import dev.lexawhatt.astraengine.network.SolarPayload;
import dev.lexawhatt.astraengine.network.SolarReceivedEvent;
import dev.lexawhatt.astraengine.network.SkyProfilePayload;
import dev.lexawhatt.astraengine.network.SkyProfileReceivedEvent;
import dev.lexawhatt.astraengine.network.SystemPayload;
import dev.lexawhatt.astraengine.network.SurfacePayload;
import dev.lexawhatt.astraengine.network.EarthContextPayload;
import dev.lexawhatt.astraengine.network.EarthContextReceivedEvent;
import dev.lexawhatt.astraengine.network.EarthBoundaryPayload;
import dev.lexawhatt.astraengine.network.EarthBoundaryReceivedEvent;
import dev.lexawhatt.astraengine.network.SurfaceReceivedEvent;
import dev.lexawhatt.astraengine.worldgen.SurfaceWorldgen;
import dev.lexawhatt.astraengine.network.SystemSnapshotReceivedEvent;
import dev.lexawhatt.astraengine.server.EngineRuntime;
import dev.lexawhatt.astraengine.server.NavigationRules;
import dev.lexawhatt.astraengine.network.NavigationPolicyPayload;
import dev.lexawhatt.astraengine.network.NavigationPolicyReceivedEvent;
import dev.lexawhatt.astraengine.compat.construction.ArchivedConstruction;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

/** Common mod entry point; client presentation is initialized by the separate physical-client entry point. */
@Mod(AstraEngine.MOD_ID)
public final class AstraEngine {
    public static final String MOD_ID = "astraengine";
    public static final Logger LOGGER = LogUtils.getLogger();
    private final EngineRuntime runtime;

    /** Wires logical-server lifecycle and snapshot transport on the appropriate buses. */
    public AstraEngine(IEventBus modEventBus) {
        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::registerPayloads);
        ArchivedConstruction.register(modEventBus);
        SurfaceWorldgen.register(modEventBus);
        runtime = new EngineRuntime();
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(dev.lexawhatt.astraengine.network.BoundaryInteractPayload.TYPE,
                dev.lexawhatt.astraengine.network.BoundaryInteractPayload.CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) { runtime.boundaryInteraction(player, payload); }
                });
        event.registrar("2").playToClient(dev.lexawhatt.astraengine.network.SpaceBoundaryPreviewPayload.TYPE,
                dev.lexawhatt.astraengine.network.SpaceBoundaryPreviewPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new dev.lexawhatt.astraengine.network.SpaceBoundaryPreviewReceivedEvent(payload)));
        event.registrar("1").playToClient(dev.lexawhatt.astraengine.network.SpaceBoundaryHandoffPayload.TYPE,
                dev.lexawhatt.astraengine.network.SpaceBoundaryHandoffPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new dev.lexawhatt.astraengine.network.SpaceBoundaryHandoffReceivedEvent(payload)));
        event.registrar("1").playToServer(dev.lexawhatt.astraengine.network.SpaceBoundaryReadyPayload.TYPE,
                dev.lexawhatt.astraengine.network.SpaceBoundaryReadyPayload.CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) { runtime.spaceBoundaryReady(player, payload.revision()); }
                });
        event.registrar("1").playToClient(dev.lexawhatt.astraengine.network.OrbitalSummaryPayload.TYPE,
                dev.lexawhatt.astraengine.network.OrbitalSummaryPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new dev.lexawhatt.astraengine.network.OrbitalSummaryReceivedEvent(payload)));
        event.registrar("1").playToClient(dev.lexawhatt.astraengine.network.BoundaryHandoffPayload.TYPE,
                dev.lexawhatt.astraengine.network.BoundaryHandoffPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new dev.lexawhatt.astraengine.network.BoundaryHandoffReceivedEvent(payload)));
        event.registrar("1").playToServer(dev.lexawhatt.astraengine.network.BoundaryReadyPayload.TYPE,
                dev.lexawhatt.astraengine.network.BoundaryReadyPayload.CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) { runtime.boundaryReady(player, payload.revision()); }
                });
        event.registrar("1").playToClient(dev.lexawhatt.astraengine.network.PlanetContextPayload.TYPE,
                dev.lexawhatt.astraengine.network.PlanetContextPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new dev.lexawhatt.astraengine.network.PlanetContextReceivedEvent(payload)));
        event.registrar("1").playToClient(NavigationPolicyPayload.TYPE, NavigationPolicyPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new NavigationPolicyReceivedEvent(payload)));
        event.registrar("3").playToClient(EarthBoundaryPayload.TYPE, EarthBoundaryPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new EarthBoundaryReceivedEvent(payload)));
        event.registrar("3").playToClient(EarthContextPayload.TYPE, EarthContextPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new EarthContextReceivedEvent(payload)));
        event.registrar("1").playToClient(SystemPayload.TYPE, SystemPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new SystemSnapshotReceivedEvent(payload)));
        event.registrar("3").playToClient(CustomSystemsPayload.TYPE, CustomSystemsPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new CustomSystemsReceivedEvent(payload)));
        event.registrar("9").playToClient(ExplorationPayload.TYPE, ExplorationPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new ExplorationReceivedEvent(payload)));
        event.registrar("1").playToClient(SolarPayload.TYPE, SolarPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new SolarReceivedEvent(payload)));
        event.registrar("1").playToClient(SkyProfilePayload.TYPE, SkyProfilePayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new SkyProfileReceivedEvent(payload)));
        event.registrar("1").playToClient(EarthWeatherPayload.TYPE,
                EarthWeatherPayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new EarthWeatherReceivedEvent(payload)));
        // PayloadRegistrar defaults to MAIN: both request handlers run on the owning logical-server thread.
        event.registrar("2").playToClient(SurfacePayload.TYPE, SurfacePayload.CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new SurfaceReceivedEvent(payload)));
        event.registrar("7").playToServer(FlightActionPayload.TYPE, FlightActionPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) { runtime.flightAction(player, payload); }
        });
        event.registrar("1").playToServer(EarthLandingPayload.TYPE, EarthLandingPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) { runtime.earthLanding(player, payload); }
        });
        event.registrar("4").playToServer(FlightControlPayload.TYPE, FlightControlPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) { runtime.flightControl(player, payload); }
        });
        event.registrar("1").playToServer(FlightSpeedPayload.TYPE, FlightSpeedPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) { runtime.flightSpeed(player, payload); }
        });
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        dev.lexawhatt.astraengine.surface.ContinentalTerrain.prepareCanonical();
        event.enqueueWork(NavigationRules::register);
        LOGGER.info("AstraEngine initialized");
    }
}
