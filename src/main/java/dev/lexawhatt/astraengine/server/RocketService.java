package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.api.AstraCosmos.DiscoverResult;
import dev.lexawhatt.astraengine.cosmos.BodyApproach;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosIds;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import dev.lexawhatt.astraengine.surface.BodyFixedFrame;
import dev.lexawhatt.astraengine.surface.EarthEphemeris;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import dev.lexawhatt.astraengine.cosmos.OrbitalTimeline;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.EarthLandingTarget;
import dev.lexawhatt.astraengine.network.SurfacePayload;
import dev.lexawhatt.astraengine.cosmos.GalacticNavigation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.ExplorationPayload;
import dev.lexawhatt.astraengine.network.CustomSystemsPayload;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.network.EarthLandingPayload;
import dev.lexawhatt.astraengine.network.FlightControlPayload;
import dev.lexawhatt.astraengine.network.FlightSpeedPayload;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread owner of virtual rocket navigation and bounded, recoverable physical flight staging. */
public final class RocketService implements AutoCloseable {
    public static final ResourceKey<Level> FLIGHT = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("astraengine", "flight"));
    private static final String RECOVERY = "astraengine_flight_recovery";
    private static final TicketType<UUID> TICKET = TicketType.create("astraengine_flight", UUID::compareTo, 240);
    private static final SpaceVector ZERO = new SpaceVector(0, 0, 0);
    private final MinecraftServer server;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Integer> lastAction = new HashMap<>();
    private final Map<UUID, List<String>> sentCustomIds = new HashMap<>();
    private final Map<UUID, Integer> recoveryAttempts = new HashMap<>();
    private EarthEphemeris.Sample calendar;
    private EarthEphemeris.Sample previousCalendar;
    private PlanetarySkyProfile calendarProfile;
    private long calendarDayTime;
    private long calendarEpoch;
    private boolean calendarAdvancing;
    private boolean calendarChanged;

    /** Creates the service owned by one Minecraft server. */
    public RocketService(MinecraftServer server) { this.server = server; refreshCalendar(); }

    private void refreshCalendar() {
        previousCalendar = calendar;
        calendarChanged = false;
        if (!EarthWorlds.active(server)) { calendar = null; return; }
        PlanetarySkyProfile profile = SkyState.get(server).profile();
        long dayTime = server.overworld().getDayTime();
        boolean advancing = server.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        if (calendar != null) {
            long delta = dayTime - calendarDayTime;
            calendarChanged = delta < 0 || delta > 1 || !advancing && delta != 0 || advancing != calendarAdvancing
                    || profile.yearDays() != calendarProfile.yearDays()
                    || profile.axialTiltDegrees() != calendarProfile.axialTiltDegrees()
                    || profile.eccentricity() != calendarProfile.eccentricity()
                    || profile.seasonOffsetDays() != calendarProfile.seasonOffsetDays();
            if (calendarChanged) { calendarEpoch++; }
        }
        calendarProfile = profile;
        calendarDayTime = dayTime;
        calendarAdvancing = advancing;
        calendar = EarthEphemeris.sample(profile, dayTime, 0);
        if (previousCalendar == null) { previousCalendar = calendar; }
    }

    private double orbitalSeconds(ExplorationCatalog catalog, String systemId) {
        return calendar != null && "sol".equals(systemId) ? calendar.orbitalSeconds() : catalog.clockTicks() / 20.0;
    }

    private double previousOrbitalSeconds(ExplorationCatalog catalog, String systemId) {
        return previousCalendar != null && "sol".equals(systemId)
                ? previousCalendar.orbitalSeconds() : Math.max(0, catalog.clockTicks() - 1) / 20.0;
    }

    private BodyFixedFrame surfaceFrame(SurfaceDefinition definition, CosmosSystem system, ExplorationCatalog catalog) {
        return calendar != null && "sol".equals(system.id())
                ? definition.calendarFrame(system, calendar.orbitalSeconds(), calendar.frame().bodyToSystem())
                : definition.frame(system, catalog.clockTicks() / 20.0, catalog.clockTicks());
    }

    private OrbitalTimeline timeline(ExplorationCatalog catalog, String systemId) {
        return calendar != null && "sol".equals(systemId)
                ? OrbitalTimeline.calendar(calendarProfile, calendarDayTime, calendarAdvancing)
                : OrbitalTimeline.elapsed(catalog.clockTicks() / 20.0);
    }

    private SpaceVector followBody(ExplorationCatalog catalog, CosmosSystem system, SpaceVector position) {
        return calendar != null && "sol".equals(system.id())
                ? FlightDynamics.followOrbitalMotion(position, system.bodies(),
                        previousOrbitalSeconds(catalog, system.id()), orbitalSeconds(catalog, system.id())) : position;
    }

    /** Whether this player is preparing or occupying rocket mode. Used to exclude other travel owners. */
    public boolean active(ServerPlayer player) { return sessions.containsKey(player.getUUID()); }
    /** Physical staging-world check, including orphaned sessions awaiting login recovery. */
    public static boolean isFlightWorld(ServerPlayer player) { return player.serverLevel().dimension().equals(FLIGHT); }

    /** Toggles the requesting player's presentation flight; no permissions or ability flags are elevated. */
    public void toggle(ServerPlayer player) {
        Session previous = sessions.remove(player.getUUID());
        if (previous != null) {
            if (!stop(player, previous, true)) {
                previous.returnRequested = true;
                sessions.put(player.getUUID(), previous);
                message(player, "preparing"); send(player, previous);
            } else { send(player, null); }
            return;
        }
        if (isFlightWorld(player)) { recoveryAttempts.remove(player.getUUID()); recover(player); return; }
        if (!player.isAlive() || player.isPassenger() || player.isSleeping()
                || player.serverLevel().dimension().equals(SystemWorlds.TRANSIT) || isFlightWorld(player)) {
            message(player, "unavailable"); return;
        }
        ServerLevel flight = server.getLevel(FLIGHT);
        if (flight == null) { message(player, "missing_world"); return; }
        if (!PreparedPlayerReturn.acceptsSource(server, player)) { message(player, "source_unavailable"); return; }
        ExplorationCatalog.get(server).player(player.getUUID());
        Session session = new Session(Point.of(player));
        session.takeoff = SurfaceWorlds.definition(player.serverLevel().dimension()).orElse(null);
        session.earthTakeoff = EarthWorlds.chart(player.serverLevel()).orElse(null);
        if (session.takeoff != null && (!SurfaceBindings.get(server).matches(player.serverLevel(), session.takeoff)
                || !canTakeOff(player, session.takeoff))
                || session.earthTakeoff != null && !canTakeOff(player, session.earthTakeoff)) {
            message(player, "surface_unavailable"); return;
        }
        sessions.put(player.getUUID(), session);
        flight.getChunkSource().addRegionTicket(TICKET, new ChunkPos(0, 0), 2, player.getUUID());
        message(player, "preparing");
    }

    /** Applies only one discrete own-player action per four ticks, rejecting uncharted destinations. */
    public void action(ServerPlayer player, FlightActionPayload payload) {
        if (!acceptAction(player)) { return; }
        if (payload.action() == FlightActionPayload.Action.TOGGLE) { toggle(player); return; }
        if (payload.action() == FlightActionPayload.Action.TAKE_OFF) {
            if (!active(player) && (SurfaceWorlds.definition(player.serverLevel().dimension()).isPresent()
                    || EarthWorlds.chart(player.serverLevel()).isPresent())) { toggle(player); }
            return;
        }
        Session session = sessions.get(player.getUUID());
        if (session == null || !session.entered || !isFlightWorld(player) || !player.isAlive()) { return; }
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        if (session.surface != null) {
            if (payload.action() == FlightActionPayload.Action.BRAKE) { cancelSurface(catalog, player, session, "surface_cancelled"); }
            return;
        }
        if (session.approach != null) {
            if (payload.action() == FlightActionPayload.Action.BRAKE) { finishApproach(catalog, player, session, "approach_cancelled"); }
            return;
        }
        if (session.jumpTicks > 0) { return; }
        switch (payload.action()) {
            case CHART_ATLAS -> {
                // The request type accepts only public atlas anchors, never private custom definitions.
                var result = catalog.discover(player.getUUID(), payload.target());
                if (result == DiscoverResult.LIMIT_REACHED) {
                    message(player, "chart_full");
                }
            }
            case SCAN -> {
                int added = catalog.revealNeighbors(pilot);
                player.sendSystemMessage(Component.translatable("astraengine.rocket.scanned", added,
                        pilot.discoveredSystems().size(), 256), true);
                if (pilot.discoveredCount() >= 256) {
                    player.sendSystemMessage(Component.translatable("astraengine.rocket.chart_full"));
                }
            }
            case JUMP_SYSTEM -> {
                if (!pilot.discoveredSystems().contains(payload.target())) {
                    message(player, "unknown_target"); return;
                }
                if (!pilot.visitedSystems().contains(payload.target())) {
                    message(player, "unvisited_target"); return;
                }
                boolean nearCurrent = pilot.systemId().equals(payload.target()) && pilot.position().length()
                        <= GalacticNavigation.arrivalRadiusMeters(currentSystem(catalog, pilot, session));
                if (nearCurrent) {
                    message(player, "unknown_target"); return;
                }
                session.jumpTarget = payload.target(); session.jumpBody = ""; session.jumpTicks = 80;
                pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
            }
            case LAND_BODY -> {
                CosmosSystem system = currentSystem(catalog, pilot, session);
                SurfaceDefinition definition = SurfaceDefinition.find(system.id(), payload.target()).orElse(null);
                CelestialBody body = system.bodies().stream().filter(value -> value.id().equals(payload.target()))
                        .findFirst().orElse(null);
                if (definition == null || body == null
                        || pilot.position().distance(system.positionAt(body, orbitalSeconds(catalog, system.id()))) > body.radiusMeters() * 6
                        || pilot.position().distance(system.positionAt(body, orbitalSeconds(catalog, system.id()))) < body.radiusMeters()) {
                    message(player, "surface_approach_first"); return;
                }
                if ("sol".equals(system.id()) && "earth".equals(body.id()) && EarthWorlds.active(server)) {
                    var frame = surfaceFrame(definition, system, catalog);
                    var target = EarthLandingTarget.aim(new ContinentalTerrain(EarthWorlds.terrainVersion(server),
                            ContinentalTerrain.SEED), frame.toBodyPoint(pilot.position()),
                            frame.toBodyDirection(pilot.orientation().forward()));
                    if (target.isEmpty()) { message(player, "surface_aim"); return; }
                    session.surface = new SurfaceTransfer(target.get(), system, surfaceFrame(SurfaceDefinition.byBody("earth"), system, catalog),
                            pilot.position(), pilot.orientation());
                } else {
                    session.surface = new SurfaceTransfer(definition, system, surfaceFrame(definition, system, catalog),
                            pilot.position(), pilot.orientation());
                }
                beginLanding(player, session, pilot, definition.bodyId());
            }
            case APPROACH_BODY -> {
                CosmosSystem system = currentSystem(catalog, pilot, session);
                CelestialBody body = system.bodies().stream().filter(value -> value.id().equals(payload.target()))
                        .findFirst().orElse(null);
                if (body == null) { message(player, "unknown_target"); return; }
                var route = BodyApproach.plan(system, body, new FlightDynamics.State(pilot.position(), pilot.velocity()),
                        pilot.orientation(), timeline(catalog, system.id()));
                if (route.isEmpty()) { message(player, "approach_blocked"); return; }
                session.approach = route.get(); session.approachTicks = 0;
                session.jumpTarget = pilot.systemId(); session.jumpBody = payload.target();
                session.jumpTicks = session.approach.durationTicks();
                pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
                session.controls.relocate(pilot.revision()); session.input = null;
            }
            case SPEED_UP -> pilot.speed(Math.min(FlightDynamics.MAX_SPEED, pilot.speedMetersPerSecond() * 1.5));
            case SPEED_DOWN -> pilot.speed(Math.max(FlightDynamics.MIN_SPEED, pilot.speedMetersPerSecond() / 1.5));
            case BRAKE -> pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
            default -> { }
        }
        catalog.setDirty(); send(player, session);
    }

    /** Accepts a visible geographic point with current navigation ownership, sharing the discrete rate limit. */
    public void earthLanding(ServerPlayer player, EarthLandingPayload payload) {
        Session session = sessions.get(player.getUUID());
        if (session == null || !session.entered || !isFlightWorld(player) || !player.isAlive()
                || session.surface != null || session.approach != null || session.jumpTicks > 0
                || payload.navigationEpoch() != session.controls.navigationEpoch() || !EarthWorlds.active(server)
                || !acceptAction(player)) { return; }
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        CosmosSystem system = currentSystem(catalog, pilot, session);
        if (!"sol".equals(system.id())) { return; }
        var frame = surfaceFrame(SurfaceDefinition.byBody("earth"), system, catalog);
        var target = EarthLandingTarget.visible(new ContinentalTerrain(EarthWorlds.terrainVersion(server),
                ContinentalTerrain.SEED), frame.toBodyPoint(pilot.position()), payload.normal());
        if (target.isEmpty()) { message(player, "surface_aim"); return; }
        session.surface = new SurfaceTransfer(target.get(), system, surfaceFrame(SurfaceDefinition.byBody("earth"), system, catalog),
                pilot.position(), pilot.orientation());
        beginLanding(player, session, pilot, "earth");
        catalog.setDirty(); send(player, session);
    }

    private void beginLanding(ServerPlayer player, Session session, ExplorationCatalog.Pilot pilot, String bodyId) {
        session.jumpTarget = pilot.systemId(); session.jumpBody = bodyId; session.jumpTicks = 1;
        pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
        session.controls.relocate(pilot.revision()); session.input = null;
        message(player, "surface_preparing");
    }

    /** Sets inspection speed for the sender's live manual session, sharing the four-tick discrete action limit. */
    public void speed(ServerPlayer player, FlightSpeedPayload payload) {
        Session session = sessions.get(player.getUUID());
        if (session == null || !session.entered || !isFlightWorld(player) || !player.isAlive()
                || session.approach != null || session.jumpTicks > 0 || !acceptAction(player)) {
            return;
        }
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        catalog.player(player.getUUID()).speed(payload.speedMetersPerSecond());
        catalog.setDirty();
        send(player, session);
    }

    private boolean acceptAction(ServerPlayer player) {
        int tick = server.getTickCount();
        Integer last = lastAction.get(player.getUUID());
        if (last != null && tick - last >= 0 && tick - last < 4) {
            return false;
        }
        lastAction.put(player.getUUID(), tick);
        return true;
    }

    /** Accepts finite sequential controls at most once per server tick; abandoned controls expire after ten ticks. */
    public void control(ServerPlayer player, FlightControlPayload payload) {
        Session session = sessions.get(player.getUUID());
        int tick = server.getTickCount();
        if (session == null || !session.entered || !isFlightWorld(player) || !player.isAlive()
                || !session.controls.accept(payload.sequence(), payload.navigationEpoch(), tick)) { return; }
        if (session.surface != null) {
            if (payload.brake()) { cancelSurface(ExplorationCatalog.get(server), player, session, "surface_cancelled"); }
            return;
        }
        if (session.approach != null) {
            if (payload.brake()) { finishApproach(ExplorationCatalog.get(server), player, session, "approach_cancelled"); }
            return;
        }
        session.input = new FlightDynamics.Input(payload.forward(), payload.strafe(), payload.vertical(),
                payload.orientation(), payload.brake());
    }

    /** Polls preparation and advances virtual flight at 20 Hz without blocking on chunk generation. */
    public void tick() {
        refreshCalendar();
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        boolean occupied = sessions.entrySet().stream().anyMatch(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            return entry.getValue().entered && player != null && player.isAlive() && isFlightWorld(player);
        });
        occupied |= server.getPlayerList().getPlayers().stream().anyMatch(player -> player.isAlive()
                && (SurfaceWorlds.definition(player.serverLevel().dimension()).isPresent()
                        || EarthWorlds.chart(player.serverLevel()).isPresent()));
        catalog.tick(occupied);
        if (server.getTickCount() % 20 == 0) {
            for (var retry : Map.copyOf(recoveryAttempts).entrySet()) {
                ServerPlayer recovering = server.getPlayerList().getPlayer(retry.getKey());
                if (recovering != null && retry.getValue() < 12 && !sessions.containsKey(retry.getKey())) {
                    recover(recovering);
                }
            }
        }
        SurfaceWorlds.maintainBorders(server);
        if (server.getTickCount() % 5 == 0) {
            for (ServerPlayer observer : server.getPlayerList().getPlayers()) {
                if (!sessions.containsKey(observer.getUUID())
                        && (SurfaceWorlds.definition(observer.serverLevel().dimension()).isPresent()
                                || EarthWorlds.chart(observer.serverLevel()).isPresent())) { sendSurface(observer, null); }
            }
        }
        Iterator<Map.Entry<UUID, Session>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Session> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Session session = entry.getValue();
            if (player == null) {
                if (session.approach != null) { stopApproachMotion(entry.getKey()); }
                sentCustomIds.remove(entry.getKey());
                if (session.surface != null) { session.surface.release(server, entry.getKey()); }
                release(entry.getKey()); iterator.remove(); continue;
            }
            if (!player.isAlive() || (session.entered && !isFlightWorld(player))) {
                stop(player, session, false); iterator.remove(); send(player, null); continue;
            }
            if (session.returnRequested) {
                player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
                if (session.surface != null) { session.surface.retain(server, player.getUUID()); }
                if (server.getTickCount() % 100 == 0) {
                    player.serverLevel().getChunkSource().addRegionTicket(TICKET, new ChunkPos(0, 0), 2, player.getUUID());
                }
                if (stop(player, session, true)) { iterator.remove(); send(player, null); continue; }
                if (++session.returnTicks > 220) {
                    session.returnRequested = false; session.returnTicks = 0;
                    PreparedPlayerReturn.release(server, player.getUUID(), session.source.dimension(), session.source.position());
                    message(player, "surface_failed");
                }
                continue;
            }
            if (!session.entered) {
                if (++session.age > 220 || !player.serverLevel().dimension().equals(session.source.dimension())) {
                    stop(player, session, false); iterator.remove(); message(player, "failed"); continue;
                }
                ServerLevel flight = server.getLevel(FLIGHT);
                if (flight == null) { stop(player, session, false); iterator.remove(); continue; }
                if (flight.getChunkSource().getChunkNow(0, 0) == null) { continue; }
                initializeLanding(flight, catalog);
                if (!flight.isEmptyBlock(new BlockPos(8, 80, 8)) || !flight.isEmptyBlock(new BlockPos(8, 81, 8))) {
                    stop(player, session, false); iterator.remove(); message(player, "obstructed"); continue;
                }
                if (!PreparedPlayerReturn.acceptsSource(server, player)) {
                    stop(player, session, false); iterator.remove(); message(player, "source_unavailable");
                    send(player, null); continue;
                }
                if (session.takeoff != null && !canTakeOff(player, session.takeoff)
                        || session.earthTakeoff != null && !canTakeOff(player, session.earthTakeoff)) {
                    stop(player, session, false); iterator.remove(); message(player, "surface_unavailable");
                    send(player, null); continue;
                }
                session.source = Point.of(player);
                player.getPersistentData().put(RECOVERY, session.source.save());
                ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
                if (session.takeoff != null || session.earthTakeoff != null) {
                    CosmosSystem system = catalog.system(session.earthTakeoff == null ? session.takeoff.systemId() : "sol");
                    SpaceVector localEye = new SpaceVector(player.getX(), player.getEyeY(), player.getZ());
                    var localOrientation = FlightOrientation.fromAngles(player.getYRot(), player.getXRot(), 0);
                    session.surface = session.earthTakeoff == null
                            ? SurfaceTransfer.ascent(session.takeoff, system, surfaceFrame(session.takeoff, system, catalog), localEye, localOrientation)
                            : SurfaceTransfer.ascent(session.earthTakeoff, system, surfaceFrame(SurfaceDefinition.byBody("earth"), system, catalog), localEye, localOrientation);
                    // Ground world identity, not stale navigation state, determines the departure system.
                    SpaceVector position = session.surface.originalPosition();
                    FlightOrientation view = session.surface.originalOrientation();
                    pilot.visit(system.id(), position, view);
                    pilot.navigate(new FlightDynamics.State(position, ZERO), view);
                    session.system = system;
                    session.jumpTarget = system.id(); session.jumpBody = session.surface.definition().bodyId();
                    session.jumpTicks = session.surface.remainingTicks();
                }
                pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
                session.controls.relocate(pilot.revision());
                player.teleportTo(flight, 8.5, 80, 8.5, Set.of(), pilot.yaw(), pilot.pitch());
                if (!isFlightWorld(player)) {
                    stop(player, session, false); iterator.remove(); message(player, "failed"); send(player, null); continue;
                }
                session.entered = true; send(player, session);
            } else {
                player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
                if (player.position().distanceToSqr(8.5, 80, 8.5) > 0.01) { player.teleportTo(8.5, 80, 8.5); }
                if (server.getTickCount() % 100 == 0) {
                    player.serverLevel().getChunkSource().addRegionTicket(TICKET, new ChunkPos(0, 0), 2, player.getUUID());
                }
                advance(catalog, player, session);
                if (session.surfaceCommitted) {
                    release(player.getUUID()); iterator.remove(); send(player, null); continue;
                }
                if (server.getTickCount() % 2 == 0) { send(player, session); }
            }
        }
    }

    private void advance(ExplorationCatalog catalog, ServerPlayer player, Session session) {
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        if (calendarChanged && "sol".equals(pilot.systemId())) {
            if (session.surface != null) { cancelSurface(catalog, player, session, "surface_failed"); return; }
            if (session.approach != null) {
                pilot.navigate(new FlightDynamics.State(followBody(catalog, currentSystem(catalog, pilot, session),
                        pilot.position()), ZERO), pilot.orientation());
                finishApproach(catalog, player, session, "approach_failed"); return;
            }
        }
        if (session.surface != null) {
            advanceSurface(catalog, player, session);
        } else if (session.approach != null) {
            BodyApproach route = session.approach;
            BodyApproach.Frame next = route.frame(session.approachTicks + 1);
            double endSeconds = orbitalSeconds(catalog, pilot.systemId());
            if (Math.abs(route.orbitalSecondsAt(session.approachTicks + 1) - endSeconds)
                    > Math.max(1e-6, Math.ulp(endSeconds) * 4)
                    || !FlightDynamics.clearSegment(pilot.position(), next.state().position(),
                    currentSystem(catalog, pilot, session).bodies(), previousOrbitalSeconds(catalog, pilot.systemId()), endSeconds)) {
                finishApproach(catalog, player, session, "approach_failed");
                return;
            }
            pilot.navigate(next.state(), next.orientation());
            session.approachTicks++;
            session.jumpTicks = route.durationTicks() - session.approachTicks;
            if (session.jumpTicks == 0) { finishApproach(catalog, player, session, null); }
        } else if (session.jumpTicks > 0) {
            if (--session.jumpTicks == 0) {
                CosmosSystem system = catalog.system(session.jumpTarget);
                CelestialBody body = session.jumpBody.isEmpty() ? system.bodies().getFirst()
                        : system.bodies().stream().filter(value -> value.id().equals(session.jumpBody)).findFirst().orElseThrow();
                pilot.arrive(system.id(), ExplorationCatalog.arrival(system, body, orbitalSeconds(catalog, system.id())));
                session.system = system;
                session.controls.relocate(pilot.revision());
                session.input = null; session.jumpTarget = ""; session.jumpBody = "";
                player.teleportTo(8.5, 80, 8.5);
                player.setYRot(pilot.yaw()); player.setXRot(pilot.pitch());
                send(player, session);
            }
        } else {
            FlightDynamics.Input input = session.input;
            if (input == null || session.controls.expired(server.getTickCount())) {
                input = new FlightDynamics.Input(0, 0, 0, pilot.orientation(), true);
            }
            CosmosSystem system = currentSystem(catalog, pilot, session);
            SpaceVector origin = followBody(catalog, system, pilot.position());
            FlightDynamics.State next = FlightDynamics.step(new FlightDynamics.State(origin, pilot.velocity()),
                    input, pilot.speedMetersPerSecond(), 0.05, system.bodies(), orbitalSeconds(catalog, system.id()));
            if (!input.brake() && (input.forward() != 0 || input.strafe() != 0 || input.vertical() != 0)
                    && !next.position().equals(origin)) {
                var arrival = GalacticNavigation.firstArrival(system, origin, next.position(),
                        chartedSystems(catalog, pilot, session));
                if (arrival.isPresent()) {
                    GalacticNavigation.Arrival reached = arrival.get();
                    boolean firstVisit = !pilot.visitedSystems().contains(reached.system().id());
                    pilot.visit(reached.system().id(), reached.position(), input.orientation());
                    catalog.revealNeighbors(pilot);
                    session.system = reached.system();
                    session.controls.relocate(pilot.revision());
                    session.input = null;
                    catalog.setDirty();
                    send(player, session);
                    if (firstVisit) {
                        player.sendSystemMessage(Component.translatable("astraengine.rocket.visited",
                                reached.system().name()), true);
                        if (pilot.discoveredCount() >= 256) {
                            player.sendSystemMessage(Component.translatable("astraengine.rocket.chart_full"));
                        }
                    }
                    return;
                }
            }
            pilot.navigate(next, input.orientation());
        }
        catalog.setDirty();
    }

    private void finishApproach(ExplorationCatalog catalog, ServerPlayer player, Session session, String reason) {
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
        session.approach = null; session.approachTicks = 0;
        session.jumpTicks = 0; session.jumpTarget = ""; session.jumpBody = ""; session.input = null;
        session.controls.relocate(pilot.revision());
        catalog.setDirty();
        if (reason != null) { message(player, reason); }
        send(player, session);
    }

    private void advanceSurface(ExplorationCatalog catalog, ServerPlayer player, Session session) {
        SurfaceTransfer transfer = session.surface;
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        CosmosSystem system = currentSystem(catalog, pilot, session);
        boolean sourceAtCurrentTime = !transfer.prepared();
        if (!transfer.prepared()) {
            SurfaceTransfer.Frame source = transfer.sourceFrame(surfaceFrame(transfer.definition(), system, catalog));
            pilot.navigate(new FlightDynamics.State(source.position(), ZERO), source.orientation());
            catalog.setDirty();
            if (!transfer.prepare(server, player)) {
                if (transfer.timedOut()) { cancelSurface(catalog, player, session, "surface_failed"); }
                return;
            }
        }
        transfer.retain(server, player.getUUID());
        SurfaceTransfer.Frame frame = transfer.advance(surfaceFrame(transfer.definition(), system, catalog));
        double seconds = orbitalSeconds(catalog, system.id());
        if (!FlightDynamics.clearSurfaceSegment(pilot.position(), frame.position(), system.bodies(),
                sourceAtCurrentTime ? seconds : previousOrbitalSeconds(catalog, system.id()), seconds,
                transfer.definition().bodyId())) {
            cancelSurface(catalog, player, session, "surface_failed"); return;
        }
        pilot.navigate(new FlightDynamics.State(frame.position(), ZERO), frame.orientation());
        session.jumpTicks = transfer.remainingTicks();
        catalog.setDirty();
        if (!transfer.finished()) { return; }
        if (transfer.ascending()) {
            transfer.release(server, player.getUUID()); session.surface = null;
            finishApproach(catalog, player, session, "surface_departed");
        } else if (transfer.canCommit(server, player)) {
            Vec3 feet = transfer.landingFeet();
            FlightOrientation local = transfer.landingOrientation();
            ServerLevel target = server.getLevel(transfer.dimension());
            player.teleportTo(target, feet.x, feet.y, feet.z, Set.of(), local.yaw(), local.pitch());
            if (player.serverLevel() != target || player.position().distanceToSqr(feet) > 1e-6) {
                cancelSurface(catalog, player, session, "surface_failed"); return;
            }
            player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
            player.getPersistentData().remove(RECOVERY);
            transfer.release(server, player.getUUID()); session.surface = null;
            session.surfaceCommitted = true;
            message(player, "surface_arrived");
        } else {
            cancelSurface(catalog, player, session, "surface_failed");
        }
    }

    private static boolean canTakeOff(ServerPlayer player, SurfaceDefinition definition) {
        return definition.patch().contains(player.getX(), player.getZ())
                && player.getEyeY() >= definition.patch().seaY()
                    + dev.lexawhatt.astraengine.surface.SurfaceGeography.MIN_HEIGHT_METERS
                && player.getY() >= player.serverLevel().getMinBuildHeight() + 1
                && player.getEyeY() < player.serverLevel().getMaxBuildHeight() - 1
                && player.serverLevel().canSeeSky(player.blockPosition()) && !player.isInWater();
    }

    private static boolean canTakeOff(ServerPlayer player, EarthChart chart) {
        return EarthWorlds.chart(player.serverLevel()).map(chart::equals).orElse(false)
                && chart.contains(new SpaceVector(player.getX(), player.getY(), player.getZ()))
                && player.getEyeY() + chart.altitudeOriginMeters() >= -48
                && player.getY() >= player.serverLevel().getMinBuildHeight() + 1
                && player.getEyeY() < player.serverLevel().getMaxBuildHeight() - 1
                && player.serverLevel().canSeeSky(BlockPos.containing(player.getX(), player.getEyeY(), player.getZ()))
                && player.serverLevel().getFluidState(BlockPos.containing(player.getX(), player.getEyeY(), player.getZ())).isEmpty();
    }

    private void cancelSurface(ExplorationCatalog catalog, ServerPlayer player, Session session, String reason) {
        SurfaceTransfer transfer = session.surface;
        if (transfer.ascending() && !session.source.teleport(player, server)) {
            session.returnRequested = true; message(player, "preparing"); return;
        }
        transfer.release(server, player.getUUID());
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        SurfaceTransfer.Frame source = transfer.sourceFrame(surfaceFrame(transfer.definition(), currentSystem(catalog, pilot, session), catalog));
        pilot.navigate(new FlightDynamics.State(source.position(), ZERO), source.orientation());
        session.surface = null;
        if (transfer.ascending()) {
            player.getPersistentData().remove(RECOVERY);
            session.surfaceCommitted = true;
        }
        finishApproach(catalog, player, session, reason);
    }

    private void sendSurface(ServerPlayer player, Session session) {
        long clockTicks = ExplorationCatalog.get(server).clockTicks();
        SurfaceDefinition ground = SurfaceWorlds.definition(player.serverLevel().dimension()).orElse(null);
        SurfaceTransfer transfer = session == null ? null : session.surface;
        SurfacePayload payload;
        if (transfer != null) {
            SurfacePayload.Phase phase = !transfer.prepared() ? SurfacePayload.Phase.PREPARING
                    : transfer.ascending() ? SurfacePayload.Phase.ASCENDING : SurfacePayload.Phase.DESCENDING;
            payload = new SurfacePayload(transfer.definition().bodyId(), clockTicks, phase, transfer.remainingTicks());
        } else if (ground != null) {
            payload = new SurfacePayload(ground.bodyId(), clockTicks, SurfacePayload.Phase.SURFACE, 0);
        } else if (EarthWorlds.chart(player.serverLevel()).isPresent()) {
            payload = new SurfacePayload("earth", clockTicks, SurfacePayload.Phase.SURFACE, 0);
        } else {
            payload = new SurfacePayload("", clockTicks, SurfacePayload.Phase.NONE, 0);
        }
        if (calendar != null) {
            payload = new SurfacePayload(payload.bodyId(), clockTicks, payload.phase(), payload.remainingTicks(),
                    calendar.orbitalSeconds(), calendar.frame().bodyToSystem(), calendarEpoch);
        }
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static CosmosSystem currentSystem(ExplorationCatalog catalog, ExplorationCatalog.Pilot pilot,
            Session session) {
        if (session.system == null || !session.system.id().equals(pilot.systemId())) {
            session.system = catalog.system(pilot.systemId());
        }
        return session.system;
    }

    private static List<CosmosSystem> chartedSystems(ExplorationCatalog catalog, ExplorationCatalog.Pilot pilot,
            Session session) {
        // Charts only grow and saved descriptors are immutable, so size invalidates this session-owned cache.
        if (session.charted.size() != pilot.discoveredCount()) {
            session.charted = pilot.discoveredSystems().stream().map(catalog::system).toList();
        }
        return session.charted;
    }

    /** Recovers an interrupted flight to its original real location, while retaining private discoveries. */
    public void recover(ServerPlayer player) {
        sentCustomIds.remove(player.getUUID());
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        catalog.revealNeighbors(catalog.player(player.getUUID()));
        if (isFlightWorld(player)
                && !Point.load(player.getPersistentData().getCompound(RECOVERY), server).teleport(player, server)) {
            int attempts = recoveryAttempts.merge(player.getUUID(), 1, Integer::sum);
            if (attempts == 1) { message(player, "preparing"); }
            if (attempts == 12) {
                Point source = Point.load(player.getPersistentData().getCompound(RECOVERY), server);
                PreparedPlayerReturn.release(server, player.getUUID(), source.dimension(), source.position());
                message(player, "surface_failed");
            }
            send(player, null); return;
        }
        recoveryAttempts.remove(player.getUUID());
        player.getPersistentData().remove(RECOVERY);
        send(player, null);
    }

    /** Releases runtime tickets on logout. The persisted recovery point remains for the next login. */
    public void disconnect(ServerPlayer player) {
        Session session = sessions.remove(player.getUUID());
        if (session != null && (session.approach != null || session.surface != null)) { stopApproachMotion(player.getUUID()); }
        if (session != null && session.surface != null) { session.surface.release(server, player.getUUID()); }
        if (session != null) {
            PreparedPlayerReturn.release(server, player.getUUID(), session.source.dimension(), session.source.position());
        } else if (recoveryAttempts.containsKey(player.getUUID())) {
            Point source = Point.load(player.getPersistentData().getCompound(RECOVERY), server);
            PreparedPlayerReturn.release(server, player.getUUID(), source.dimension(), source.position());
        }
        lastAction.remove(player.getUUID()); release(player.getUUID());
        recoveryAttempts.remove(player.getUUID());
        sentCustomIds.remove(player.getUUID());
    }

    /** Emits a private snapshot; permits map viewing before entering Rocket mode. */
    public void send(ServerPlayer player) { send(player, sessions.get(player.getUUID())); }

    private void send(ServerPlayer player, Session session) {
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        List<String> customIds = pilot.discoveredSystems().stream().filter(CosmosIds::isCustom).toList();
        if (!customIds.equals(sentCustomIds.get(player.getUUID()))) {
            // The ordered play connection delivers definitions before any snapshot can reference them.
            PacketDistributor.sendToPlayer(player, new CustomSystemsPayload(customIds.stream().map(catalog::system).toList()));
            sentCustomIds.put(player.getUUID(), customIds);
        }
        boolean active = session != null && session.entered && isFlightWorld(player);
        String target = !active ? "" : session.jumpTarget + (session.jumpBody.isEmpty() ? "" : "/" + session.jumpBody);
        PacketDistributor.sendToPlayer(player, new ExplorationPayload(catalog.galaxySeed(), catalog.clockTicks(),
                pilot.systemId(), pilot.position(), pilot.velocity(), active, pilot.speedMetersPerSecond(), pilot.orientation(),
                active ? session.jumpTicks : 0, target, pilot.discoveredSystems(), pilot.visitedSystems(), pilot.revision(),
                active ? session.controls.navigationEpoch() : 0, orbitalSeconds(catalog, pilot.systemId()),
                calendar != null && "sol".equals(pilot.systemId()) ? calendar.frame().bodyToSystem() : null,
                calendar != null && "sol".equals(pilot.systemId()) ? calendarEpoch : 0));
        sendSurface(player, session);
    }

    private boolean stop(ServerPlayer player, Session session, boolean restore) {
        if (restore && session.entered && isFlightWorld(player) && player.isAlive()
                && !session.source.teleport(player, server)) { return false; }
        if (session.approach != null || session.surface != null) { stopApproachMotion(player.getUUID()); }
        if (session.surface != null) { session.surface.release(server, player.getUUID()); }
        player.getPersistentData().remove(RECOVERY); release(player.getUUID());
        PreparedPlayerReturn.release(server, player.getUUID(), session.source.dimension(), session.source.position());
        return true;
    }

    private static void initializeLanding(ServerLevel level, ExplorationCatalog catalog) {
        if (catalog.landingInitialized()) { return; }
        for (int x = 4; x <= 12; x++) {
            for (int z = 4; z <= 12; z++) {
                BlockPos floor = new BlockPos(x, 79, z);
                if (level.isEmptyBlock(floor)) { level.setBlockAndUpdate(floor, Blocks.BARRIER.defaultBlockState()); }
            }
        }
        catalog.markLandingInitialized();
    }
    private void release(UUID id) {
        ServerLevel level = server.getLevel(FLIGHT);
        if (level != null) { level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(0, 0), 2, id); }
    }
    private static void message(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable("astraengine.rocket." + key), true);
    }
    private void stopApproachMotion(UUID id) {
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        ExplorationCatalog.Pilot pilot = catalog.player(id);
        pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
        catalog.setDirty();
    }

    @Override
    public void close() {
        sessions.forEach((id, session) -> {
            if (session.approach != null || session.surface != null) { stopApproachMotion(id); }
            if (session.surface != null) { session.surface.release(server, id); }
            PreparedPlayerReturn.release(server, id, session.source.dimension(), session.source.position());
            release(id);
        });
        for (UUID id : recoveryAttempts.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                Point source = Point.load(player.getPersistentData().getCompound(RECOVERY), server);
                PreparedPlayerReturn.release(server, id, source.dimension(), source.position());
            }
        }
        sessions.clear(); lastAction.clear(); sentCustomIds.clear(); recoveryAttempts.clear();
    }

    private static final class Session {
        private Point source;
        private CosmosSystem system;
        private List<CosmosSystem> charted = List.of();
        private int age;
        private boolean entered;
        private final FlightInputWindow controls = new FlightInputWindow();
        private FlightDynamics.Input input;
        private BodyApproach approach;
        private SurfaceDefinition takeoff;
        private EarthChart earthTakeoff;
        private SurfaceTransfer surface;
        private boolean surfaceCommitted;
        private boolean returnRequested;
        private int returnTicks;
        private int approachTicks;
        private int jumpTicks;
        private String jumpTarget = "";
        private String jumpBody = "";
        private Session(Point source) { this.source = source; }
    }
    private record Point(ResourceKey<Level> dimension, Vec3 position, float yaw, float pitch) {
        static Point of(ServerPlayer player) {
            return new Point(player.serverLevel().dimension(), player.position(), player.getYRot(), player.getXRot());
        }
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", dimension.location().toString());
            tag.putDouble("x", position.x); tag.putDouble("y", position.y); tag.putDouble("z", position.z);
            tag.putFloat("yaw", yaw); tag.putFloat("pitch", pitch); return tag;
        }
        static Point load(CompoundTag tag, MinecraftServer server) {
            ResourceLocation location = ResourceLocation.tryParse(tag.getString("dimension"));
            if (location != null && tag.contains("x", Tag.TAG_DOUBLE) && tag.contains("y", Tag.TAG_DOUBLE)
                    && tag.contains("z", Tag.TAG_DOUBLE) && tag.contains("yaw", Tag.TAG_FLOAT) && tag.contains("pitch", Tag.TAG_FLOAT)) {
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
                ServerLevel level = server.getLevel(key);
                Vec3 position = new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
                float yaw = tag.getFloat("yaw"), pitch = tag.getFloat("pitch");
                if (level != null && !key.equals(FLIGHT) && !key.equals(SystemWorlds.TRANSIT)
                        && Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
                        && Math.abs(position.x) < 29_999_984 && Math.abs(position.z) < 29_999_984
                        && position.y >= level.getMinBuildHeight() && position.y < level.getMaxBuildHeight()
                        && Float.isFinite(yaw) && Float.isFinite(pitch)) {
                    return new Point(key, position, yaw, pitch);
                }
            }
            return new Point(Level.OVERWORLD, Vec3.atBottomCenterOf(server.overworld().getSharedSpawnPos()), 0, 0);
        }
        boolean teleport(ServerPlayer player, MinecraftServer server) {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) { return Point.load(new CompoundTag(), server).teleport(player, server); }
            return PreparedPlayerReturn.attempt(server, player, dimension, position, yaw, pitch);
        }
    }
}
