# AstraEngine

A Minecraft 1.21.1 mod for NeoForge. Author: **lexawhatt**.

AstraEngine manages stellar system state, persistent worlds, and shader-rendered
space. Interplanetary flight uses a local void and shader-rendered celestial bodies.
Planetary surfaces, builds and arrival platforms use persistent Minecraft blocks.
SolarTech will be a separate API consumer providing machinery, an energy economy,
and progression.

[Engine and consumer-mod boundaries](docs/ENGINE_SCOPE.md): AstraEngine also
owns server-side navigation, safe travel, and persistence; fuel, machinery, and
progression rules belong to the gameplay layer.

## Celestial creation API

Consumer mods can create persistent systems with rocky, ocean, gas, or ice planets,
stars, and black holes through `AstraCosmos` and the `CelestialBodies` /
`CelestialSystems` builders. Configure physical radii, orbits, colors, atmospheres,
and rings, then reveal them to selected players. Manual first visits unlock fast
travel; the map, smooth approach, and rendering use the saved definitions after
reload and restart.

[Java example and API contract](docs/CELESTIAL_API.md). The current limit is
64 custom systems with 1-64 bodies each, within a 900-KiB aggregate descriptor
synchronization budget. Parent-relative orbits support authored moons. Supported
solid bodies also receive a versioned [planetary surface profile](docs/PLANET_SURFACES.md);
gas giants remain astronomical bodies without an invented solid floor.

## Planetary surfaces

Choose **Astra Earth** when creating a world to bind Overworld to a closed,
Earth-radius geography. Existing ordinary Overworld saves retain their generator.
Geographic **F3** shows longitude, latitude and physical altitude. Persistent cube
faces and altitude bands store the blocks; prepared neighboring observations allow
walking or flying across their boundaries, including poles. Each block and chest
has one canonical owner, including interactions made from an adjacent band or face.

Press **R** on a bound planet to inspect it with free movement through real terrain.
**WASD**, **Space / Shift**, mouse rotation and **Q / E** roll control the view.
Crossing **100,000 m** transfers between the surface and space at the reached
geographic position. The server prepares the destination and preserves the view;
unready terrain holds movement at a valid position. There is no mandatory landing
animation. Loaded clear-air ascent supports up to 51,200 m/s; movement near actual
terrain retains bounded collision steps and a 2,560 m/s limit. Prepared storage
seams and horizontal chunk streaming remain bounded.
Press **R** again on the surface to leave inspection at the reached position.
The map's **To orbit** button, also available as `/astra-flight orbit`, prepares a
direct view 150 km above the same location and retains the real departure for return.

Rocky, icy and ocean planets and moons use the same storage, traversal and
space-boundary contracts with their own physical radii and saved profiles.
See [Earth storage and controls](docs/EARTH_WORLD.md) and
[solid-planet profiles, persistence and limits](docs/PLANET_SURFACES.md).
The earlier bounded Moon/Earth patches and independent highlands/horizon prototypes
remain separate compatibility paths; their [guided controls](docs/SURFACE_TRAVEL.md)
do not describe the canonical planetary boundary.

[Live geographic frames](docs/SURFACE_FRAMES.md) expose read-only server poses and
tile-change notifications. `/astra geography here` reports the current address;
Minecraft continues to own canonical blocks, collision and saved player positions.

Earth's close orbital view now uses its existing procedural height field for
spherical parallax occlusion, slope lighting and progressively resolved surface
materials. A bounded asynchronous height cache avoids resampling the full height
function at every nearby ray step. Map approach frames ordinary planets/moons more closely while preserving
their real radii. Bounded summaries of actual surface edits add large builds,
quarries and exposed nighttime emission to the orbital view. Summaries are derived
from saved blocks; they do not transmit every block or turn the globe into full-detail geometry.

The new **Astra Earth** world type provides [continental Earth storage](docs/EARTH_WORLD.md)
in Overworld, with climate biomes, vegetation, geographic F3 and persistent blocks.
Its versioned orbital height/climate maps share the chunk generator and add progressive close relief.
Free descent selects the geographic location being approached. The ground calendar drives the same Earth
rotation and Sol orbital motion in flight, including frozen daylight and safe recovery
from time commands. A bounded distant terrain mesh continues its oceans and mountain
silhouettes beyond loaded chunks, with geographic lighting and cloud occlusion.
Orbital and distant surfaces capture active block textures and the default Earth
biome tints: forests use canopy colors, snow stays pale, and frozen seas use ice
instead of liquid-ocean color and reflections. These are regional material summaries;
individual tree geometry is not reconstructed from orbit. Actual surface edits
overlay those regional materials. The background layers beneath host geometry
and yields to active Iris packs. Existing worlds and legacy patches retain their
saved generation versions.

New **Astra Earth** worlds use terrain v4: curved rivers join ocean outlets through
continuous confluences and narrower valleys, with dissected mountain ranges, plateaus, old uplands, lowland
basins and ocean trenches. Regional temperature, moisture and rain shadows separate
dry interiors, forests and snowy highlands; river banks receive local moisture.
Bed and water elevations are shared by chunks and planetary presentation. Existing v1-v3
worlds keep their geography; use a new Astra Earth world for the new generation.
See [river generation and its resolution limits](docs/EARTH_WORLD.md).

[Orbital Earth lighting](docs/EARTH_ATMOSPHERE.md) uses spherical atmospheric
transport and a shared geographic cloud field. Ground and orbital clouds follow
the same Earth weather. Optional `/astra-render auto-exposure true` meters the
complete celestial scene; fixed exposure remains the default.

New Astra Earth worlds use cave v2: narrower passages follow folded bedding and
fractures, with occasional junction rooms and shafts, up to 1.2 km beneath the
local terrain. Seabeds remain protected and dry entrances occur where passages reach the surface.
Old saved generators keep their underground version. **F8** toggles a rebindable
64-block inspection flashlight; enclosed unlit views are dark while local lamps
and night vision remain usable. Active Iris packs retain lighting ownership.

Distant terrain is rendered by Astra's own bounded geographic mesh and orbital
height caches. Distant Horizons integration has been retired; planetary testing
uses a profile without DH. No extra distance fog is added to hide terrain joins.
The renderer samples the shared field without generating the whole planet as chunks.

An opt-in [horizon calibration world](docs/PLANETARY_HORIZON.md) adds an Earth-radius
spherical ocean and fixed visual towers. It demonstrates altitude-dependent
horizons and bottom-first occlusion. Near blocks remain flat; active
Iris packs retain their own sky. This is a geometry prototype, not globe traversal.

## Consumer ship rendering

AstraEngine renders consumer-supplied visual assemblies: analytic shapes,
materials and transforms. It provides world/void drawing, a reusable preview and
picking through the [ship rendering API](docs/SHIP_RENDERING.md).

The Rocket Editor, part catalog, construction workflow and engineering
calculations have been removed from the engine. No SolarTech module is bundled.
Old editor blocks and assembly records remain as inert data archives for future
migration; [existing-save behavior](docs/ROCKET_EDITOR.md) explains the limits.
The scene/GLSL editors and astronomical free camera remain engine tools.

## Cosmos and Rocket Mode

Press **R** to enter free inspection. In an ordinary unbound world the player moves
to a bounded void while the camera travels through shader-rendered space; on a bound
planet inspection starts at the actual surface position. The starting system contains the Sun and
eight planets and 21 major moons with real mean radii and orbital distances. Orbital parameters
come from NASA tables; initial orbital phases and planetary surfaces are artistic.

- **Mouse**: free rotation through the poles; **Q / E**: roll.
- **WASD**, **Space / Shift**: movement along the camera axes without inertia.
- **Mouse wheel** or **+ / −**: speed ×/÷1.5; **B**: stop.
- **M**: map and **Approach body**, with smooth alignment and a decelerating
  approach; **B** or the map's **Cancel approach** button cancels it.
- On the map: **MMB drag** pans, **Wheel** zooms at the cursor, **F** focuses the
  selected object, and **Home** resets the view. Select **Moon** and press **F**
  to inspect the Earth-Moon pair; **Approach body** also works for satellites.
- **M -> Cosmic atlas** or `/astra-flight atlas`: select a galaxy and region;
  **Chart and aim** marks a public destination for its first manual trip.
- **C**: refresh nearby systems around the current visited system.
- Press **R** again to leave inspection. Ground inspection retains the reached
  position; void inspection returns to its saved real-world recovery position.

Procedural systems contain planets and seeded moons, binary stars, rare black holes, and supernova
remnants. The map shows nearby systems before a visit; **Aim at system** helps
with the first manual flight. Arrival unlocks fast travel and reveals the next
neighborhood. Known systems, visits and virtual position persist. After an
interrupted flight, the real player returns to the departure
point, and the next activation resumes exploration from the saved virtual position.

Cosmic view distance is independent of chunks: the CPU computes coordinates in
`double`, and GLSL receives directions and angular sizes. Distant planets really
are small; navigation markers help locate them. Nebulae, star fields, atmospheres,
rings with shadows, and accretion disks are procedural. Supported generated solid
planets and moons allocate persistent surface storage on demand under a bounded
world budget. Unloading or restarting does not regenerate their identities or edits.

Free flight crosses systems and can leave the Milky Way's spatial procedural
disk. The **Cosmic atlas** adds eight procedural neighboring galaxies, with
spiral, elliptical and irregular structures. Their nuclei, nebulae, star clusters
and supernova remnants have named destinations and shared spatial descriptors.
One active galaxy has a quasar nucleus with jets. The core's stellar population
and dust replace the previous smooth luminous spot. Transit has no radial lines
or ribbons. [Atlas contents, controls and limits](docs/UNIVERSE.md).

Each galaxy also has a **Pulsar** destination with a compact physical body and
rotating visual beams. Find **Milky Way Pulsar** in the atlas, chart it and fly
there manually before fast travel unlocks. Existing systems retain their identities.
The Moon now shares detailed maria, filtered impact markings and grazing relief
lighting between its orbital material and the Overworld phase disc; saved landing
terrain and physical radii remain unchanged. Large maria and impact markings change
surface reflectance, not the voxel terrain height.

Use `/astra-flight speed interstellar` for the first system visit, or
`/astra-flight speed galactic` for an external galaxy view. `/astra-flight galaxy aim`
turns toward the nearest galaxy center without moving the observer. Full controls and scope are
in the [cosmos guide](docs/COSMOS.md).

Black holes bend the star field and light from distant bodies. A layered hot disk
curves above and below the dark silhouette and changes with the viewing angle;
HDR bloom adds a soft glow. This is an analytic visual approximation. To inspect
one, find a black-hole system on the map and select its primary object →
**Approach body**.

[Controls, scale, and limits](docs/COSMOS.md) · [Sol parameter sources](docs/SOLAR_REFERENCE.md).

Consumer mods can [replace the system map and atlas](docs/NAVIGATION_API.md) through
a client event with immutable snapshots and server-validated requests. Operators
can enable `astraFreeNavigation` to bypass prior discovery/visits, and set
`astraTravelSeconds` to a positive duration (1 = 20 server ticks). Defaults preserve
ordinary navigation. These rules do not bypass surface chunk preparation.

## Continents, mountains and ocean depths

Three new permanent [continental inspection worlds](docs/CONTINENTAL_WORLDS.md)
sample a shared procedural globe: coast, alpine peaks above 8 km and abyssal
seabeds. F3 reports physical altitude. Their explicit 4,064-block altitude windows
preserve one-meter vertical relief; they do not yet connect into unlimited-height
storage or a walkable globe. Existing worlds remain unchanged.

## Overworld Sun and supernova

In the ordinary Overworld, `environment auto` shows an atmospheric sky with
volumetric clouds and cloud-shadowed light shafts, orbital seasons, a rotating
star field and a Milky Way visible
under low light pollution. A year is **365 days**; each mean solar day is
**20 minutes at 20 TPS**. Winter days are shorter and summer days longer. The
Overworld Sun is displayed at **3x angular size** with atmospheric glow; physical
Sol dimensions in space remain unchanged.

```text
/astra season set winter
/astra season status
/astra season sun-size 3
/astra season pollution 0
/astra-render shafts true
/astra-render cloud-cover 0.65
```

[Seasonal sky, clock rules and API](docs/SEASONS.md). Terrain, precipitation,
spawning and sleep rules remain Minecraft-owned. The star retains its full
resource by default. An operator can start gradual diagnostic extraction:

```text
/astra sun demo 60
/astra sun pause
/astra sun resume
/astra sun status
/astra sun reset
```

Extraction is followed by 4 seconds of collapse, 16 seconds of supernova, and a
permanent remnant. This is fictional gameplay evolution of the Sun; energy uses
arbitrary units, with no destruction or resource rewards. The phase persists;
time pauses without observers and while the server is offline. The Overworld
and the Sun in Rocket Mode display the same state. Sky lighting visually weakens
while preserving the contribution of block light sources.
`/astra-render environment off` restores the vanilla sky; `auto` restores Astra's sky.

The supernova expands into seeded gas layers, filaments and knots that cool into
a dim remnant. Generated remnant systems use the same mature material. The
gas emission has a modest 22% brightness lift without raising cloud illumination
or the flash envelope. The supernova has a rising rumble, an impact, and a fading tail. Set its volume
with `/astra-audio volume 0.75`, or disable it with `/astra-audio enabled false`.
Space and the solar sky use HDR bloom; adjust its strength with
`/astra-render bloom-strength 0.65` and exposure with `/astra-render exposure 1`.

[Commands, persistence, lighting, and limits](docs/SOLAR_SKY.md).

## Persistent worlds and evolution

Two persistent systems, `alpha` and `beta`, each have their own seed, resource,
and world. Travel between them uses a four-second void transit. Worlds and builds
survive return visits and server restarts. Without living players, system
evolution and the burst timer pause; an offline server accumulates no elapsed time.

To try them, create a **Creative world with commands enabled**. Commands require
operator permission level 2:

```text
/astra visit alpha
/astra status
/astra extract 700000
/astra extract 300000
/astra visit beta
/astra leave
```

The first extraction leaves 30% of the resource and makes the star unstable;
a diagnostic burst follows after 200 active ticks. The second exhausts the
resource and reveals a black hole with a disk. A new system starts with
1,000,000 arbitrary resource units. `/astra leave` returns to the start of the
current journey. The platform is created once, and changes to it persist.
Travel is rejected if the arrival area is occupied. If logout interrupts transit,
the player is restored to the departure point on the next login.

This is an engine prototype with diagnostic commands. Extraction does not yet
provide FE, items, or materials. Bursts do not deduct energy from machines.
Arbitrary persistent-world creation for the procedural catalog, a growing horizon,
a white-hole finale, and ship transfers are not implemented. Lensing affects
only the shader-rendered background. Optional renderer integration has explicit
[shader-pack ownership and verification limits](docs/COMPATIBILITY.md).

## Sodium and Iris

Sodium and Iris are optional. With Iris shaders disabled, Astra uses its ordinary
rendering. An active shader pack owns Overworld atmosphere and terrain lighting;
Astra composes its cosmos in other dimensions and consumer visuals after the pack.
Forced environment previews do not replace the pack's Overworld sky. The pack does not
automatically display Astra's seasons or stellar events. Production code never
changes shader settings.

Minecraft Clouds Fancy/Fast enables Astra's clouds when its atmosphere owns the
sky; Off hides them. Active Iris packs retain their own cloud rendering.

`/astra-render compatibility` reports installed versions and actual pack state.
[Version matrix, current verification status and feature limits](docs/COMPATIBILITY.md).

## Lighting and planetary sky

The renderer supports directional, point, and spot lights, a flashlight with a
soft edge, cave darkening, bloom, and quality profiles. The planetary profile
shows a day/night cycle and a ringed planet: its shadow on the rings follows the
sun's direction. Profiles load from resources; other mods can supply lights
through the client API.

```text
/astra-render environment planet
/astra-render flashlight true
/astra-render quality balanced
```

Use `/astra-render environment auto` for automatic environment selection
(the AstraEngine solar sky in the Overworld).
Visual lighting does not change mob spawning or server light levels. The lighting
layer operates on opaque geometry; screen-space shadows are limited to the depth
visible in the frame. These commands do not create an arbitrary planetary world.

[Profiles, commands, lighting API, and limits](docs/RENDERING.md).

## Scene and shader editor

**F7** or `/astra-editor` opens an editor over the live world: spheres, boxes,
rings, disks, point/spot/directional lights, properties, undo/redo, and local
presets. The **GLSL** button opens a post-effect text editor with compilation,
error logs, and `.fsh` saving. A rejected shader preserves the last working effect.
The editor does not change server blocks or stellar system state.

[Controls, formats, and GLSL interface](docs/EDITOR.md).

## Environment

- Eclipse Temurin JDK 21 (64-bit).
- Minecraft 1.21.1, NeoForge 21.1.252.
- Gradle 9.2.1 through the included Gradle Wrapper.

The Java version and vendor (Eclipse Adoptium / Temurin 21) are pinned for Gradle
in `gradle/gradle-daemon-jvm.properties`, and for compilation and game launches
in `build.gradle`. Gradle downloads a suitable JDK automatically if none is available.

## Launching Minecraft

From a terminal at the project root:

```sh
./gradlew runClient
```

On Windows: `gradlew.bat runClient`.
The first launch downloads dependencies and game assets. The client runs with
AstraEngine in a development environment; saves, settings, and logs live in `run/`.
The main log is `run/logs/latest.log`. The client has a maximum heap of 4 GiB.

In IntelliJ IDEA, open the project root as a Gradle project, wait for synchronization,
and run `runClient` from the Gradle tool window. Set the Project SDK to Temurin 21;
Gradle uses the JVM version pinned by the project.

## Build

```sh
./gradlew build
```

Output: `build/libs/astraengine-1.0.0.jar`.

## Verification

```sh
./gradlew test
./gradlew runVerifyServer
```

The first command tests the pure model. The second runs GameTestServer checks
for NBT, the network format, and pausing empty systems on the server.
Minecraft 1.21.1's built-in GameTestServer does not include datapack dimensions
when creating its world; a separate client scenario checks travel and block persistence.

The canonical planetary fixtures use fresh directories for `earth-crossings`
(walking face/pole/band transitions), `earth-space-boundaries` (free ground/space
handoffs), `earth-space-rotating` (Earth rotation during free movement), and
`earth-space-pack` (the same geographic cases with a required active Iris pack).
`earth-interactions` followed by `earth-interactions-restart` checks ordinary
survival mining/placement against real neighboring blocks. `solid-planets-create`
followed by `solid-planets-restart` checks independent Moon/Europa terrain and
inventories. These verification sources are excluded from the distributed JAR.

The connected journey fixture uses ordinary controls and public body approach
after its initial setup: Earth seam crossing, full ascent, Moon descent and block
placement, then ascent and return to the original Earth build. Its separate
restart process reopens that exact saved world and checks both edits and the
player's returned pose. Both phases have passed under the copied test modpack:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-journey -PverifyPhase=earth-journey-create
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-journey -PverifyPhase=earth-journey-restart
```

The shorter `earth-platform-collision` scenario checks a real platform just below
an altitude seam, first during maximum-speed inspection and then ordinary gravity:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-platform -PverifyPhase=earth-platform-collision
```

That scenario also passed with Lithium 0.15.4. These are functional checks:
the connected journey's 4-GiB run reported DH memory pressure and a long forced
checkpoint save. Passing travel without a loading screen is not a frame-time or
save-latency guarantee. `-PverifyHeap=12G` selects a larger isolated fixture heap.

The older small-patch surface fixture is retained for historical evidence. Use the
canonical planetary phases above to verify current Moon entry and geographic
boundaries; saved legacy patch worlds retain their independent storage.

Current dedicated planetary tests exercise stale/foreign acknowledgements, actual
changed destination blocks, brake/turn-away/disconnect cleanup and real host
transfer/recovery vetoes. The older `surface-*` phases describe the historical
guided patch path. `-PverifyGraphics=fabulous` selects the alternate native path;
optional mod and shader-pack files belong only in an isolated test directory.

The automated game scenario opens a separate Minecraft window. Use a fresh
directory for a new run (the first phase refuses to overwrite an existing world):

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-run
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-run -PverifyPhase=restart
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-run -PverifyPhase=interrupt
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-run -PverifyPhase=recover
```

The fixture automatically passes the initial accessibility screen in its test
profile. It checks stage changes, system isolation, return visits to modified
blocks, restart, interrupted transit, reload, and resize. Screenshots are saved
in the selected directory's `evidence/` folder. The verification mod under
`src/verification` is excluded from the shipped JAR.

Lighting, a dark room, the flashlight, profiles, and ring shadows:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-lighting-run -PverifyPhase=lighting
```

Use a fresh directory. This scenario also checks reload with an invalid profile,
resize, light budgets, and the Fancy → Fabulous transition.

Editor interaction through real widgets, GLSL compilation, and saved data after
restart (the first command requires a fresh directory):

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-editor-run -PverifyPhase=editor
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-editor-run -PverifyPhase=editor-restart
```

Physical scale, Rocket Mode, discoveries, the map, and flight recovery
(the first command requires a fresh directory):

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-cosmos-run -PverifyPhase=cosmos
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-cosmos-run -PverifyPhase=cosmos-restart
```

Smooth local approach, camera alignment, cancellation, and return to manual flight
have a separate phase in a fresh world:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-approach-run -PverifyPhase=approach
```

It uses the real map widgets and records server snapshots and rendered camera
poses while checking resource reload, stale control rejection, and leaving/reentering
Rocket mode. These checks do not measure flight performance or remote network latency.

Public celestial creation, private discovery, custom planet/black-hole rendering,
and persistence without recreating definitions on restart:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-api-run -PverifyPhase=celestial-api
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-api-run -PverifyPhase=celestial-api-restart -PverifyGraphics=fabulous
```

The first phase needs a fresh directory. It creates content through the public API,
uses the real map and approach controls, and checks resource reload and cancellation.
The second reads the same saved world, compares exact definitions/navigation, and
checks custom content under Fabulous graphics. Both preserve a real-world block marker.

Manual interstellar discovery, locked/unlocked travel, external galaxy views,
and exact saved navigation have a separate two-launch scenario:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-galactic-run -PverifyPhase=galactic
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-galactic-run -PverifyPhase=galactic-restart -PverifyGraphics=fabulous
```

The first phase uses a fresh world, real map aiming and movement controls; it
checks a forged unvisited jump, swept arrival, neighborhood expansion, unlocked
return, a stable physical anchor, galaxy views from outside, and resource reload.
The restart phase uses the same directory and verifies exact chart/visit/pose
persistence without granting visits. Neither scenario is a frame-time benchmark.

Galaxy atlas, public chart requests, real first visits, nebula/remnant interiors,
central black holes, a second active galaxy and exact persistence:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-universe-run -PverifyPhase=atlas
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-universe-run -PverifyPhase=atlas-restart -PverifyGraphics=fabulous
```

Use a fresh directory for the first phase and the same directory for restart.
The scenario uses actual atlas widgets and held flight controls, rejects forged
unvisited jumps, inspects region edges and reload, and retains real-world blocks.
It does not alter authoritative position or grant visits through fixture state.

A separate phase in a fresh world checks lensing, disk orientations, small angular
sizes, overlaps, and reload/resize:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-celestial-run -PverifyPhase=celestial
```

For a separate Fabulous run, add `-PverifyGraphics=fabulous` and use another fresh
directory. After ordinary travel to a catalog black hole, the fixture feeds fixed
visual scenes into the live renderer while preserving the server position and
catalog. These additional renders verify images; they do not measure normal
flight performance.

The movable map, satellite navigation and shared supernova material have a
separate phase in a fresh world:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-polish-run -PverifyPhase=celestial-polish
```

It exercises map Screen handlers and rendered markers for Moon, Europa and Titan,
MMB pan, cursor-anchored zoom, focus/reset, clipping, reload and resize. Actual
server approach reaches the Moon. Frozen server evolution samples then feed the
production Overworld renderer and controlled space-view poses, with bloom on/off.
Screenshots and scope notes distinguish real navigation from diagnostic draws;
this is not an OS input, performance or shader-pack sky-integration test.
For Fabulous, use another fresh directory with `-PverifyGraphics=fabulous`.

Pulsar atlas charting, manual first entry, continuous approach and saved return:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-pulsar-run -PverifyPhase=pulsar-create
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-pulsar-run -PverifyPhase=pulsar-restart -PverifyGraphics=fabulous
```

Use a fresh directory for creation. The fixture stages the observer near the
destination, then crosses the boundary through real manual flight; it does not
claim to fly the full galactic distance. Charting alone must leave fast travel locked.

Frozen Moon phases and pulsar beam angles, bloom pairs, reload and resize:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-lunar-visuals -PverifyPhase=lunar-pulsar
```

This separate fresh-world fixture probes the production renderer at controlled
camera poses and checks that presentation does not change navigation. For Iris,
prepare its optional stack as described in the compatibility guide below; this
checks space rendering, not a pack-owned Overworld sky.

Overworld rendering and persistent solar evolution (the first run requires a
fresh directory; the second continues its saved event):

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-solar-run -PverifyPhase=solar
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-solar-run -PverifyPhase=solar-restart
```

Consumer-supplied ship visuals, preview picking, world depth, reload/resize and
free-camera flight are exercised without a construction or gameplay mod:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-ships -PverifyPhase=ship-visual
```

Use a fresh directory. The visual producer exists only in the verification mod;
production AstraEngine does not spawn a demonstration ship or retain a ship catalog.
Dedicated GameTests also verify opaque legacy construction data retention.

Optional Iris integration has a separate native phase. Prepare a fresh directory
with the exact mods, shader pack and Iris configuration described in the
[compatibility guide](docs/COMPATIBILITY.md#reproduce-the-native-scenario), then run:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-render-compat -PverifyPhase=render-compat -PverifyGraphics=fancy
```

This fixture has a 600-second deadline and toggles Iris settings only in its
disposable test directory. It checks pack ownership, toggles, reload and shadow
isolation, then consumer previews, depth and free-camera flight.

The opaque Earth background optimization has an independent native comparison:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-earth-core -PverifyPhase=earth-core-occlusion
```

It compares the retained original shader with the registered renderer, checks
actual surface hit and full pixel coverage, and exercises uncertain limb rays,
ground/lensing fallbacks and the relief-distance boundary. Forward and reverse
GPU timing windows use clouds disabled; this does not qualify cloud performance.
Use a fresh directory and retain its settings/mod list with the results.

The separate `earth-relief-cost` phase measures whole-renderer timing differences
with private relief/background bypasses. These bypasses are diagnostic only and
never change the shipped renderer's quality settings.

## Structure

- `src/main/java/dev/lexawhatt/astraengine/`: common mod code.
- `src/main/java/dev/lexawhatt/astraengine/client/`: client code.
- `src/main/java/dev/lexawhatt/astraengine/api/`: public server APIs and immutable visual values.
- `src/main/resources/assets/astraengine/shaders/`: procedural GLSL background.
- `src/main/resources/data/astraengine/`: persistent void dimensions.
- `src/test/` and `src/verification/`: model and game verification.
- `src/main/templates/META-INF/neoforge.mods.toml`: mod metadata.
- `gradle.properties`: name, author, version, and dependencies.
- `Workflow/`: local working notes; this directory is Git-ignored.

Integration contracts: [server API](docs/API.md) and [client rendering](docs/RENDERING.md).
The initial API is not yet stable.

The project is based on the NeoForge MDK; its template license is retained in
`TEMPLATE_LICENSE.txt`. Minecraft names use official Mojang mappings and Parchment.

[NeoForge 1.21.1 documentation](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
· [Mojang mappings license](https://github.com/NeoForged/NeoForm/blob/main/Mojang.md)

Free-camera/HDR and solar-event audio checks:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-camera-run -PverifyPhase=camera
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-camera-run -PverifyPhase=camera-restart -PverifyGraphics=fabulous
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-audio-run -PverifyPhase=audio
```

The audio fixture observes real Minecraft playback requests and decoded channels,
checking a single impact and cleanup of its own sounds. Original Ogg assets are
created by the development script `tools/audio/synthesize_solar.py`; Python,
NumPy, and ffmpeg are not required to play the mod.

Mars material versions, old-save preservation and thin dust atmosphere are documented in
[Mars surface and atmosphere](docs/MARS_SURFACE.md).
