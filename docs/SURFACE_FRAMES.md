# Live geographic frames

Stage 3a connects the highlands' geographic topology to actual server-owned player
poses. It supplies correct motion conversion, immutable frame snapshots and
notifications when an observed tile changes. Ordinary walking still uses the
existing permanent Minecraft world. This does not stitch different worlds or
transfer blocks across a globe.

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
dead/removed players, unsupported dimensions and out-of-bounds highlands columns
return `Optional.empty()`. Only the highlands is bound to this API in this slice.
Trusted server code may also sample an unconnected host player; a successful query
does not assert network membership. The automatic tracker observes connected players.
The existing Moon/Earth arrival APIs retain their own unchanged bindings.

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

F3 in the highlands shows the geographic tile and a position relative to its center,
in addition to longitude, latitude and reference altitude. These extra rows are
client-derived presentation, not permission or an authoritative network snapshot.
Reduced-debug mode and unsupported worlds do not show them. Tile-local numbers
can change at a boundary while body position and the view remain continuous.

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
and [bounded orbital arrival](SURFACE_TRAVEL.md). Block ownership across new local
world frames, collision/view stitching and recoverable world migration remain the
next stage; existing chunk storage, limits and borders are unchanged.
