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

After discovery, press **R**, then **M**, select Aurora and **Jump to system**.
Select Nereid and **Approach body** to watch the automatic flight toward its rings.
Creation alone does not reveal content or move anyone. Nearby-sector scanning
discovers generated systems; custom content is revealed explicitly through this API.

## Body and system builders

| Factory or option | Contract |
| --- | --- |
| `CelestialBodies.planet(id, name, kind, radiusMeters)` | `ROCKY`, `OCEAN`, `GAS_GIANT`, or `ICE` material |
| `CelestialBodies.star(id, name, radiusMeters)` | Existing stellar shader |
| `CelestialBodies.blackHole(id, name, radiusMeters)` | Physical horizon radius; existing lensing and accretion disc |
| `.orbit(meters, seconds)` | Semimajor axis and period; both zero means stationary at the system origin |
| `.phaseRadians(value)` | Initial mean anomaly in `[-2pi, 2pi]` |
| `.inclinationRadians(value)` | Orbital inclination in `[-pi, pi]` |
| `.eccentricity(value)` | Elliptical orbit in `[0, 0.3]` |
| `.color(red, green, blue)` | Finite linear RGB components in `[0, 1]` |
| `.atmosphere(value)` | Artistic strength in `[0, 1]` |
| `.rings(inner, outer)` | Body-radius ratios: `1 < inner < outer <= 10`; `(0, 0)` disables rings |
| `.axialTiltRadians(value)` | Tilt in `[-pi, pi]`; also sets ring orientation |
| `CelestialSystems.builder(id, name)` | Namespaced custom ID and display name |
| `.seed(long)` | Deterministic visual seed, default zero |
| `.kind(kind)` | `SINGLE`, `BINARY`, `BLACK_HOLE`, or `SUPERNOVA`; default `SINGLE` |
| `.galaxyPositionLightYears(x, y, z)` | Galactic position in light-years, default origin |
| `.body(body)` | Adds a body; the first body is the primary and interstellar arrival target |

Body defaults are stationary, white, without rings, atmosphere, tilt, or
eccentricity. System kind selects existing template behavior; it does not create
bodies, derive a physically consistent binary orbit, or start stellar evolution.
For a black-hole system, choose `CosmosSystem.Kind.BLACK_HOLE` and add a
`CelestialBodies.blackHole(...)` primary. Planets orbit the system origin;
parented moon orbits and arbitrary local offsets are not exposed yet.

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

The catalog accepts **64 custom systems**, each with **1-12 bodies**. Discoveries
are private, with **256 systems per player** and **4096 player records**. Full
catalogs do not evict content or discoveries. Radius is `1..1e12` meters; orbital
axis and period are `0..1e15` in meters and seconds, respectively. Nonstationary
orbits require both positive values and a pericenter outside the body's radius.
All numeric inputs must be finite.

Each galactic coordinate is within `+/-1e6` light-years. Body apoapsis plus its
standard observation margin must fit inside the existing **4096 AU** navigation
sphere. Validation does not prove that bodies never overlap or that every
approach route is possible; the navigation system can refuse an obstructed route.
Body scale is not inflated for visibility. See [coordinates and rendering](COSMOS.md).

## Server operations and events

Every `AstraCosmos` call requires the owning logical server thread and loaded
worlds. Null or malformed arguments throw; off-thread calls throw
`IllegalStateException`. Consumers own gameplay authorization. There is no client
creation request or public server command that grants arbitrary creation rights.

| Operation | Result |
| --- | --- |
| `create(server, descriptor)` | `CREATED`, `ALREADY_EXISTS`, `CONFLICT`, or `LIMIT_REACHED` |
| `find(server, id)` | `Optional<CosmosSystem>`; empty for a valid absent custom ID; built-in IDs resolve using the server's seed |
| `discover(player, id)` | `DISCOVERED`, `ALREADY_KNOWN`, `UNKNOWN_SYSTEM`, or `LIMIT_REACHED` |

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
`data/astraengine_exploration.dat`, format **v3**. Descriptor encoding is version 1.
Existing navigation formats v1/v2 migrate without losing position, roll, speed,
or discovery. Malformed definitions, duplicate identities, and missing referenced
systems fail closed; they are never silently replaced by generated content.

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
new shader materials. The existing alpha/beta resource API and Sol diagnostic
cycle retain their separate contracts. Rendering limits, including one nearest
black-hole lens per frame, still apply to custom content.

See [engine scope](ENGINE_SCOPE.md), [resource API](API.md), and
[client lighting API](RENDERING.md).
