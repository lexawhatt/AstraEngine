package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.cosmos.BodyApproach;
import dev.lexawhatt.astraengine.cosmos.CosmosIds;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client private discovery and virtual navigation snapshot; positions are local meters.
 * Navigation epoch changes on entry, arrival and approach ownership changes. Local approach
 * snapshots interpolate continuously; an interstellar arrival relocates presentation.
 */
public record ExplorationPayload(long galaxySeed, long clockTicks, String systemId, SpaceVector position,
        SpaceVector velocity, boolean active, double speedMetersPerSecond, FlightOrientation orientation, int jumpTicks,
        String jumpTarget, List<String> discoveredSystems, List<String> visitedSystems, long revision,
        long navigationEpoch, double orbitalSeconds, FlightOrientation earthOrientation, long calendarEpoch)
        implements CustomPacketPayload {
    public static final Type<ExplorationPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            AstraEngine.MOD_ID, "exploration"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ExplorationPayload> CODEC = StreamCodec.ofMember(
            ExplorationPayload::write, ExplorationPayload::read);
    /** Bounded remaining simulation ticks; a long local route may exceed the 80-tick interstellar transit. */
    public static final int MAX_TRANSITION_TICKS = BodyApproach.MAX_TICKS;

    public ExplorationPayload {
        if (!Double.isFinite(orbitalSeconds) || calendarEpoch < 0
                || earthOrientation != null && !"sol".equals(systemId)) {
            throw new IllegalArgumentException("Invalid navigation ephemeris snapshot");
        }
        new FlightDynamics.State(position, velocity);
        FlightDynamics.validateSpeed(speedMetersPerSecond);
        new FlightDynamics.Input(0, 0, 0, orientation, false);
        if (clockTicks < 0 || clockTicks > 1_000_000_000_000L || revision < 0 || navigationEpoch < 0 || !validId(systemId)
                || jumpTicks < 0 || jumpTicks > MAX_TRANSITION_TICKS || jumpTarget == null || jumpTarget.length() > 129
                || !jumpTarget.matches("[a-z0-9_.:/-]*") || discoveredSystems == null
                || discoveredSystems.isEmpty() || discoveredSystems.size() > 256
                || new HashSet<>(discoveredSystems).size() != discoveredSystems.size()
                || !discoveredSystems.contains(systemId) || discoveredSystems.stream().anyMatch(id -> !validId(id))
                || visitedSystems == null || visitedSystems.isEmpty() || visitedSystems.size() > 256
                || new HashSet<>(visitedSystems).size() != visitedSystems.size()
                || !visitedSystems.contains(systemId) || !discoveredSystems.containsAll(visitedSystems)) {
            throw new IllegalArgumentException("Invalid exploration snapshot");
        }
        if (!validTransition(active, systemId, jumpTicks, jumpTarget)) {
            throw new IllegalArgumentException("Invalid exploration transition state");
        }
        if (jumpTicks > 0 && jumpTarget.indexOf('/') < 0 && !discoveredSystems.contains(jumpTarget)) {
            throw new IllegalArgumentException("An accepted jump requires a charted destination");
        }
        discoveredSystems = List.copyOf(discoveredSystems);
        visitedSystems = List.copyOf(visitedSystems);
    }
    /**
     * Existing occupied-clock adapter. A null Earth orientation explicitly denotes the legacy orbital policy;
     * calendar-bound Sol snapshots instead carry their matching epoch seconds and full Earth rotation.
     */
    public ExplorationPayload(long galaxySeed, long clockTicks, String systemId, SpaceVector position,
            SpaceVector velocity, boolean active, double speedMetersPerSecond, FlightOrientation orientation,
            int jumpTicks, String jumpTarget, List<String> discoveredSystems, List<String> visitedSystems,
            long revision, long navigationEpoch) {
        this(galaxySeed, clockTicks, systemId, position, velocity, active, speedMetersPerSecond, orientation,
                jumpTicks, jumpTarget, discoveredSystems, visitedSystems, revision, navigationEpoch,
                clockTicks / 20.0, null, 0);
    }

    /** Whether this snapshot binds Sol orbit/frame sampling to the server's Earth calendar. */
    public boolean calendarEarth() { return earthOrientation != null; }

    /** Compatibility adapter for pre-visit callers; only charted Sol/current are inferred as visited. */
    public ExplorationPayload(long galaxySeed, long clockTicks, String systemId, SpaceVector position,
            SpaceVector velocity, boolean active, double speedMetersPerSecond, FlightOrientation orientation,
            int jumpTicks, String jumpTarget, List<String> discoveredSystems, long revision, long navigationEpoch) {
        this(galaxySeed, clockTicks, systemId, position, velocity, active, speedMetersPerSecond, orientation,
                jumpTicks, jumpTarget, discoveredSystems, legacyVisited(systemId, discoveredSystems), revision,
                navigationEpoch);
    }

    /** Compatibility adapter for former gear/yaw/pitch callers; network semantics use version nine. */
    public ExplorationPayload(long galaxySeed, long clockTicks, String systemId, SpaceVector position,
            SpaceVector velocity, boolean active, int speedIndex, float yaw, float pitch, int jumpTicks,
            String jumpTarget, List<String> discoveredSystems, long revision, long navigationEpoch) {
        this(galaxySeed, clockTicks, systemId, position, velocity, active, FlightDynamics.speed(speedIndex),
                FlightDynamics.legacyOrientation(yaw, pitch), jumpTicks, jumpTarget, discoveredSystems, revision, navigationEpoch);
    }
    public float yaw() { return orientation.yaw(); }
    public float pitch() { return orientation.pitch(); }
    /** Approximate former gear for compatibility only; use speedMetersPerSecond for navigation. */
    public int speedIndex() { return FlightDynamics.legacySpeedIndex(speedMetersPerSecond); }

    /** True only during a continuous local body approach, never an interstellar jump. */
    public boolean approaching() { return active && jumpTicks > 0 && jumpTarget.indexOf('/') >= 0; }

    /** True only during the existing bounded interstellar transit. */
    public boolean interstellarJump() { return active && jumpTicks > 0 && !approaching(); }

    /** Current approach body ID, or an empty string when no body approach owns navigation. */
    public String approachBodyId() { return approaching() ? jumpTarget.substring(jumpTarget.indexOf('/') + 1) : ""; }

    private static boolean validTransition(boolean active, String system, int ticks, String target) {
        if (ticks == 0) { return target.isEmpty(); }
        if (!active || target.isEmpty()) { return false; }
        int separator = target.indexOf('/');
        if (separator < 0) { return ticks <= BodyApproach.MAX_TICKS && validId(target); }
        return target.substring(0, separator).equals(system)
                && target.substring(separator + 1).matches("[a-z0-9_-]{1,64}");
    }

    private static boolean validId(String id) {
        return CosmosIds.isKnownId(id);
    }

    private static List<String> legacyVisited(String current, List<String> charted) {
        if (charted == null) {
            throw new IllegalArgumentException("A legacy exploration snapshot requires a chart");
        }
        return charted.stream().filter(id -> "sol".equals(id) || id != null && id.equals(current)).toList();
    }
    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(galaxySeed); buffer.writeLong(clockTicks); buffer.writeUtf(systemId, 64);
        writeVector(buffer, position); writeVector(buffer, velocity);
        buffer.writeBoolean(active); buffer.writeDouble(speedMetersPerSecond);
        buffer.writeDouble(orientation.x()); buffer.writeDouble(orientation.y());
        buffer.writeDouble(orientation.z()); buffer.writeDouble(orientation.w());
        buffer.writeVarInt(jumpTicks); buffer.writeUtf(jumpTarget, 129); buffer.writeVarInt(discoveredSystems.size());
        for (String id : discoveredSystems) { buffer.writeUtf(id, 64); }
        buffer.writeVarInt(visitedSystems.size());
        for (String id : visitedSystems) { buffer.writeUtf(id, 64); }
        buffer.writeLong(revision); buffer.writeLong(navigationEpoch);
        buffer.writeDouble(orbitalSeconds); buffer.writeBoolean(earthOrientation != null);
        if (earthOrientation != null) {
            buffer.writeDouble(earthOrientation.x()); buffer.writeDouble(earthOrientation.y());
            buffer.writeDouble(earthOrientation.z()); buffer.writeDouble(earthOrientation.w());
        }
        buffer.writeLong(calendarEpoch);
    }
    private static ExplorationPayload read(RegistryFriendlyByteBuf buffer) {
        long seed = buffer.readLong(), clock = buffer.readLong();
        String system = buffer.readUtf(64);
        SpaceVector position = readVector(buffer), velocity = readVector(buffer);
        boolean active = buffer.readBoolean();
        double speed = buffer.readDouble();
        FlightOrientation orientation = new FlightOrientation(buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble());
        int jumpTicks = buffer.readVarInt();
        String target = buffer.readUtf(129);
        int count = buffer.readVarInt();
        if (count < 1 || count > 256) { throw new IllegalArgumentException("Discovery packet count exceeds bounds"); }
        List<String> discoveries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) { discoveries.add(buffer.readUtf(64)); }
        int visitCount = buffer.readVarInt();
        if (visitCount < 1 || visitCount > 256) {
            throw new IllegalArgumentException("Visited system packet count exceeds bounds");
        }
        List<String> visited = new ArrayList<>(visitCount);
        for (int index = 0; index < visitCount; index++) { visited.add(buffer.readUtf(64)); }
        long revision = buffer.readLong(), navigationEpoch = buffer.readLong();
        double orbitalSeconds = buffer.readDouble();
        FlightOrientation earth = buffer.readBoolean() ? new FlightOrientation(buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble()) : null;
        return new ExplorationPayload(seed, clock, system, position, velocity, active, speed, orientation,
                jumpTicks, target, discoveries, visited, revision, navigationEpoch, orbitalSeconds, earth, buffer.readLong());
    }
    private static void writeVector(RegistryFriendlyByteBuf buffer, SpaceVector vector) {
        buffer.writeDouble(vector.x()); buffer.writeDouble(vector.y()); buffer.writeDouble(vector.z());
    }
    private static SpaceVector readVector(RegistryFriendlyByteBuf buffer) {
        return new SpaceVector(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
    @Override
    public Type<ExplorationPayload> type() { return TYPE; }
}
