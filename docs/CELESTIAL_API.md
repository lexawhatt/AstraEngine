# Creating planets and stellar systems

The provisional Java API targets Minecraft 1.21.1 / NeoForge 21.1.252. A consumer
builds immutable celestial descriptors, creates a system in the server's saved
catalog, and grants discovery to selected players. Discovered custom systems use
the existing map, interstellar transit, smooth body approach, and celestial shaders.

## Example

Build a descriptor on any thread with a caller-owned builder. Call `create` on
the owning server thread after worlds are available, for example from a
`ServerStartedEvent` handler on `NeoForge.EVENT_BUS`. Grant discovery from your
server-side gameplay code after checking the player's permissions.

```java
import dev.lexawhatt.astraengine.api.AstraCosmos;
import dev.lexawhatt.astraengine.api.celestial.CelestialBodies;
import dev.lexawhatt.astraengine.api.celestial.CelestialSystems;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class ObservatoryContent {
    private ObservatoryContent() {
    }

    public static CosmosSystem aurora() {
        return CelestialSystems.builder("observatory:aurora", "Aurora")
                .seed(42L)
                .galaxyPositionLightYears(2.0, 0.5, -1.0)
                .body(CelestialBodies.star("primary", "Aurora A", 695_700_000)
                        .color(1.0, 0.82, 0.58)
                        .build())
                .body(CelestialBodies.planet("nereid", "Nereid",
                                CelestialBody.Kind.GAS_GIANT, 58_232_000)
                        .orbit(149_597_870_700.0, 365.25 * 86_400)
                        .phaseRadians(0.4)
                        .inclinationRadians(0.05)
                        .eccentricity(0.02)
                        .color(0.75, 0.55, 0.35)
                        .atmosphere(0.6f)
                        .rings(1.4f, 2.5f)
                        .axialTiltRadians(0.45)
                        .build())
                .body(CelestialBodies.planet("nereid_moon", "Nereid Moon",
                                CelestialBody.Kind.ICE, 1_500_000)
                        .parent("nereid")
                        .orbit(450_000_000, 5 * 86_400)
                        .color(0.72, 0.74, 0.76)
                        .build())
                .build();
    }

    public static AstraCosmos.CreateResult create(MinecraftServer server) {
        return AstraCosmos.create(server, aurora());
    }

    public static AstraCosmos.DiscoverResult reveal(ServerPlayer player) {
        return AstraCosmos.discover(player, "observatory:aurora");
    }
}
```

Handle the returned result in the consumer. Only `CREATED` and `ALREADY_EXISTS`
mean the saved definition matches the submitted content. A conflict requires
an explicit content decision; it does not replace the existing system. Do not
grant discovery as if conflicting creation succeeded.

After discovery, press **R**, then **M**, select Aurora and **Aim at system**.
Set `/astra-flight speed interstellar` and hold **W** for the first manual visit.
Arrival unlocks future **Jump to system** requests. Select Nereid and **Approach
body** to watch the automatic flight toward its rings.
Creation alone does not reveal content or move anyone. Discovery grants chart
visibility; it does not record a visit or unlock fast travel. Nearby-sector scanning
discovers generated systems; custom content is revealed explicitly through this API.

## Body and system builders

| Factory or option | Contract |
| --- | --- |
| `CelestialBodies.planet(id, name, kind, radiusMeters)` | `ROCKY`, `OCEAN`, `GAS_GIANT`, or `ICE` material |
| `CelestialBodies.star(id, name, radiusMeters)` | Existing stellar shader |
| `CelestialBodies.pulsar(id, name, radiusMeters)` | Compact emissive body with rotating visual beams; no evolution clock |
| `CelestialBodies.blackHole(id, name, radiusMeters)` | Physical horizon radius; existing lensing and accretion disc |
| `.orbit(meters, seconds)` | Parent-relative semimajor axis and period; both zero means stationary at that origin |
| `.parent(bodyId)` | Local parent identity; empty string (the default) means the system origin |
| `.phaseRadians(value)` | Initial mean anomaly in `[-2pi, 2pi]` |
| `.inclinationRadians(value)` | Orbital inclination in `[-pi, pi]` |
| `.eccentricity(value)` | Elliptical orbit in `[0, 0.3]` |
| `.color(red, green, blue)` | Finite linear RGB components in `[0, 1]` |
| `.atmosphere(value)` | Artistic strength in `[0, 1]` |
| `.rings(inner, outer)` | Body-radius ratios: `1 < inner < outer <= 10`; `(0, 0)` disables rings |
| `.axialTiltRadians(value)` | Tilt in `[-pi, pi]`; also sets ring orientation |
| `CelestialSystems.builder(id, name)` | Namespaced custom ID and display name |
| `.seed(long)` | Deterministic visual seed, default zero |
| `.kind(kind)` | `SINGLE`, `BINARY`, `BLACK_HOLE`, `SUPERNOVA`, or `PULSAR`; default `SINGLE` |
| `.galaxyPositionLightYears(x, y, z)` | Galactic position in light-years, default origin |
| `.body(body)` | Adds a body; the first body is the primary and fast-travel observation target |

Body defaults are stationary, white, without rings, atmosphere, tilt, or
eccentricity. System kind selects existing template behavior; it does not create
bodies, derive a physically consistent binary orbit, or start stellar evolution.
For a black-hole system, choose `CosmosSystem.Kind.BLACK_HOLE` and add a
`CelestialBodies.blackHole(...)` primary. Bodies without a parent orbit the system
origin. Moons use the same material builders and name a local parent; nested
satellites and forward references are supported. System construction rejects
missing, self-referential and cyclic parents before anything is saved.

`body.positionAt(seconds)` gives a **parent-relative** Kepler offset in meters.
Use `system.positionAt(body, seconds)` or `system.positionAt(bodyId, seconds)` for
the resolved system-local position. Parent motion is added at the same instant;
all orbital planes use the system reference axes, and parent spin/tilt does not
implicitly rotate a child's orbit. The original body constructor remains available
and sets an empty parent. Arbitrary local offsets and N-body dynamics are not exposed.

Builders are mutable and must not be shared across threads. Built values own
immutable body lists; reusing a builder cannot change an earlier result.
Invalid inputs throw `IllegalArgumentException` during construction/validation.
Use `CelestialSystems.validateCustom(descriptor)` when accepting descriptors
assembled directly from the value records.

## Identity and limits

Custom system IDs use `namespace:single_segment`, at most 64 ASCII characters
including the colon. Both components allow lowercase letters, digits, dots,
underscores, and hyphens; slashes and empty components are rejected. Use your
mod's namespace. `sol` and generated `s_x_y_z` IDs remain reserved built-in content.
Body IDs are local to their system, match `[a-z0-9_-]{1,64}`, and must be unique.
System and body names must be nonblank and at most 96 characters.

The catalog accepts **64 custom systems**, each with **1-64 bodies**, subject to
an aggregate **900 KiB encoded descriptor snapshot** limit. The byte budget includes
UTF-8 strings, length prefixes and all numeric fields; unusually long names and
large systems can reach it before the system-count limit. `create` returns
`LIMIT_REACHED` before mutation when either limit would be exceeded. All previously
valid 64-system, 12-body catalogs fit the larger budget. Discoveries
are private, with **256 systems per player** and **4096 player records**. Full
catalogs do not evict content or discoveries. Radius is `1..1e12` meters; orbital
axis and period are `0..1e15` in meters and seconds, respectively. Nonstationary
orbits require both positive values and a pericenter outside the body's radius.
All numeric inputs must be finite.

Each galactic coordinate is within `+/-1e6` light-years. The sum of a body's apoapsis
and every ancestor's apoapsis, plus its
standard observation margin must fit inside the **4096 AU** local-system
publication envelope, even though manual flight can leave that envelope.
Validation does not prove that bodies never overlap or that every
approach route is possible; the navigation system can refuse an obstructed route.
Body scale is not inflated for visibility. See [coordinates and rendering](COSMOS.md).

A first visit requires crossing into the charted system's arrival region from
outside. Systems whose regions already contain the observer require leaving and
reentering; overlapping regions do not repeatedly transfer a stationary observer.

## Server operations and events

Every `AstraCosmos` call requires the owning logical server thread and loaded
worlds. Null or malformed arguments throw; off-thread calls throw
`IllegalStateException`. Consumers own gameplay authorization. There is no client
creation request or public server command that grants arbitrary creation rights.

| Operation | Result |
| --- | --- |
| `create(server, descriptor)` | `CREATED`, `ALREADY_EXISTS`, `CONFLICT`, or `LIMIT_REACHED` |
| `find(server, id)` | `Optional<CosmosSystem>`; empty for a valid absent custom ID or unpopulated atlas sector; built-ins resolve using the server's seed |
| `discover(player, id)` | Chart visibility only: `DISCOVERED`, `ALREADY_KNOWN`, `UNKNOWN_SYSTEM`, or `LIMIT_REACHED` |

An exact creation replay is idempotent, including after restart. Same ID with
different fields returns `CONFLICT` without mutation. There is no replace/delete
API: changing an ID or a descriptor in the consumer does not migrate existing
saves. `find` does not discover a system or load its dimension chunks.

Subscribe to `CosmosDiscoveryEvent` on `NeoForge.EVENT_BUS` to observe a new
discovery. It runs synchronously on the server thread after mutation, is not
cancellable, and exposes `player()` and `systemId()`. Replay, unknown IDs, and
capacity refusals emit no event. Resolve the immutable definition with `find`;
do not retain the live player reference beyond its lifecycle.

## Saving, synchronization, and scope

Custom definitions and navigation records share the Overworld's
`data/astraengine_exploration.dat`, format **v7**. Descriptor encoding is version 2.
Existing navigation formats v1-v6 migrate without losing position, roll, speed,
definitions or discovery. Existing v4-v6 visits are retained; for v1-v3 only charted Sol/current are inferred as visited;
the other charted systems need a manual visit before fast travel unlocks.
Malformed definitions, duplicate identities, and missing referenced
systems fail closed; they are never silently replaced by generated content.

Version-one custom descriptors retain their exact original origin-relative fields
and body count when read; version two explicitly stores each `parent_id` (including
an empty parent). Custom content never gains automatically generated satellites.
Version six separately pins additive satellite generation version 1 alongside the
unchanged parent and universe generators. Built-in Sol and procedural systems gain
satellites without moving their existing planets or stars. An unknown future
satellite version fails closed: reopen with the matching engine version, retaining
the original save; do not delete the version field or regenerate the catalog.
Version seven adds independently pinned pulsar catalog version 1 without changing
existing system definitions. `CelestialBodies.pulsar("primary", "Beacon", 12000)`
creates a 12-km-radius body; use a `PULSAR` system kind and add the body explicitly.
The visual spin is deliberately slowed for inspection, not a measured physical
rotation period. A pulsar observation uses an 80-radius framing margin.
Descriptor synchronization requires protocol version 3 on both endpoints; older
clients are rejected before receiving an unsupported body kind. Descriptor NBT
and wire field layout remain version 2, with the new kind appended.

Saved definitions survive departure and restart even if the consumer no longer
calls `create`. A successful call marks data dirty for normal Minecraft saving;
it is not an immediate disk commit or a cross-mod transaction. Store game rules
and economy state under the consumer's own ownership.

Only discovered custom definitions are sent to a player, before the navigation
snapshot that references them. The client caches them for that connection,
retains them through resource reload, and clears them at logout. Client rendering
cannot register systems or mutate authoritative descriptors.

Supported public value types include `cosmos.CelestialBody`, `CosmosSystem`, and
`SpaceVector`. The builders, `AstraCosmos`, and `CosmosDiscoveryEvent` are the
consumer entry points; catalog services, codecs, network packets, and navigation
classes remain implementation details. This contract is provisional, not a
promise of binary compatibility across future engine releases.

This API creates astronomical content in shader space. It does not allocate
dimensions, terrain, walkable planet surfaces, machines, extraction ledgers, or
new shader materials. Up to **12 bodies per frame** are selected from the larger
catalog using apparent extent and distance, while retaining the primary and nearest
black-hole lens. Every catalog body stays selectable/navigable even when it is too
distant to receive a render slot. The existing alpha/beta resource API and Sol diagnostic
cycle retain their separate contracts. Rendering limits, including one nearest
black-hole lens per frame, still apply to custom content.

See [engine scope](ENGINE_SCOPE.md), [resource API](API.md), and
[client lighting API](RENDERING.md).
