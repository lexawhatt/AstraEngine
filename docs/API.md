# AstraEngine APIs

This is a provisional engine contract for Minecraft 1.21.1 / NeoForge 21.1.252.
The `api` package and the documented immutable `cosmos` values form the consumer
boundary. Ship rendering also uses `cosmos.SpaceVector` and `FlightOrientation`.
The `server`, `systems`, and `network` packages are internal implementation.
Client extension points are supported only where explicitly documented, including
the ship collection event, renderer and `AstraEngineClient.shipRenderer()` facade.

For custom planets, stars, black holes, systems, and private discovery, use
[`AstraCosmos` and the celestial builders](CELESTIAL_API.md). They integrate saved
definitions with the map and renderer. The resource/evolution API below is a
separate diagnostic contract for the fixed alpha/beta worlds.

## Authority and units

Call `AstraSystems.snapshot(server, systemId)` and
`AstraSystems.extract(server, systemId, bodyId, operationId, amount)` on the
owning logical server thread. Off-thread catalog access throws. The initial
system IDs are `alpha` and `beta`; only body `primary` accepts extraction.
Unknown IDs, a null operation ID, or a nonpositive amount are rejected.
The caller authorizes gameplay requests; no client extraction payload exists.

Amounts are whole engine resource units, independent of FE, joules, items, or a
consumer's inventory. Extraction is capped by the resource left. Immutable
snapshots include the versioned descriptor, remaining resource, occupied ticks,
burst timer/count, and a monotonic revision. Reading does not load system chunks.

## Repeated operations and saving

An operation UUID is scoped to one system's primary body. Keep it stable across
retries. Results distinguish:

| Status | Meaning |
| --- | --- |
| `APPLIED` | A new operation accepted, possibly partially or with zero yield after depletion |
| `REPLAY` | Same UUID and amount; original yield/revision returned, no new debit |
| `CONFLICT` | Same UUID with a different amount; rejected without change |
| `RECEIPT_LIMIT` | 4096 saved receipts reached; new operations rejected |

Never credit an inventory a second time for `REPLAY`. Receipts are not evicted:
silent eviction would permit old retries to apply again. This bounded ledger is
for a diagnostic slice; automated collectors need a durable, bounded consumer
sequence/checkpoint protocol before they use the API continuously.

The catalog is versioned NBT in the Overworld's
`data/astraengine_systems.dat`. Worlds and blocks are stored by Minecraft under
their permanent dimension IDs. Existing unreadable/invalid catalogs fail closed
instead of silently regenerating pristine systems. Restore a known-good backup
when a catalog cannot be read; there is no automatic migration yet.

Operations become authoritative in memory immediately and are persisted by
Minecraft's normal saves. A success result/event is **not** a disk commit or a
crash-atomic transaction with SolarTech. Independent inventory saves require a
reconciliation protocol; this API does not claim to prevent cross-mod duplication
across an abrupt process crash. Travel recovery covers saved disconnect/restart,
not arbitrary filesystem failure.

## Time, stages, and notifications

Only live players occupying a system advance its model. Transit, unoccupied
worlds, and offline time do not advance that system. Rendering cannot advance
server state. The initial stage thresholds are `ACTIVE` above 35%, `UNSTABLE`
from 35% down to a positive remainder, and `BLACK_HOLE` at zero.
Unstable systems emit one diagnostic burst per 200 occupied ticks. No inventory
penalty, material reward, central mass, or reaction-window ending exists yet.

Subscribe to `StellarStateEvent` on `NeoForge.EVENT_BUS` on the server.
It is non-cancellable and emitted after an extraction or burst has been applied
in memory. `before()`/`after()` are immutable; compare their stages to observe a
stage transition. A replay/rejection produces no event. Do not recursively mutate
the same system from an event callback or equate notification with durable save.

Clients receive bounded snapshots every five server ticks while inside a system
or in transit. Dimension identity and revision reject stale presentation; logout
clears session data. Astronomical positions are shader coordinates, independent
of block coordinates. This API does not expose planets as blocks or entities.

## Scope

The backend has two fixed, persistent worlds and one transit world. It does not
allocate unlimited dimensions at runtime or reset a previously visited world.
Render lifecycle belongs to the client and Minecraft resource reload. The
initial black-hole distortion affects only the procedural background, so local
voxel geometry retains normal depth and interaction.

## Client presentation extension

The separate [rendering API](RENDERING.md) adds client-only per-frame lights and
resource-defined environments. It does not change this server resource/time
contract. SolarTech may extract machine visuals through the client event while
keeping its economy authoritative on the server.


## Procedural exploration catalog

The supported `cosmos` values are immutable `CosmosSystem`, `CelestialBody` and
`SpaceVector` descriptors. Internal `CosmosGenerator` queries produce built-in content;
consumer creation and lookup use `AstraCosmos`. Galactic
positions are light-years; body positions/radii are meters. These are distinct
from the persisted resource/evolution `SystemDescriptor` used by alpha/beta.
They do not silently allocate or reset worlds. See [COSMOS.md](COSMOS.md).

`ExplorationCatalog` belongs to the logical server and stores private discovery
IDs, immutable custom definitions, virtual flight position, orientation and an occupied-only orbital clock.
`RocketService` owns transient flight sessions and return/recovery points.
C2S actions and controls are requests for the sending player's own session;
by default only visited systems can be fast-jump destinations; a discovery grants chart
visibility and manual targeting. Presentation uses
`ExplorationPayload` snapshots. These internals are not yet a frozen SolarTech
travel or extraction API for generated systems.

The [consumer map API](NAVIGATION_API.md) exposes client-only replacement events,
immutable snapshots and validated request handles. Operator navigation gamerules
can explicitly bypass prior discovery/visits and fix route duration.


## Ship visualization

The [ship rendering API](SHIP_RENDERING.md) accepts immutable visual primitives,
materials, positions and orientations. Consumers supply frame snapshots; the
engine owns rendering resources, preview and visual picking. It owns no ship
part catalog, engineering statistics, construction editor, movement simulation
or new ship networking/persistence contract.

The former `api.rocket` construction surface is removed. Old stored records are
retained by [inert compatibility types](ROCKET_EDITOR.md), not an active editor.
No SolarTech implementation is included in this core-only change.

## Seasonal sky

[AstraSky and the pure planetary ephemeris](SEASONS.md#consumer-api) configure
server-owned Overworld seasons, latitude, atmosphere presentation, light pollution
and apparent solar size without changing physical celestial descriptors.
