package dev.lexawhatt.astraengine.client.flight;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.client.surface.EarthStateClient;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.client.render.RenderOptions;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.solar.SolarStateClient;
import dev.lexawhatt.astraengine.client.surface.SurfaceStateClient;
import dev.lexawhatt.astraengine.client.surface.SurfaceSkyRenderer;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosIds;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.GalacticNavigation;
import dev.lexawhatt.astraengine.cosmos.GalaxyDescriptor;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.UniverseGenerator;
import dev.lexawhatt.astraengine.network.ExplorationPayload;
import dev.lexawhatt.astraengine.network.ExplorationReceivedEvent;
import dev.lexawhatt.astraengine.network.SurfaceReceivedEvent;
import dev.lexawhatt.astraengine.network.SurfacePayload;
import dev.lexawhatt.astraengine.network.CustomSystemsReceivedEvent;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.network.EarthLandingPayload;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthLandingTarget;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import dev.lexawhatt.astraengine.network.FlightControlPayload;
import dev.lexawhatt.astraengine.network.FlightSpeedPayload;
import dev.lexawhatt.astraengine.server.RocketService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

/** Client presentation/input for authoritative virtual flight; no world or celestial state is mutated here. */
public final class RocketController {
    private final Minecraft minecraft = Minecraft.getInstance();
    private final CosmosRenderer renderer = new CosmosRenderer();
    private final RenderOptions options;
    private final SolarStateClient solar;
    private final SurfaceStateClient surface = new SurfaceStateClient();
    private final KeyMapping toggle = key("toggle", GLFW.GLFW_KEY_R);
    private final KeyMapping map = key("map", GLFW.GLFW_KEY_M);
    private final KeyMapping scan = key("scan", GLFW.GLFW_KEY_C);
    private final KeyMapping brake = key("brake", GLFW.GLFW_KEY_B);
    private final KeyMapping land = key("land", GLFW.GLFW_KEY_L);
    private final KeyMapping rollLeft = key("roll_left", GLFW.GLFW_KEY_Q);
    private final KeyMapping rollRight = key("roll_right", GLFW.GLFW_KEY_E);
    private final KeyMapping faster = key("faster", GLFW.GLFW_KEY_EQUAL);
    private final KeyMapping slower = key("slower", GLFW.GLFW_KEY_MINUS);
    private final EarthStateClient earth;
    private final FlightCamera flightCamera = new FlightCamera();
    private final Map<String, CosmosSystem> systems = new HashMap<>();
    private String targetSystem = "";
    private boolean targetGalaxy;
    private int targetGalaxyIndex;
    private List<GalaxyDescriptor> galaxyAtlas = List.of();
    private String pendingAtlasTarget = "";
    private long pendingAtlasEpoch;
    private int pendingAtlasTicks;
    private Map<String, CosmosSystem> customSystems = Map.of();
    private ExplorationPayload snapshot;
    private SpaceVector previousPosition = SpaceVector.ZERO;
    private FlightOrientation previousOrientation = FlightOrientation.IDENTITY;
    private boolean finishingGuidance;
    private long receivedAt;
    private double previousClockSeconds;
    private long previousCameraTime;
    private long sequence;
    private float forward;
    private float strafe;
    private float vertical;
    private double pendingYawDegrees;
    private double pendingPitchDegrees;
    private double pendingSpeedSteps;
    private int speedActionTicks;
    private float smoothing = 0.35f;
    private boolean mapRequested;
    private boolean atlasRequested;
    private boolean wasActive;
    private CameraType previousCamera;
    private String targetBody = "earth";
    private Matrix4f viewProjection;

    /** Shares visual quality controls with the existing renderer, keeping independent flight ownership. */
    public RocketController(RenderOptions options, SolarStateClient solar, EarthStateClient earth) {
        if (earth == null) { throw new IllegalArgumentException("Earth connection owner is required"); }
        this.earth = earth;
        this.options = options; this.solar = solar; renderer.setBloomOptions(options);
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.astraengine.flight." + name, code, "key.categories.astraengine");
    }

    public void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(toggle); event.register(map); event.register(scan); event.register(brake);
        event.register(rollLeft); event.register(rollRight); event.register(faster); event.register(slower);
        event.register(land);
    }

    /** Shared connection-scoped surface context for the registered fixed-dimension effects. */
    public SurfaceStateClient surfaceState() { return surface; }

    /** Receives authoritative handoff progress; it never transfers the local player. */
    public void receiveSurface(SurfaceReceivedEvent event) { surface.receive(event); }

    public void registerShaders(RegisterShadersEvent event) {
        pendingYawDegrees = 0; pendingPitchDegrees = 0;
        previousCameraTime = System.nanoTime();
        renderer.registerShaders(event);
    }

    /** Receives a server-owned snapshot; descriptor derivation never makes an object discovered. */
    public void receive(ExplorationReceivedEvent event) {
        ExplorationPayload incoming = event.payload();
        if (snapshot != null && snapshot.galaxySeed() == incoming.galaxySeed() && incoming.revision() < snapshot.revision()) { return; }
        for (String id : incoming.discoveredSystems()) {
            if (CosmosIds.isCustom(id) && !customSystems.containsKey(id)) {
                throw new IllegalArgumentException("Navigation referenced an unsynchronized custom system: " + id);
            }
        }
        if (snapshot == null || snapshot.galaxySeed() != incoming.galaxySeed()) {
            systems.clear();
            galaxyAtlas = UniverseGenerator.galaxies(incoming.galaxySeed());
        }
        boolean wasApproaching = snapshot != null && snapshot.approaching();
        boolean manualRebase = snapshot != null && snapshot.active() && incoming.active()
                && !snapshot.systemId().equals(incoming.systemId()) && !snapshot.interstellarJump()
                && incoming.jumpTicks() == 0;
        boolean guidedChange = snapshot != null && snapshot.active() && incoming.active()
                && snapshot.systemId().equals(incoming.systemId()) && (wasApproaching || incoming.approaching());
        boolean relocated = snapshot == null || !snapshot.active() || !snapshot.systemId().equals(incoming.systemId())
                || (snapshot.navigationEpoch() != incoming.navigationEpoch() && !guidedChange)
                || (snapshot.interstellarJump() && incoming.jumpTicks() == 0);
        previousPosition = manualRebase ? currentSystem().galaxyPosition().subtract(system(incoming.systemId()).galaxyPosition())
                .multiply(CosmosGenerator.LIGHT_YEAR).add(visualPosition()) : relocated ? incoming.position() : visualPosition();
        boolean resetView = relocated && !manualRebase;
        previousOrientation = resetView ? incoming.orientation() : orientation();
        previousClockSeconds = resetView ? incoming.clockTicks() / 20.0 : timeSeconds();
        snapshot = incoming;
        if (incoming.systemId().equals(targetSystem)) { targetSystem = ""; }
        receivedAt = System.nanoTime();
        if (relocated || !incoming.active() || incoming.approaching()) { finishingGuidance = false; }
        else if (wasApproaching) {
            finishingGuidance = true;
        }
        if (incoming.approaching()) {
            targetBody = incoming.approachBodyId();
            pendingYawDegrees = 0; pendingPitchDegrees = 0; pendingSpeedSteps = 0;
        }
        if (resetView && incoming.active() && minecraft.player != null) {
            flightCamera.reset(incoming.orientation());
            pendingYawDegrees = 0; pendingPitchDegrees = 0;
            minecraft.player.setYRot(incoming.yaw());
            minecraft.player.setXRot(incoming.pitch());
        }
        if (relocated && incoming.active()) {
            // A manual boundary changes the coordinate origin, not the live free-camera heading.
            pendingSpeedSteps = 0;
            targetBody = currentSystem().bodies().stream()
                    .min(Comparator.comparingDouble(body -> currentSystem().positionAt(body, timeSeconds()).distance(incoming.position())))
                    .map(CelestialBody::id).orElse(currentSystem().bodies().getFirst().id());
        }
        systems.keySet().retainAll(incoming.discoveredSystems());
        if (!pendingAtlasTarget.isEmpty()) {
            if (!active() || incoming.navigationEpoch() != pendingAtlasEpoch) {
                pendingAtlasTarget = "";
            } else if (incoming.discoveredSystems().contains(pendingAtlasTarget)) {
                String acknowledged = pendingAtlasTarget;
                pendingAtlasTarget = "";
                aimAtSystem(acknowledged);
            }
        }
    }

    /** Applies a complete private descriptor set on the client thread before its navigation snapshot. */
    public void receiveCustomSystems(CustomSystemsReceivedEvent event) {
        Map<String, CosmosSystem> replacement = new HashMap<>();
        for (CosmosSystem system : event.payload().systems()) {
            CosmosSystem previous = customSystems.get(system.id());
            if (previous != null && !previous.equals(system)) {
                throw new IllegalArgumentException("A saved custom descriptor changed within the connection: " + system.id());
            }
            replacement.put(system.id(), system);
        }
        if (snapshot != null) {
            for (String id : snapshot.discoveredSystems()) {
                if (CosmosIds.isCustom(id) && !replacement.containsKey(id)) {
                    throw new IllegalArgumentException("A known custom system was removed from synchronization: " + id);
                }
            }
        }
        customSystems = Map.copyOf(replacement);
    }

    public ExplorationPayload snapshot() { return snapshot; }
    public float smoothing() { return smoothing; }
    public float exposure() { return options.exposure(); }
    public float yaw() { return orientation().yaw(); }
    public float pitch() { return orientation().pitch(); }
    public float roll() { return orientation().roll(); }
    public FlightOrientation orientation() { return flightCamera.orientation(); }
    public FlightOrientation targetOrientation() { return flightCamera.target(); }
    public String targetBody() { return targetBody; }

    public boolean active() {
        return snapshot != null && snapshot.active() && minecraft.level != null
                && minecraft.level.dimension().equals(RocketService.FLIGHT);
    }

    public CosmosSystem currentSystem() {
        return snapshot == null ? CosmosGenerator.sol() : system(snapshot.systemId());
    }

    public CosmosSystem system(String id) {
        if (CosmosIds.isCustom(id)) {
            CosmosSystem system = customSystems.get(id);
            if (system == null) { throw new IllegalArgumentException("Custom system was not synchronized: " + id); }
            return system;
        }
        if (snapshot == null) { return CosmosGenerator.sol(); }
        return systems.computeIfAbsent(id, key -> CosmosGenerator.byId(snapshot.galaxySeed(), key));
    }

    /** Lists only discoveries in the last server snapshot, sorted relative to the current system. */
    public List<CosmosSystem> discoveredSystems() {
        if (snapshot == null) { return List.of(); }
        SpaceVector origin = currentSystem().galaxyPosition();
        return snapshot.discoveredSystems().stream().map(this::system)
                .sorted(Comparator.comparingDouble((CosmosSystem value) -> value.galaxyPosition().distance(origin))
                        .thenComparing(CosmosSystem::id)).toList();
    }

    /** Whether the server has recorded a visit and unlocked fast travel for this connection's pilot. */
    public boolean visited(String id) {
        return snapshot != null && snapshot.visitedSystems().contains(id);
    }

    /** Includes returning to the current origin after leaving its arrival envelope in manual flight. */
    public boolean canJumpTo(String id) {
        return active() && snapshot.jumpTicks() == 0 && visited(id)
                && (!snapshot.systemId().equals(id)
                    || snapshot.position().length() > GalacticNavigation.arrivalRadiusMeters(currentSystem()));
    }

    /** Observer in galactic light-years; local render calculations continue to use double meters. */
    public SpaceVector galaxyPosition() {
        return currentSystem().galaxyPosition().add(visualPosition().multiply(1 / CosmosGenerator.LIGHT_YEAR));
    }

    private SpaceVector galaxyCenterRelative() {
        SpaceVector center = galaxyAtlas.isEmpty() ? UniverseGenerator.MILKY_WAY_CENTER_LIGHT_YEARS
                : galaxyAtlas.get(targetGalaxyIndex).centerLightYears();
        return center.subtract(currentSystem().galaxyPosition())
                .multiply(CosmosGenerator.LIGHT_YEAR).subtract(visualPosition());
    }

    /** Aims toward a charted system for manual flight. It neither starts movement nor grants a visit. */
    public boolean aimAtSystem(String id) {
        if (!active() || snapshot.jumpTicks() > 0 || !snapshot.discoveredSystems().contains(id)) { return false; }
        SpaceVector relative = system(id).galaxyPosition().subtract(currentSystem().galaxyPosition())
                .multiply(CosmosGenerator.LIGHT_YEAR).subtract(visualPosition());
        if (!aimDirection(relative)) { return false; }
        pendingAtlasTarget = "";
        targetSystem = id;
        targetGalaxy = false;
        return true;
    }

    /**
     * Requests visibility of a public atlas anchor, then aims only after a matching server chart acknowledgement.
     * Client thread, active idle flight only. Refusal leaves navigation unchanged; requests expire after two seconds.
     */
    public boolean chartAtlasSystem(String id) {
        if (!UniverseGenerator.isAtlasSystemId(id) || !active() || snapshot.jumpTicks() != 0) { return false; }
        if (snapshot.discoveredSystems().contains(id)) { return aimAtSystem(id); }
        pendingAtlasTarget = id;
        pendingAtlasEpoch = snapshot.navigationEpoch();
        pendingAtlasTicks = 40;
        action(FlightActionPayload.Action.CHART_ATLAS, id);
        return true;
    }

    /** Aims without translation; host yaw/pitch are derived from a finite direction in local axes. */
    private boolean aimDirection(SpaceVector relative) {
        if (!active() || snapshot.jumpTicks() > 0 || relative.length() < 1) { return false; }
        SpaceVector direction = relative.normalized();
        finishingGuidance = false;
        flightCamera.aim(FlightOrientation.fromAngles(Math.toDegrees(Math.atan2(-direction.x(), direction.z())),
                -Math.toDegrees(Math.asin(Math.clamp(direction.y(), -1, 1))), 0));
        pendingYawDegrees = 0; pendingPitchDegrees = 0;
        return true;
    }

    /** Requests the selected inspection speed; only the owning server can accept and persist it. */
    public boolean setSpeed(double metersPerSecond) {
        FlightDynamics.validateSpeed(metersPerSecond);
        if (!active() || snapshot.jumpTicks() > 0) { return false; }
        pendingSpeedSteps = 0;
        PacketDistributor.sendToServer(new FlightSpeedPayload(metersPerSecond));
        return true;
    }

    /** Presentation clock interpolates the same snapshots as position and stops when no new state arrives. */
    public double timeSeconds() {
        if (snapshot == null) { return 0; }
        double factor = snapshotBlend();
        return previousClockSeconds * (1 - factor) + snapshot.clockTicks() / 20.0 * factor;
    }

    private double snapshotBlend() { return Math.clamp((System.nanoTime() - receivedAt) / 100_000_000.0, 0, 1); }

    /** Interpolates snapshots in local meters; it never accumulates an independent client trajectory. */
    public SpaceVector visualPosition() {
        if (snapshot == null) { return SpaceVector.ZERO; }
        double factor = snapshotBlend();
        return previousPosition.multiply(1 - factor).add(snapshot.position().multiply(factor));
    }

    public void action(FlightActionPayload.Action action, String target) {
        if (minecraft.getConnection() != null && minecraft.player != null) {
            if (action == FlightActionPayload.Action.LAND_BODY && "earth".equals(target) && earth.active()
                    && active() && "sol".equals(currentSystem().id())) {
                if (snapshot.jumpTicks() > 0 || automaticCamera()) { return; }
                var frame = SurfaceDefinition.byBody("earth").frame(currentSystem(), timeSeconds(), timeSeconds() * 20);
                var hit = EarthLandingTarget.aim(new ContinentalTerrain(earth.terrainVersion(), ContinentalTerrain.SEED),
                        frame.toBodyPoint(visualPosition()), frame.toBodyDirection(orientation().forward()));
                if (hit.isEmpty()) { minecraft.player.displayClientMessage(text("surface_aim"), true); return; }
                var point = hit.get();
                PacketDistributor.sendToServer(new EarthLandingPayload(point.chart().normal(point.localFeet().x(),
                        point.localFeet().z()), snapshot.navigationEpoch()));
            } else {
                PacketDistributor.sendToServer(new FlightActionPayload(action, target));
            }
        }
    }

    public void setTargetBody(String id) {
        if (currentSystem().bodies().stream().anyMatch(body -> body.id().equals(id))) {
            targetBody = id; targetSystem = ""; targetGalaxy = false; pendingAtlasTarget = "";
        }
    }

    public void cycleSmoothing() { smoothing = smoothing >= 0.89f ? 0 : Math.min(0.9f, smoothing + 0.15f); }
    public void cycleExposure() { options.setExposure(exposure() >= 1.75f ? 0.75f : exposure() + 0.25f); }

    /** Tick-paced input avoids frame-rate-dependent network traffic and simulation. */
    public void tick(ClientTickEvent.Post event) {
        if (!pendingAtlasTarget.isEmpty() && --pendingAtlasTicks <= 0) { pendingAtlasTarget = ""; }
        while (toggle.consumeClick()) {
            if (minecraft.screen == null) {
                action(surface.definition(minecraft.level) != null || minecraft.level != null
                        && earth.chart(minecraft.level.dimension().location().toString()).isPresent()
                        ? FlightActionPayload.Action.TAKE_OFF : FlightActionPayload.Action.TOGGLE, "");
            }
        }
        while (land.consumeClick()) {
            if (active() && minecraft.screen == null) { action(FlightActionPayload.Action.LAND_BODY, targetBody); }
        }
        while (map.consumeClick()) { if (minecraft.screen == null) { mapRequested = true; } }
        while (scan.consumeClick()) { if (minecraft.screen == null) { action(FlightActionPayload.Action.SCAN, ""); } }
        while (faster.consumeClick()) { if (active() && controlsAvailable()) { pendingSpeedSteps = Math.min(16, pendingSpeedSteps + 1); } }
        while (slower.consumeClick()) { if (active() && controlsAvailable()) { pendingSpeedSteps = Math.max(-16, pendingSpeedSteps - 1); } }
        if (mapRequested && minecraft.screen == null && minecraft.player != null) {
            mapRequested = false;
            minecraft.setScreen(new CosmosMapScreen(this));
        }
        if (atlasRequested && minecraft.screen == null && minecraft.player != null) {
            atlasRequested = false;
            minecraft.setScreen(new UniverseAtlasScreen(this));
        }
        if (active()) {
            if (!wasActive) {
                previousCamera = minecraft.options.getCameraType();
                previousCameraTime = System.nanoTime();
            }
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            boolean controls = controlsAvailable();
            boolean guided = automaticCamera();
            if (!controls || guided) {
                pendingSpeedSteps = 0;
                // Loading overlays may suppress camera callbacks while the host recenters its cursor.
                pendingYawDegrees = 0; pendingPitchDegrees = 0;
            }
            if (++speedActionTicks >= 4 && Math.abs(pendingSpeedSteps) >= 1) {
                boolean increase = pendingSpeedSteps > 0;
                action(increase ? FlightActionPayload.Action.SPEED_UP : FlightActionPayload.Action.SPEED_DOWN, "");
                pendingSpeedSteps += increase ? -1 : 1;
                speedActionTicks = 0;
            }
            boolean manual = controls && !guided;
            PacketDistributor.sendToServer(new FlightControlPayload(manual ? forward : 0, manual ? strafe : 0,
                    manual ? vertical : 0, guided ? snapshot.orientation() : orientation(),
                    guided ? controls && brake.isDown() : !controls || brake.isDown(),
                    ++sequence, snapshot.navigationEpoch()));
        } else if (wasActive) { restoreCamera(); }
        wasActive = active();
    }

    /** Captures movement intent before clearing vanilla walking/jumping in the bounded flight room. */
    public void movement(MovementInputUpdateEvent event) {
        if (!active()) { return; }
        var input = event.getInput();
        forward = input.forwardImpulse;
        strafe = input.leftImpulse;
        vertical = (input.jumping ? 1 : 0) - (input.shiftKeyDown ? 1 : 0);
        input.forwardImpulse = 0; input.leftImpulse = 0;
        input.up = false; input.down = false; input.left = false; input.right = false;
        input.jumping = false; input.shiftKeyDown = false;
    }

    private boolean controlsAvailable() {
        return minecraft.screen == null && minecraft.getOverlay() == null && minecraft.isWindowActive();
    }

    private boolean automaticCamera() {
        SurfacePayload context = surface.snapshot();
        boolean surfaceRoute = context != null && (context.phase() == SurfacePayload.Phase.PREPARING
                || context.phase() == SurfacePayload.Phase.DESCENDING || context.phase() == SurfacePayload.Phase.ASCENDING);
        return snapshot != null && (snapshot.approaching() || finishingGuidance || surfaceRoute);
    }

    /** Reads host-accepted movement before MouseHandler consumes it, preserving its cursor-recenter suppression. */
    public void mouseTurn(CalculatePlayerTurnEvent event) {
        if (!active() || automaticCamera() || !controlsAvailable() || !minecraft.mouseHandler.isMouseGrabbed()) {
            pendingYawDegrees = 0; pendingPitchDegrees = 0;
            return;
        }
        double sensitivity = Math.pow(event.getMouseSensitivity() * 0.6 + 0.2, 3) * 8 * 0.15;
        pendingYawDegrees += minecraft.mouseHandler.getXVelocity() * sensitivity;
        pendingPitchDegrees += minecraft.mouseHandler.getYVelocity() * sensitivity
                * (minecraft.options.invertYMouse().get() ? -1 : 1);
    }

    /** The virtual camera owns its unrestricted orientation; the walking player's pitch clamp is irrelevant. */
    public void camera(ViewportEvent.ComputeCameraAngles event) {
        if (!active() || minecraft.player == null) { return; }
        long now = System.nanoTime();
        double dt = Math.clamp((now - previousCameraTime) / 1_000_000_000.0, 0, 0.1);
        previousCameraTime = now;
        if (automaticCamera()) {
            FlightOrientation guidedPose = finishingGuidance ? snapshot.orientation()
                    : previousOrientation.interpolate(snapshot.orientation(), snapshotBlend());
            flightCamera.follow(guidedPose, dt);
            if (finishingGuidance && flightCamera.orientation().equals(snapshot.orientation())) {
                finishingGuidance = false;
            }
        } else {
            boolean captured = controlsAvailable() && minecraft.mouseHandler.isMouseGrabbed();
            double roll = captured ? ((rollLeft.isDown() ? 1 : 0) - (rollRight.isDown() ? 1 : 0)) * 75 * dt : 0;
            flightCamera.update(captured ? pendingYawDegrees : 0, captured ? pendingPitchDegrees : 0, roll, dt, smoothing);
        }
        pendingYawDegrees = 0; pendingPitchDegrees = 0;
        event.setYaw(yaw()); event.setPitch(pitch()); event.setRoll(roll());
    }

    /** Flight bindings own Q/E and L without dropping items, opening inventory or opening advancements. */
    public void keyInput(InputEvent.Key event) {
        if (!active() || !controlsAvailable()
                || (!rollLeft.matches(event.getKey(), event.getScanCode())
                    && !rollRight.matches(event.getKey(), event.getScanCode())
                    && !land.matches(event.getKey(), event.getScanCode()))) { return; }
        for (KeyMapping host : new KeyMapping[] {minecraft.options.keyDrop, minecraft.options.keyInventory,
                minecraft.options.keyAdvancements}) {
            if (host.matches(event.getKey(), event.getScanCode())) {
                host.setDown(false);
                while (host.consumeClick()) { /* The flight binding owns this action in the active flight context. */ }
            }
        }
    }

    public void scroll(InputEvent.MouseScrollingEvent event) {
        if (active() && minecraft.screen == null) {
            if (event.getScrollDeltaY() != 0) {
                pendingSpeedSteps = Math.clamp(pendingSpeedSteps + event.getScrollDeltaY(), -16, 16);
            }
            event.setCanceled(true);
        }
    }

    public void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (active()) { event.setCanceled(true); event.setSwingHand(false); }
    }

    public void highlight(RenderHighlightEvent.Block event) { if (active()) { event.setCanceled(true); } }

    public void hand(RenderHandEvent event) { if (active()) { event.setCanceled(true); } }

    /** Co-located pilots in the physical staging room are not objects in the virtual cosmos. */
    public void player(RenderPlayerEvent.Pre event) { if (active()) { event.setCanceled(true); } }
    public void living(RenderLivingEvent.Pre<?, ?> event) { if (active()) { event.setCanceled(true); } }

    /** Flight sky uses its own physical-scale scene and does not depend on the chunk far plane. */
    public void render(RenderLevelStageEvent event) {
        var ground = surface.definition(minecraft.level);
        if ((!active() && ground == null) || RenderCompatibility.shadowPass()) { return; }
        var stage = RenderCompatibility.lateWorldPasses()
                ? RenderLevelStageEvent.Stage.AFTER_LEVEL : RenderLevelStageEvent.Stage.AFTER_SKY;
        if (event.getStage() != stage) { return; }
        renderer.setContinentalEarth(ground == null ? earth.terrainVersion() : 0);
        renderer.setQuality(options.quality().ordinal());
        renderer.setGalaxySeed(snapshot == null ? 0 : snapshot.galaxySeed());
        renderer.setSolarVisual(solar.visual());
        if (ground != null) {
            SurfaceSkyRenderer.render(event, renderer, CosmosGenerator.sol(), ground, surface.clockTicks(), exposure());
            return;
        }
        Matrix4f view = new Matrix4f(event.getModelViewMatrix()).setTranslation(0, 0, 0);
        viewProjection = new Matrix4f(event.getProjectionMatrix()).mul(view);
        float warp = snapshot.interstellarJump() ? (float) Math.sin(Math.PI * (1 - snapshot.jumpTicks() / 80.0)) : 0;
        renderer.render(event, currentSystem(), visualPosition(), timeSeconds(), warp, exposure());
    }

    /** Replaces the walking HUD with navigation instruments while retaining the host's screen rendering. */
    public void hud(RenderGuiEvent.Pre event) {
        if (minecraft.screen instanceof CosmosMapScreen) { event.setCanceled(true); return; }
        if (!active()) { return; }
        event.setCanceled(true);
        if (minecraft.options.hideGui || minecraft.screen != null) { return; }
        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int cyan = 0xFF8BE8EA;
        Component speedText = text("speed", distance(snapshot.velocity().length()) + "/s",
                distance(snapshot.speedMetersPerSecond()) + "/s");
        int panelRight = Math.min(width - 10, Math.max(230, minecraft.font.width(speedText) + 25));
        graphics.fill(10, 10, panelRight, 49, 0xAA061118);
        graphics.drawString(minecraft.font, text("hud", currentSystem().name()), 17, 16, cyan);
        graphics.drawString(minecraft.font, speedText, 17, 31, 0xFFE1ECEF);
        graphics.fill(10, height - 49, width - 10, height - 10, 0x99061018);
        graphics.drawString(minecraft.font, text("controls"), 17, height - 42, 0xFFABC5D1);
        graphics.drawString(minecraft.font, text("attitude", String.format(Locale.ROOT, "%.1f", yaw()),
                String.format(Locale.ROOT, "%.1f", pitch()), String.format(Locale.ROOT, "%.1f", roll()),
                String.format(Locale.ROOT, "%.2f", smoothing)), 17, height - 27, cyan);
        int cx = width / 2, cy = height / 2;
        graphics.fill(cx - 13, cy, cx - 5, cy + 1, cyan); graphics.fill(cx + 5, cy, cx + 13, cy + 1, cyan);
        graphics.fill(cx, cy - 13, cx + 1, cy - 5, cyan); graphics.fill(cx, cy + 5, cx + 1, cy + 13, cyan);
        SurfacePayload context = surface.snapshot();
        if (context != null && context.phase() == SurfacePayload.Phase.PREPARING) {
            graphics.drawCenteredString(minecraft.font, text("surface_loading", brake.getTranslatedKeyMessage()),
                    cx, 65, 0xFFF6D4A5);
        } else if (context != null && (context.phase() == SurfacePayload.Phase.DESCENDING
                || context.phase() == SurfacePayload.Phase.ASCENDING)) {
            graphics.drawCenteredString(minecraft.font, text(context.phase() == SurfacePayload.Phase.DESCENDING
                    ? "surface_descending" : "surface_ascending",
                    String.format(Locale.ROOT, "%.1f", context.remainingTicks() / 20.0), brake.getTranslatedKeyMessage()),
                    cx, 65, 0xFFF6D4A5);
        } else if (snapshot.approaching()) {
            String name = currentSystem().bodies().stream().filter(body -> body.id().equals(snapshot.approachBodyId()))
                    .map(CelestialBody::name).findFirst().orElse(snapshot.approachBodyId());
            graphics.drawCenteredString(minecraft.font, text("approaching", name,
                    String.format(Locale.ROOT, "%.1f", snapshot.jumpTicks() / 20.0), brake.getTranslatedKeyMessage()),
                    cx, 65, 0xFFF6D4A5);
            targetMarker(graphics, width, height);
        } else if (snapshot.interstellarJump()) {
            graphics.drawCenteredString(minecraft.font, text("jumping", snapshot.jumpTarget()), cx, 65, 0xFFF6D4A5);
        } else { targetMarker(graphics, width, height); }
    }

    private void targetMarker(GuiGraphics graphics, int width, int height) {
        if (!targetSystem.isEmpty() && snapshot.discoveredSystems().contains(targetSystem)) {
            CosmosSystem target = system(targetSystem);
            SpaceVector relative = target.galaxyPosition().subtract(currentSystem().galaxyPosition())
                    .multiply(CosmosGenerator.LIGHT_YEAR).subtract(visualPosition());
            drawTargetMarker(graphics, width, height, relative, target.name());
            graphics.drawCenteredString(minecraft.font, text(visited(target.id()) ? "target_visited" : "target_unvisited"),
                    width / 2, height - 65, 0xFFEED4AA);
            return;
        }
        if (targetGalaxy) {
            String name = galaxyAtlas.isEmpty() ? text("galaxy_center").getString()
                    : galaxyAtlas.get(targetGalaxyIndex).name();
            drawTargetMarker(graphics, width, height, galaxyCenterRelative(), name);
            return;
        }
        CelestialBody body = currentSystem().bodies().stream().filter(value -> value.id().equals(targetBody)).findFirst().orElse(null);
        if (body == null) { return; }
        drawTargetMarker(graphics, width, height, currentSystem().positionAt(body, timeSeconds()).subtract(visualPosition()), body.name());
    }

    private void drawTargetMarker(GuiGraphics graphics, int width, int height, SpaceVector relative, String name) {
        if (viewProjection == null) { return; }
        double length = relative.length();
        if (length < 1) { return; }
        SpaceVector direction = relative.multiply(1 / length);
        Vector4f projected = viewProjection.transform(new Vector4f((float) direction.x(), (float) direction.y(), (float) direction.z(), 1));
        if (projected.w <= 0) { return; }
        int x = (int) ((projected.x / projected.w * 0.5 + 0.5) * width);
        int y = (int) ((-projected.y / projected.w * 0.5 + 0.5) * height);
        if (x < 35 || y < 55 || x > width - 35 || y > height - 58) { return; }
        int color = 0xAAEABF76;
        graphics.fill(x - 5, y - 5, x + 5, y - 4, color); graphics.fill(x - 5, y + 4, x + 5, y + 5, color);
        graphics.fill(x - 5, y - 5, x - 4, y + 5, color); graphics.fill(x + 4, y - 5, x + 5, y + 5, color);
        graphics.drawCenteredString(minecraft.font, Component.literal(name + " / " + distance(length)), x, y + 9, 0xFFEED4AA);
    }

    public void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("astra-flight")
                .executes(context -> { action(FlightActionPayload.Action.TOGGLE, ""); return 1; })
                .then(Commands.literal("takeoff").executes(context -> {
                    action(FlightActionPayload.Action.TAKE_OFF, ""); return 1;
                }))
                .then(Commands.literal("land").executes(context -> {
                    action(FlightActionPayload.Action.LAND_BODY, targetBody); return 1;
                }).then(Commands.argument("body", StringArgumentType.word())
                        .suggests((context, builder) -> builder.suggest("moon").suggest("earth").buildFuture())
                        .executes(context -> {
                            action(FlightActionPayload.Action.LAND_BODY, StringArgumentType.getString(context, "body"));
                            return 1;
                        })))
                .then(Commands.literal("map").executes(context -> { mapRequested = true; return 1; }))
                .then(Commands.literal("atlas").executes(context -> { atlasRequested = true; return 1; }))
                .then(Commands.literal("scan").executes(context -> { action(FlightActionPayload.Action.SCAN, ""); return 1; }))
                .then(Commands.literal("speed")
                        .then(Commands.literal("local").executes(context -> setSpeed(FlightDynamics.LOCAL_MAX_SPEED) ? 1 : 0))
                        .then(Commands.literal("interstellar").executes(context -> setSpeed(CosmosGenerator.LIGHT_YEAR) ? 1 : 0))
                        .then(Commands.literal("galactic").executes(context -> setSpeed(FlightDynamics.MAX_SPEED) ? 1 : 0))
                        .then(Commands.argument("metersPerSecond",
                                DoubleArgumentType.doubleArg(FlightDynamics.MIN_SPEED, FlightDynamics.MAX_SPEED))
                        .executes(context -> setSpeed(DoubleArgumentType.getDouble(context, "metersPerSecond")) ? 1 : 0)))
                .then(Commands.literal("galaxy").then(Commands.literal("aim").executes(context -> {
                    if (!active() || galaxyAtlas.isEmpty()) { return 0; }
                    SpaceVector observer = galaxyPosition();
                    targetGalaxyIndex = galaxyAtlas.stream().min(Comparator.comparingDouble(
                            galaxy -> galaxy.centerLightYears().distance(observer))).orElseThrow().index();
                    if (!aimDirection(galaxyCenterRelative())) { return 0; }
                    targetSystem = ""; targetGalaxy = true; pendingAtlasTarget = ""; return 1;
                })))
                .then(Commands.literal("smoothness").then(Commands.argument("value", FloatArgumentType.floatArg(0, 0.95f))
                        .executes(context -> { smoothing = FloatArgumentType.getFloat(context, "value"); return 1; })))
                .then(Commands.literal("exposure").then(Commands.argument("value", FloatArgumentType.floatArg(0.1f, 4))
                        .executes(context -> { options.setExposure(FloatArgumentType.getFloat(context, "value")); return 1; }))));
    }

    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        surface.clear();
        restoreCamera(); snapshot = null; systems.clear(); customSystems = Map.of(); previousPosition = SpaceVector.ZERO;
        targetSystem = ""; targetGalaxy = false; pendingAtlasTarget = ""; pendingAtlasTicks = 0; atlasRequested = false;
        galaxyAtlas = List.of(); targetGalaxyIndex = 0;
        forward = 0; strafe = 0; vertical = 0; sequence = 0; wasActive = false; mapRequested = false;
        flightCamera.reset(FlightOrientation.IDENTITY); pendingYawDegrees = 0; pendingPitchDegrees = 0; pendingSpeedSteps = 0;
        previousOrientation = FlightOrientation.IDENTITY; finishingGuidance = false;
        if (RenderSystem.isOnRenderThread()) { renderer.close(); }
        else { RenderSystem.recordRenderCall(renderer::close); }
        viewProjection = null;
    }

    private void restoreCamera() {
        pendingYawDegrees = 0; pendingPitchDegrees = 0;
        if (previousCamera != null) { minecraft.options.setCameraType(previousCamera); previousCamera = null; }
    }

    public static String distance(double meters) {
        double amount = Math.abs(meters);
        if (amount >= CosmosGenerator.LIGHT_YEAR * 0.01) { return String.format(Locale.ROOT, "%.2f ly", meters / CosmosGenerator.LIGHT_YEAR); }
        if (amount >= CosmosGenerator.AU * 0.01) { return String.format(Locale.ROOT, "%.3f AU", meters / CosmosGenerator.AU); }
        if (amount >= 1_000_000) { return String.format(Locale.ROOT, "%.2f Mm", meters / 1_000_000); }
        if (amount >= 1000) { return String.format(Locale.ROOT, "%.1f km", meters / 1000); }
        return String.format(Locale.ROOT, "%.0f m", meters);
    }

    static Component text(String key, Object... arguments) { return Component.translatable("astraengine.flight." + key, arguments); }
}
