package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.api.AstraCosmos.CreateResult;
import dev.lexawhatt.astraengine.api.AstraCosmos.DiscoverResult;
import dev.lexawhatt.astraengine.api.celestial.CelestialSystems;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosIds;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.SatelliteGenerator;
import dev.lexawhatt.astraengine.cosmos.UniverseGenerator;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Overworld-owned versioned exploration data. All reads and mutations require the owning server thread. */
public final class ExplorationCatalog extends SavedData {
    public static final int MAX_CUSTOM_SYSTEMS = CelestialSystems.MAX_CUSTOM_SYSTEMS;
    private static final String FILE_NAME = "astraengine_exploration";
    private final Map<UUID, Pilot> pilots = new LinkedHashMap<>();
    private final Map<String, CosmosSystem> customSystems = new LinkedHashMap<>();
    private final long galaxySeed;
    private long clockTicks;
    private boolean landingInitialized;

    private ExplorationCatalog(long galaxySeed) { this.galaxySeed = galaxySeed; }

    /** Loads saved discovery and refuses to regenerate over unreadable existing data. */
    public static ExplorationCatalog get(MinecraftServer server) {
        if (server == null) { throw new IllegalArgumentException("Exploration requires a Minecraft server"); }
        if (!server.isSameThread()) { throw new IllegalStateException("Exploration requires the server thread"); }
        Factory<ExplorationCatalog> factory = new Factory<>(() -> {
            if (Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data/" + FILE_NAME + ".dat"))) {
                throw new IllegalStateException("Existing exploration data could not be read; preserve and restore a backup");
            }
            ExplorationCatalog catalog = new ExplorationCatalog(server.overworld().getSeed() ^ 0x41535452414CL);
            catalog.setDirty();
            return catalog;
        }, (tag, registries) -> decode(tag), null);
        return server.overworld().getDataStorage().computeIfAbsent(factory, FILE_NAME);
    }

    /** Stable seed for deterministic generation; not a Minecraft world-coordinate origin. */
    public long galaxySeed() { return galaxySeed; }
    /** Occupied exploration ticks only; no wall-clock catch-up. */
    public long clockTicks() { return clockTicks; }

    /** Resolves a canonical system ID; absent custom IDs and malformed inputs throw without mutation. */
    public CosmosSystem system(String id) {
        return findSystem(id).orElseThrow(() -> new IllegalArgumentException("Unknown cosmos system: " + id));
    }

    /** Resolves saved custom content or this catalog's built-in generation; valid absent custom IDs return empty. */
    public Optional<CosmosSystem> findSystem(String id) {
        CosmosIds.requireId(id);
        if (CosmosIds.isCustom(id)) {
            return Optional.ofNullable(customSystems.get(id));
        }
        return UniverseGenerator.find(galaxySeed, id);
    }

    /** Immutable descriptor list in creation/save order; no caller can alter the owning saved catalog. */
    public List<CosmosSystem> customSystems() {
        return List.copyOf(customSystems.values());
    }

    /** Creates immutable custom content on the owning server thread; replay/conflict never replace saved data. */
    public CreateResult createSystem(CosmosSystem descriptor) {
        CelestialSystems.validateCustom(descriptor);
        CosmosSystem existing = customSystems.get(descriptor.id());
        if (existing != null) {
            return existing.equals(descriptor) ? CreateResult.ALREADY_EXISTS : CreateResult.CONFLICT;
        }
        if (customSystems.size() >= MAX_CUSTOM_SYSTEMS
                || CosmosDescriptorCodec.snapshotBytes(customSystems.values()) + CosmosDescriptorCodec.encodedBytes(descriptor)
                        > CosmosDescriptorCodec.MAX_SNAPSHOT_BYTES) {
            return CreateResult.LIMIT_REACHED;
        }
        customSystems.put(descriptor.id(), descriptor);
        setDirty();
        return CreateResult.CREATED;
    }

    /** Charts a private destination without visiting it or unlocking fast travel; does not post API events. */
    public DiscoverResult discover(UUID id, String systemId) {
        if (id == null) {
            throw new IllegalArgumentException("A player UUID is required for cosmos discovery");
        }
        if (findSystem(systemId).isEmpty()) {
            return DiscoverResult.UNKNOWN_SYSTEM;
        }
        Pilot pilot = pilots.get(id);
        if (pilot == null) {
            // Every new pilot already knows Sol; this no-op needs neither a record nor a capacity slot.
            if (systemId.equals("sol")) {
                return DiscoverResult.ALREADY_KNOWN;
            }
            if (pilots.size() >= 4096) {
                return DiscoverResult.LIMIT_REACHED;
            }
            pilot = player(id);
        }
        if (pilot.discovered.contains(systemId)) {
            return DiscoverResult.ALREADY_KNOWN;
        }
        if (pilot.discovered.size() >= 256) {
            return DiscoverResult.LIMIT_REACHED;
        }
        pilot.discover(systemId);
        setDirty();
        return DiscoverResult.DISCOVERED;
    }

    /** Advances the common visual ephemeris only while at least one pilot occupies flight mode. */
    public void tick(boolean occupied) {
        if (occupied && clockTicks < 1_000_000_000_000L) { clockTicks++; setDirty(); }
    }
    /** Returns or creates the player's bounded private discovery record. */
    public Pilot player(UUID id) {
        if (id == null) { throw new IllegalArgumentException("Exploration requires a player UUID"); }
        Pilot existing = pilots.get(id);
        if (existing != null) { return existing; }
        if (pilots.size() >= 4096) { throw new IllegalStateException("Exploration player capacity reached"); }
        CosmosSystem sol = CosmosGenerator.sol();
        CelestialBody earth = sol.bodies().stream().filter(body -> body.id().equals("earth"))
                .findFirst().orElseThrow();
        FlightDynamics.Observation arrival = arrival(sol, earth, clockTicks / 20.0);
        Pilot created = new Pilot("sol", arrival.position(), new SpaceVector(0, 0, 0), FlightDynamics.speed(0),
                arrival.orientation(), List.of("sol"), List.of("sol"), 0);
        revealNeighbors(created);
        pilots.put(id, created); setDirty();
        return created;
    }

    /** Charts nearby built-ins around the current visited system, stopping without eviction at the private limit. */
    int revealNeighbors(Pilot pilot) {
        if (pilot == null || !pilot.visited.contains(pilot.systemId)) {
            throw new IllegalArgumentException("Neighbor revelation requires a visited current system");
        }
        int added = 0;
        CosmosSystem current = system(pilot.systemId);
        for (CosmosSystem neighbor : UniverseGenerator.nearby(galaxySeed, current, 1)) {
            if (pilot.discover(neighbor.id())) {
                added++;
            }
        }
        if (added > 0) {
            setDirty();
        }
        return added;
    }
    /** Legacy origin-relative observation; parented bodies require the complete-system overload. */
    public static SpaceVector arrival(CelestialBody body, double seconds) {
        if (body == null || !body.parentId().isEmpty()) {
            throw new IllegalArgumentException("A parented body observation requires its complete system");
        }
        return body.positionAt(seconds).add(new SpaceVector(0, 0, -Math.max(body.radiusMeters() * 4, 100_000)));
    }
    /** Sunlit, body-aware framing with view angles carried in the authoritative navigation snapshot. */
    public static FlightDynamics.Observation arrival(CosmosSystem system, CelestialBody body, double seconds) {
        return FlightDynamics.observation(system, body, seconds);
    }
    boolean landingInitialized() { return landingInitialized; }
    void markLandingInitialized() { landingInitialized = true; setDirty(); }

    /** A server-owned private record; returned discovery lists are immutable snapshots. */
    public static final class Pilot {
        private String systemId;
        private SpaceVector position;
        private SpaceVector velocity;
        private double speedMetersPerSecond;
        private FlightOrientation orientation;
        private long revision;
        private final Set<String> discovered = new LinkedHashSet<>();
        private final Set<String> visited = new LinkedHashSet<>();

        private Pilot(String systemId, SpaceVector position, SpaceVector velocity, double speedMetersPerSecond,
                FlightOrientation orientation, List<String> discovered, List<String> visited, long revision) {
            new FlightDynamics.State(position, velocity);
            FlightDynamics.validateSpeed(speedMetersPerSecond);
            new FlightDynamics.Input(0, 0, 0, orientation, false);
            if (revision < 0 || discovered.isEmpty() || discovered.size() > 256
                    || Set.copyOf(discovered).size() != discovered.size() || !discovered.contains(systemId)
                    || visited.isEmpty() || visited.size() > 256 || Set.copyOf(visited).size() != visited.size()
                    || !visited.contains(systemId) || !discovered.containsAll(visited)) {
                throw new IllegalArgumentException("Invalid saved exploration pilot");
            }
            this.systemId = systemId; this.position = position; this.velocity = velocity;
            this.speedMetersPerSecond = speedMetersPerSecond; this.orientation = orientation;
            this.discovered.addAll(discovered); this.visited.addAll(visited); this.revision = revision;
        }
        public String systemId() { return systemId; }
        public SpaceVector position() { return position; }
        public SpaceVector velocity() { return velocity; }
        /** Continuous travel speed in meters per second. */
        public double speedMetersPerSecond() { return speedMetersPerSecond; }
        /** Canonical system-local view orientation, including roll. */
        public FlightOrientation orientation() { return orientation; }
        /** Nearest former gear; retained only for older diagnostics. */
        public int speedIndex() { return FlightDynamics.legacySpeedIndex(speedMetersPerSecond); }
        public float yaw() { return orientation.yaw(); }
        public float pitch() { return orientation.pitch(); }
        public long revision() { return revision; }
        /** Charted destinations, including unvisited systems which cannot yet be fast-travel targets. */
        public List<String> discoveredSystems() { return List.copyOf(discovered); }
        int discoveredCount() { return discovered.size(); }
        /** Systems reached by manual arrival, plus conservative Sol/current migration anchors. */
        public List<String> visitedSystems() { return List.copyOf(visited); }
        void navigate(FlightDynamics.State state, FlightOrientation orientation) {
            if (state == null || orientation == null) {
                throw new IllegalArgumentException("Navigation requires a state and orientation");
            }
            this.position = state.position(); this.velocity = state.velocity(); this.orientation = orientation;
            revision++;
        }
        void speed(double metersPerSecond) {
            speedMetersPerSecond = FlightDynamics.validateSpeed(metersPerSecond); revision++;
        }
        void arrive(String id, FlightDynamics.Observation observation) {
            if (!visited.contains(id)) { throw new IllegalArgumentException("Fast-travel destination is not visited"); }
            systemId = id; position = observation.position(); velocity = new SpaceVector(0, 0, 0);
            orientation = observation.orientation(); revision++;
        }
        void visit(String id, SpaceVector entryPosition, FlightOrientation entryOrientation) {
            if (!discovered.contains(id)) {
                throw new IllegalArgumentException("Manual arrival requires a charted destination");
            }
            FlightDynamics.State arrived = new FlightDynamics.State(entryPosition, SpaceVector.ZERO);
            if (entryOrientation == null || entryPosition.length() > FlightDynamics.LOCAL_RADIUS * 1.000001) {
                throw new IllegalArgumentException("Manual arrival requires a local boundary position and heading");
            }
            systemId = id;
            position = arrived.position();
            velocity = arrived.velocity();
            orientation = entryOrientation;
            speedMetersPerSecond = Math.min(speedMetersPerSecond, FlightDynamics.LOCAL_MAX_SPEED);
            visited.add(id);
            revision++;
        }
        boolean discover(String id) {
            if (discovered.size() >= 256 || !discovered.add(id)) { return false; }
            revision++; return true;
        }
    }

    /** Strict version-six codec; atlas and additive satellite versions are independent of legacy parent generation. */
    public static ExplorationCatalog decode(CompoundTag root) {
        if (root == null) { throw new IllegalArgumentException("An exploration save compound is required"); }
        require(root, Tag.TAG_INT, "version", "generator_version");
        require(root, Tag.TAG_LONG, "seed", "clock_ticks");
        require(root, Tag.TAG_LIST, "players");
        require(root, Tag.TAG_BYTE, "landing");
        int version = root.getInt("version");
        if ((version < 1 || version > 6) || root.getInt("generator_version") != CosmosGenerator.VERSION
                || root.getLong("clock_ticks") < 0 || root.getLong("clock_ticks") > 1_000_000_000_000L) {
            throw new IllegalArgumentException("Unsupported or invalid exploration format");
        }
        if (version >= 5) {
            require(root, Tag.TAG_INT, "universe_version");
            if (root.getInt("universe_version") != UniverseGenerator.VERSION) {
                throw new IllegalArgumentException("Unsupported saved universe atlas version");
            }
        }
        if (version >= 6) {
            require(root, Tag.TAG_INT, "satellite_version");
            if (root.getInt("satellite_version") != SatelliteGenerator.VERSION) {
                throw new IllegalArgumentException("Unsupported saved satellite version; load with the matching engine version");
            }
        }
        ExplorationCatalog catalog = new ExplorationCatalog(root.getLong("seed"));
        catalog.clockTicks = root.getLong("clock_ticks"); catalog.landingInitialized = root.getBoolean("landing");
        if (version >= 3) {
            require(root, Tag.TAG_LIST, "custom_systems");
            ListTag descriptors = root.getList("custom_systems", Tag.TAG_COMPOUND);
            if (descriptors.size() > MAX_CUSTOM_SYSTEMS
                    || descriptors.size() != ((ListTag) root.get("custom_systems")).size()) {
                throw new IllegalArgumentException("Invalid saved custom cosmos system list");
            }
            for (int index = 0; index < descriptors.size(); index++) {
                CosmosSystem descriptor = CosmosDescriptorCodec.decode(descriptors.getCompound(index));
                if (descriptors.getCompound(index).getInt("version") != CosmosDescriptorCodec.VERSION) {
                    catalog.setDirty();
                }
                if (catalog.customSystems.putIfAbsent(descriptor.id(), descriptor) != null) {
                    throw new IllegalArgumentException("Duplicate saved custom cosmos system: " + descriptor.id());
                }
            }
            if (CosmosDescriptorCodec.snapshotBytes(catalog.customSystems.values()) > CosmosDescriptorCodec.MAX_SNAPSHOT_BYTES) {
                throw new IllegalArgumentException("Saved custom catalog exceeds its synchronization byte budget");
            }
        } else if (root.contains("custom_systems")) {
            require(root, Tag.TAG_LIST, "custom_systems");
            if (!((ListTag) root.get("custom_systems")).isEmpty()) {
                throw new IllegalArgumentException("Custom cosmos systems require exploration format version three");
            }
        }
        ListTag entries = root.getList("players", Tag.TAG_COMPOUND);
        if (entries.size() > 4096 || entries.size() != ((ListTag) root.get("players")).size()) {
            throw new IllegalArgumentException("Invalid saved exploration player list");
        }
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag tag = entries.getCompound(i);
            require(tag, Tag.TAG_STRING, "system"); require(tag, Tag.TAG_DOUBLE, "x", "y", "z", "vx", "vy", "vz");
            double speed;
            FlightOrientation orientation;
            if (version == 1) {
                require(tag, Tag.TAG_FLOAT, "yaw", "pitch"); require(tag, Tag.TAG_INT, "speed");
                speed = FlightDynamics.speed(tag.getInt("speed"));
                orientation = FlightDynamics.legacyOrientation(tag.getFloat("yaw"), tag.getFloat("pitch"));
            } else {
                require(tag, Tag.TAG_DOUBLE, "speed_mps", "qx", "qy", "qz", "qw");
                speed = FlightDynamics.validateSpeed(tag.getDouble("speed_mps"));
                orientation = new FlightOrientation(tag.getDouble("qx"), tag.getDouble("qy"),
                        tag.getDouble("qz"), tag.getDouble("qw"));
            }
            require(tag, Tag.TAG_LONG, "revision"); require(tag, Tag.TAG_LIST, "discovered");
            if (!tag.hasUUID("uuid")) { throw new IllegalArgumentException("Missing or invalid saved pilot UUID"); }
            UUID id = tag.getUUID("uuid");
            ListTag discoveries = tag.getList("discovered", Tag.TAG_STRING);
            if (discoveries.isEmpty() || discoveries.size() > 256
                    || discoveries.size() != ((ListTag) tag.get("discovered")).size()) {
                throw new IllegalArgumentException("Invalid saved discovery list");
            }
            List<String> names = discoveries.stream().map(Tag::getAsString).toList();
            if (version < 5 && names.stream().anyMatch(UniverseGenerator::isUniverseId)) {
                throw new IllegalArgumentException("Atlas identities require exploration format version five");
            }
            for (String name : names) { catalog.system(name); }
            catalog.system(tag.getString("system"));
            SpaceVector position = new SpaceVector(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
            SpaceVector velocity = new SpaceVector(tag.getDouble("vx"), tag.getDouble("vy"), tag.getDouble("vz"));
            if (version < 4 && (speed > FlightDynamics.LOCAL_MAX_SPEED
                    || position.length() > FlightDynamics.LOCAL_RADIUS * 1.000001
                    || velocity.length() > FlightDynamics.LOCAL_MAX_SPEED * 1.000001)) {
                throw new IllegalArgumentException("Legacy exploration state exceeds its local navigation bounds");
            }
            List<String> visited;
            if (version >= 4) {
                require(tag, Tag.TAG_LIST, "visited");
                ListTag visits = tag.getList("visited", Tag.TAG_STRING);
                if (visits.isEmpty() || visits.size() > 256
                        || visits.size() != ((ListTag) tag.get("visited")).size()) {
                    throw new IllegalArgumentException("Invalid saved visited system list");
                }
                visited = visits.stream().map(Tag::getAsString).toList();
            } else {
                visited = names.stream().filter(name -> name.equals("sol") || name.equals(tag.getString("system")))
                        .toList();
            }
            Pilot pilot = new Pilot(tag.getString("system"), position, velocity, speed, orientation, names, visited,
                    tag.getLong("revision"));
            if (catalog.pilots.putIfAbsent(id, pilot) != null) { throw new IllegalArgumentException("Duplicate saved pilot"); }
        }
        if (version < 6) { catalog.setDirty(); }
        return catalog;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putInt("version", 6); root.putInt("generator_version", CosmosGenerator.VERSION);
        root.putInt("universe_version", UniverseGenerator.VERSION);
        root.putInt("satellite_version", SatelliteGenerator.VERSION);
        root.putLong("seed", galaxySeed); root.putLong("clock_ticks", clockTicks); root.putBoolean("landing", landingInitialized);
        ListTag descriptors = new ListTag();
        for (CosmosSystem descriptor : customSystems.values()) {
            descriptors.add(CosmosDescriptorCodec.encode(descriptor));
        }
        root.put("custom_systems", descriptors);
        ListTag players = new ListTag();
        pilots.forEach((id, pilot) -> {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("uuid", id); tag.putString("system", pilot.systemId);
            tag.putDouble("x", pilot.position.x()); tag.putDouble("y", pilot.position.y()); tag.putDouble("z", pilot.position.z());
            tag.putDouble("vx", pilot.velocity.x()); tag.putDouble("vy", pilot.velocity.y()); tag.putDouble("vz", pilot.velocity.z());
            tag.putDouble("speed_mps", pilot.speedMetersPerSecond);
            tag.putDouble("qx", pilot.orientation.x()); tag.putDouble("qy", pilot.orientation.y());
            tag.putDouble("qz", pilot.orientation.z()); tag.putDouble("qw", pilot.orientation.w());
            tag.putLong("revision", pilot.revision);
            ListTag discoveries = new ListTag();
            pilot.discovered.forEach(name -> discoveries.add(StringTag.valueOf(name)));
            tag.put("discovered", discoveries);
            ListTag visits = new ListTag();
            pilot.visited.forEach(name -> visits.add(StringTag.valueOf(name)));
            tag.put("visited", visits);
            players.add(tag);
        });
        root.put("players", players); return root;
    }
    private static void require(CompoundTag tag, int type, String... keys) {
        for (String key : keys) {
            if (!tag.contains(key, type)) { throw new IllegalArgumentException("Missing saved exploration field: " + key); }
        }
    }
}
