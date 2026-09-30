# Planetary arrival foundation

AstraEngine has two permanent, bounded surface patches: Sol Moon and Sol Earth.
They use separate `astraengine:surface_moon` and `astraengine:surface_earth`
worlds. Existing Overworld terrain is not replaced or bound to the orbital Earth.
This is the first surface integration, not a complete traversable spherical world.

## Controls

1. Enter free flight with **R**, open **M**, select **Moon** or **Earth**, and use
   **Approach body**. Landing requires being within six body radii.
2. Press **L**, or use `/astra-flight land moon` / `/astra-flight land earth`.
   The server prepares a safe destination and guides the camera to its patch.
3. Walk and build using normal Minecraft blocks. Press **R**, or use
   `/astra-flight takeoff`, to depart from an unobstructed position above ground.
4. **B** cancels a guided transfer. A canceled landing returns to its safe
   body-fixed orbital source; a canceled departure returns to the ground source.
   **R** during flight returns to the real location where that flight began.

World generation, arrival and departure are engine facilities. There is no fuel,
rocket-part catalog, construction economy or SolarTech dependency.

## Identity, coordinates and terrain

The [surface model](SURFACE_MODEL.md) defines geographic coordinates, reversible
body-fixed transforms and a shared version-one geography function. The orbital
material and the voxel generator sample the same seed, height and material field.
Each patch covers approximately 4096 by 4096 meters around a fixed geographic
anchor. Its border leaves a safety margin inside that generation envelope.
Earth retains its 6,371,000-meter mean radius, and Moon retains 1,737,400 meters.
Local block coordinates use meters, so the camera is human-sized relative to
the full planetary sphere. The automated inspection route compresses travel time;
it does not shrink the planet or simulate realistic spacecraft propulsion.
The fixed vertical range is currently y=0..255, with reference sea level at 64.
The Moon has no ocean; Earth includes water and a temperate material palette.

World storage is permanent. Leaving, unloading chunks or restarting does not
regenerate saved chunks or player modifications. `astraengine_surfaces` records
body, system, world, generation version and seed; incompatible saved bindings or
loaded generators are rejected rather than silently replaced. New built-in
worlds are registered at world load. Arbitrary live dimension creation and custom
body-to-surface registration are not provided by this slice.

The same occupied catalog clock drives orbital views and surface frames. Living
surface observers keep that clock running; absent and offline time pauses it.
Earth's surface spin uses a 24,000-tick mean day without accelerating existing
physical orbital periods. Its surface year therefore does not yet match the
independent Overworld 365-game-day calendar. Existing orbital phases are retained;
the new geography material and rotation definition are a version-one addition.

## Prepared server-owned transfers

The server validates the requesting player's active session, system, target and
range. Only the authorized surface route can enter its target's ordinary flight
collision envelope; all other bodies retain collision protection and their full
parent orbit graph. Regular input epochs are invalidated at route ownership changes.

Flight entry requires a loaded, dry, unobstructed standing player volume, even
when requesting it while crouching or crawling. That full volume must fit inside
the source world's build height and border; source x/z must also remain strictly
inside +/-29999984 meters. High-altitude creative flight above the build range,
submerged entry and low tunnels without standing headroom are unsupported. The
server validates these conditions both when requested and immediately before
staging, saving the player's latest valid position and view. A rejected entry
leaves the player in the original world without a flight session.

Landing requests nine full destination chunks using expiring host tickets and
poll readiness. They never block a tick waiting for generation. A bounded search
finds a dry, clear landing position without replacing blocks. Edited columns
whose eye endpoint lies below the supported route floor
(currently local y=16) are skipped in favor of another candidate within14 blocks
of the patch center. If no suitable candidate remains, the transfer fails safely.
Preparation times out; it never implies arrival merely because a timer elapsed. The analytic route
aligns around the body and then descends, with a 480-tick duration. Departure uses
the reverse route shape and ends beyond the free-flight safety envelope.

A cross-dimension handoff commits only after the player's actual level changes.
Interrupted staging retains a persisted real-world recovery point. Return
preparation polls chunks, checks the player's collision volume and can select a
nearby unobstructed location; it never clears a building to make room. Failed
returns retain recovery data. Login retries are bounded, and **R** can retry.

Minecraft still performs the actual dimension transfer and client chunk loading.
The orbital shell has a bounded local height correction; it is not a displaced
voxel mesh. The current handoff is not a guarantee of zero loading delay or
pixel-identical terrain silhouettes at every distance.

## Rendering and compatibility

Ground skies use the same parent-resolved celestial positions, planetary rotation,
materials and HDR/bloom renderer as orbital flight. Earth has spherical atmospheric
scattering; Moon is airless. Ordinary surface skylight follows the mapped Sun while
preserving block emission. Host weather, gameplay lighting and seasonal mechanics
are not replaced. A shared orbit-to-ground cloud/weather simulation is not present.

With an active Iris pack, its lighting ownership remains intact; Astra celestial
presentation uses the existing late composition boundary. Shader-pack results
must be verified per pack/version, as described in [COMPATIBILITY.md](COMPATIBILITY.md).
No Distant Horizons or Voxy integration is claimed.

Closed planetary traversal, polar topology, geographic F3 coordinates, kilometre
relief, expanded world height and distant terrain rendering are the next generation
step. They require their own storage, coordinate and compatibility contracts.
