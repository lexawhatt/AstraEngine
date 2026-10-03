# Live geographic frames

Geographic references connect canonical Earth and solid-body charts, as well as
legacy bounded surface worlds, to actual server-owned player poses. They supply
motion conversion, immutable frame snapshots and notifications when an observed
tile changes. This read-only API does not move players or transfer blocks. Separate
[canonical traversal](EARTH_WORLD.md) and [solid-planet storage](PLANET_SURFACES.md)
own prepared world crossings while Minecraft retains the original blocks.

## Observe from a consumer

On the owning logical-server thread, including an integrated singleplayer server:

```java
AstraGeography.snapshot(player).ifPresent(snapshot -> {
    PlanetaryPose bodyPose = snapshot.pose();
    PlanetaryPose.LocalPose tilePose = snapshot.localPose();
    PlanetaryFrame frame = snapshot.frame();
    // Keep the exact frame with local values. Reconstruct before changing frames.
    PlanetaryPose restored = tilePose.toBody(frame);
});
```

Imports are `dev.lexawhatt.astraengine.api.AstraGeography` and
`dev.lexawhatt.astraengine.surface.{PlanetaryPose, PlanetaryFrame}` (use ordinary
explicit Java imports). The sample does not authorize mutation, load chunks,
create worlds or grant discoveries. A null player or wrong-thread access throws;
dead/removed players, unsupported dimensions and out-of-bounds columns
return `Optional.empty()`. Bindings cover the saved Astra Earth preset, allocated
solid-planet charts, and legacy Moon, Earth, highlands, continental coast, alpine
and abyss windows. A loaded level must have the corresponding pinned generator.
Trusted server code may also sample an unconnected host player; a successful query
does not assert network membership. The automatic tracker observes connected players.
Existing Moon/Earth arrival world identities remain unchanged.

`AstraGeography.planetaryReference(level)` returns the immutable `GeographicReference`
for any supported projection on the owning server thread. The older
`AstraGeography.reference(level)` retains its patch-only `SurfaceReference` return
type and behavior. A reference's `geographic(hostFeet)` reads physical altitude
directly from host Y and the stored altitude origin. `resolve(geographic)` performs
the inverse without clipping or substituting the patch center. The server wrapper
`AstraGeography.resolve(level, geographic)` also checks build height and the world
border. None of these operations loads chunks, allocates worlds or moves a player.

`SurfaceReferences` supplies the fixed legacy references to client presentation.
Canonical chart references instead follow the saved Earth context and authorized
solid-body contexts. Different Earth-sized prototypes have different geography IDs.
The three legacy continental windows share `astraengine:continental/v1`; a vanilla
Overworld retains no geographic binding, while an Astra Earth Overworld does.
Sampling does not merge the independent prototype terrain realizations.

`SurfaceFrameSnapshot` contains a geography ID, dimension ID, pinned topology and
body-fixed pose. It retains no mutable player/world references. These values can
be sent to worker code or retained after disconnect, but are snapshots rather
than a live player handle. They carry no simulation clock or claim of disk durability.

## Coordinates and velocity

- Body position is player feet in double meters relative to the planet center.
- Velocity is the host's stored `getDeltaMovement()` state, transformed into
  body-fixed meters **per game tick**. It is not measured displacement between
  samples or meters per wall-clock second. Teleports do not manufacture velocity.
- Host yaw/pitch becomes a full quaternion in the patch's established tangent
  convention. Host players have no roll; pure poses can preserve arbitrary roll.
- The tile frame is the fixed tangent basis at its geographic center, with radial
  Y up. Its local X/Z axes can rotate at a face boundary and are not globally east
  and south. These coordinates are not Minecraft block or chunk addresses.

`SurfacePatch.toBodyVelocity(feet, velocity)` evaluates the differential of the
existing nonlinear gnomonic/radial position mapping. Horizontal scale depends on
column and altitude. `toLocalVelocity` inverts that differential at the same feet
position. The existing `toBodyDirection` and `toLocalDirection` remain ordinary
rotation-only direction transforms; they must not replace these velocity methods.
No orbital or axial-spin transport velocity is added.

`PlanetaryPose.inFrame(frame)` and `LocalPose.toBody(frame)` re-express the same
position, velocity and full orientation in an affine tangent frame. Rebase through
body-fixed space before interpolating poses from different frames. Interpolating
raw local X/Z values across a frame boundary can produce a false jump. Pure
operations validate finite representable values and work independently of worlds.

## Change notifications and lifetime

Subscribe to `SurfaceFrameChangedEvent` on `NeoForge.EVENT_BUS`. The engine posts
this non-cancellable event synchronously on the server thread after its observation
context changes. Event delivery follows the pinned host's
[event model](https://docs.neoforged.net/docs/1.21.1/concepts/events/).

- Empty `previous()` means initial observation or re-entry.
- Empty `current()` means leave, death, respawn reset or disconnect.
- Both present means the observed tile changed. `previous()` is the last observed
  pose, not the position at which the player originally entered that tile.

A command or another mod can move a player across many tiles. The event therefore
reports endpoint contexts and does not prove continuous crossing, enumerate every
skipped tile or grant travel permission. Same-tile movement updates the internal
previous sample but does not emit repeated events. Consumers needing a current
pose should sample explicitly on the server thread.

Each player has an independent transient context. World changes and respawn reset
it; logout removes it; shutdown clears the tracker without writing another player
position ledger. Shutdown emits no individual leave events; consumers retaining
their own per-player maps must clear those on server stop. Reopening a world derives
a new context from Minecraft's actual
saved player position. Resource reload does not reset server observations or move
players. Listeners may retain immutable snapshots, but must not retain the event's
player reference beyond their callback.

## Inspect and verify

F3 in each bound world replaces primary XYZ/Block/Chunk rows with longitude,
latitude and reference altitude, followed by geography ID and geographic tile. These rows are
client-derived presentation, not permission or an authoritative network snapshot.
Reduced-debug mode reveals no coordinates. Unsupported worlds retain host F3.
The tile ID can change at a boundary while body position and the view remain continuous.

Operators can inspect or navigate within their current bound window:

```text
/astra geography here
/astra geography tp <latitude_degrees> <longitude_degrees> <altitude_meters>
```

Latitude is in [-90, 90], longitude in [-180, 180] with +180 canonicalized to -180.
Altitude refers to player feet above the reference sphere, including negative
seabed altitudes. The command requires permission 2, respects the host teleport
event and rejects route ownership conflicts, foreign dimensions, outside addresses,
invalid event-modified targets and insufficient build-height headroom. Like vanilla
`/tp`, it does not find safe ground or clear obstructions. Host chunks and saved
positions remain authoritative. A successful teleport does not grant discovery.

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-surface-frames -PverifyPhase=surface-frames-create
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-surface-frames -PverifyPhase=surface-frames-restart -PverifyGraphics=fabulous
```

Use a fresh disposable directory for creation. This fixture stages a walkway near
a logical face boundary, then uses real forward/backward movement and collisions.
Restart checks the saved host position and built marker blocks. This is a test of
live geographic observation during ordinary movement, not proof of a completed
cross-world seam renderer or multiplayer network latency handling.

See [tile identities](PLANETARY_GEOGRAPHY.md), [highlands terrain](PLANETARY_TERRAIN.md)
and [bounded orbital arrival](SURFACE_TRAVEL.md) for those legacy fixtures.
Current canonical face/band storage, neighboring collision and interaction, and
free surface/space transitions are documented in [Earth worlds](EARTH_WORLD.md)
and [solid-planet surfaces](PLANET_SURFACES.md). Their dedicated native scenarios
provide separate evidence; the legacy `surface-frames` fixture does not qualify them.
