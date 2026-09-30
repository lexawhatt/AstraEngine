package dev.lexawhatt.astraengine.server;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.AstraSystems;
import dev.lexawhatt.astraengine.api.CosmosDiscoveryEvent;
import dev.lexawhatt.astraengine.api.ExtractionResult;
import dev.lexawhatt.astraengine.api.StellarStateEvent;
import dev.lexawhatt.astraengine.api.SystemSnapshot;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.network.FlightControlPayload;
import dev.lexawhatt.astraengine.network.FlightSpeedPayload;
import dev.lexawhatt.astraengine.network.SystemPayload;
import dev.lexawhatt.astraengine.systems.StellarSystem;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Lifecycle adapter. Holds transient travel state only while the owning server is running. */
public final class EngineRuntime {
    private MinecraftServer server;
    private TravelService travel;
    private RocketService rocket;
    private SolarEvolutionService solar;

    /** Registers common/server listeners once. */
    public EngineRuntime() {
        NeoForge.EVENT_BUS.addListener(this::onStarted);
        NeoForge.EVENT_BUS.addListener(this::onStopped);
        NeoForge.EVENT_BUS.addListener(this::onStopping);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::onCosmosDiscovery);
        NeoForge.EVENT_BUS.addListener(this::onCommands);
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> onInteract(event));
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickItem event) -> onInteract(event));
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) -> onInteract(event));
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteractSpecific event) -> onInteract(event));
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.LeftClickBlock event) -> onInteract(event));
        NeoForge.EVENT_BUS.addListener(this::onAttack);
        NeoForge.EVENT_BUS.addListener(this::onBreak);
        NeoForge.EVENT_BUS.addListener(this::onPlace);
    }

    private void onStarted(ServerStartedEvent event) {
        server = event.getServer();
        SystemCatalog.get(server);
        ExplorationCatalog.get(server);
        SurfaceBindings.get(server).validate(server);
        PlanetaryTerrainWorld.validate(server);
        SurfaceWorlds.maintainBorders(server);
        travel = new TravelService(server);
        rocket = new RocketService(server);
        solar = new SolarEvolutionService(server, rocket);
        SkyState.get(server);
        AstraEngine.LOGGER.info("AstraEngine systems ready: alpha and beta; empty systems pause");
    }

    private void onStopping(ServerStoppingEvent event) {
        if (rocket != null) { rocket.close(); }
        if (travel != null) { travel.close(); }
    }

    private void onStopped(ServerStoppedEvent event) { solar = null; rocket = null; travel = null; server = null; }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (travel != null) { travel.recover(player); }
            if (rocket != null) { rocket.recover(player); }
            if (solar != null) { solar.send(player); }
            SkyService.send(player);
        }
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (travel != null) { travel.disconnect(player); }
            if (rocket != null) { rocket.disconnect(player); }
        }
    }

    private void onCosmosDiscovery(CosmosDiscoveryEvent event) {
        if (rocket != null && event.player().getServer() == server && server.isSameThread()) {
            rocket.send(event.player());
        }
    }

    private void onTick(ServerTickEvent.Post event) {
        if (travel == null) { return; }
        travel.tick();
        if (rocket != null) { rocket.tick(); }
        if (solar != null) { solar.tick(); }
        SystemCatalog catalog = SystemCatalog.get(event.getServer());
        for (String id : SystemWorlds.SYSTEM_IDS) {
            ServerLevel level = event.getServer().getLevel(SystemWorlds.dimension(id));
            boolean occupied = level != null && level.players().stream().anyMatch(ServerPlayer::isAlive);
            if (occupied) {
                StellarSystem system = catalog.system(id);
                SystemSnapshot before = system.snapshot();
                boolean burst = system.tick(true);
                catalog.setDirty();
                if (burst) {
                    NeoForge.EVENT_BUS.post(new StellarStateEvent(before, system.snapshot(), StellarStateEvent.Kind.BURST));
                    for (ServerPlayer player : level.players()) {
                        player.sendSystemMessage(Component.translatable("astraengine.system.burst", id), true);
                    }
                }
            }
        }
        if (event.getServer().getTickCount() % 5 != 0) { return; }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            String id = SystemWorlds.systemId(player.serverLevel().dimension()).orElseGet(() -> travel.target(player));
            if (id != null) {
                PacketDistributor.sendToPlayer(player, new SystemPayload(catalog.system(id).snapshot(),
                        player.serverLevel().dimension().location(), travel.remainingTicks(player)));
            }
        }
    }

    private void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("astra").requires(source -> source.hasPermission(2))
                .then(Commands.literal("visit").then(Commands.argument("system", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            SystemWorlds.SYSTEM_IDS.forEach(builder::suggest);
                            return builder.buildFuture();
                        }).executes(context -> {
                            String id = StringArgumentType.getString(context, "system");
                            if (travel == null || !SystemWorlds.SYSTEM_IDS.contains(id)) {
                                context.getSource().sendFailure(Component.translatable("astraengine.system.unknown", id));
                                return 0;
                            }
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            if (rejectRocketTravel(player)) { return 0; }
                            return travel.begin(player, id) ? 1 : 0;
                        })))
                .then(Commands.literal("leave").executes(context -> {
                    if (travel == null) { return 0; }
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (rejectRocketTravel(player)) { return 0; }
                    travel.returnHome(player);
                    return 1;
                }))
                .then(SolarEvolutionService.commands())
                .then(SkyService.commands())
                .then(Commands.literal("status").executes(context -> {
                    for (String id : SystemWorlds.SYSTEM_IDS) {
                        SystemSnapshot snapshot = AstraSystems.snapshot(context.getSource().getServer(), id);
                        context.getSource().sendSuccess(() -> Component.translatable("astraengine.system.status", id,
                                snapshot.stage().name(), snapshot.remainingResource(), snapshot.descriptor().resourceCapacity(),
                                snapshot.activeTicks(), snapshot.ticksUntilBurst(), snapshot.burstCount()), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("extract").then(Commands.argument("amount", LongArgumentType.longArg(1))
                        .executes(context -> extract(context.getSource().getPlayerOrException(),
                                LongArgumentType.getLong(context, "amount"), UUID.randomUUID()))
                        .then(Commands.argument("operation", UuidArgument.uuid()).executes(context -> extract(
                                context.getSource().getPlayerOrException(), LongArgumentType.getLong(context, "amount"),
                                UuidArgument.getUuid(context, "operation")))))));
    }

    /** Routes a decoded main-thread request only to the running server and excludes the other travel owner. */
    public void flightAction(ServerPlayer player, FlightActionPayload payload) {
        if (rocket == null || server == null || player.getServer() != server || !server.isSameThread()) { return; }
        if ((payload.action() == FlightActionPayload.Action.TOGGLE
                || payload.action() == FlightActionPayload.Action.TAKE_OFF) && !rocket.active(player)
                && travel != null && travel.busy(player)) {
            player.sendSystemMessage(Component.translatable("astraengine.rocket.unavailable"), true);
            return;
        }
        rocket.action(player, payload);
    }

    /** Routes decoded controls to the owning server; physical flight cannot be controlled for another player. */
    public void flightControl(ServerPlayer player, FlightControlPayload payload) {
        if (rocket != null && server != null && player.getServer() == server && server.isSameThread()) {
            rocket.control(player, payload);
        }
    }

    /** Applies a bounded inspection-speed request only on its player's owning server thread. */
    public void flightSpeed(ServerPlayer player, FlightSpeedPayload payload) {
        if (rocket != null && server != null && player.getServer() == server && server.isSameThread()) {
            rocket.speed(player, payload);
        }
    }

    private boolean rejectRocketTravel(ServerPlayer player) {
        if ((rocket != null && rocket.active(player)) || RocketService.isFlightWorld(player)) {
            player.sendSystemMessage(Component.translatable("astraengine.rocket.unavailable"), true);
            return true;
        }
        return false;
    }

    private void onInteract(PlayerInteractEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && RocketService.isFlightWorld(player)
                && event instanceof ICancellableEvent cancellable) { cancellable.setCanceled(true); }
    }

    private void onAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && RocketService.isFlightWorld(player)) {
            event.setCanceled(true);
        }
    }

    private void onBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && RocketService.isFlightWorld(player)) {
            event.setCanceled(true);
        }
    }

    private void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension().equals(RocketService.FLIGHT)) {
            event.setCanceled(true);
        }
    }

    private int extract(ServerPlayer player, long amount, UUID operation) {
        String id = SystemWorlds.systemId(player.serverLevel().dimension()).orElse(null);
        if (id == null) {
            player.sendSystemMessage(Component.translatable("astraengine.system.visit_first"));
            return 0;
        }
        ExtractionResult result = AstraSystems.extract(player.getServer(), id, "primary", operation, amount);
        player.sendSystemMessage(Component.translatable("astraengine.system.extraction", result.status().name(),
                result.extracted(), result.operationId().toString()));
        return result.status() == ExtractionResult.Status.APPLIED ? 1 : 0;
    }
}
