# Cosmos, map, and Rocket mode

AstraEngine renders astronomical bodies with shaders around a virtual camera.
In Rocket mode, the player occupies the small empty `astraengine:flight` world,
while the virtual camera's position within a system is stored separately in
meters. The initial system is our Sun and eight planets, with physical radii
and orbits at 1:1 scale. Other systems are reproduced from a seed and sector
coordinates.

## First flight

1. In a loaded world, press **R**. Once the empty world is ready, the camera
   appears near Earth if this is the player's first flight.
2. Use the mouse to look around without a pitch limit, and **Q / E** to roll.
   **WASD** and **Space / Shift** move along the camera's local axes; **B** stops.
   The mouse wheel adjusts the selected speed.
3. Press **M**, select `Saturn` in the current-system list, and click
   **Approach body**. The camera aims at Saturn and flies smoothly to a viewing
   distance that shows its rings; the planet's physical size stays unchanged.
4. Press **C** or **Scan nearby systems** on the map. Under **Discovered systems**,
   select a discovered system and click **Jump to system**.
5. **R** or **Leave Rocket mode** returns to the original location in the real world.

These controls are available to ordinary players on an AstraEngine server.
Planetary landing is not implemented: Approach body selects an observation point
in shader space. This slice does not create block terrain, walkable surfaces,
or structures on procedural planets.

## Controls

| Action | Default |
| --- | --- |
| Enter / leave Rocket mode | R |
| Open map | M |
| Close map | M / Escape / Close |
| Scan neighboring systems | C |
| Yaw / pitch | Mouse |
| Move forward / sideways relative to camera | W, S / A, D |
| Move up / down | Space / Shift |
| Roll | Q / E |
| Stop / cancel approach | Hold B |
| Multiply / divide speed by 1.5 | Wheel or + / - during flight |
| Map zoom | Wheel over the map |

Keys can be remapped in Minecraft's control settings. Flight uses first-person
view and restores the previous camera mode on exit. This is currently an
inspection free camera: rotation crosses the poles, roll is controlled manually,
and movement follows all three rotated camera axes. Q/E do not drop items or
open the inventory during flight.

In manual flight, releasing movement keys or pressing B immediately sets server
velocity to zero. Opening a screen or losing focus sends a stop request. Camera
rotation is smoothed using frame time; movement has no inertial drift. An inertial
ship mode remains future work. Gravity, fuel, and relativistic dynamics are not
modeled.

Initial speed is **100 m/s**. One wheel step multiplies or divides it by **1.5**;
the supported range is **1 m/s to 10 AU/s**. The HUD shows actual and selected
speed. Fractional wheel input accumulates; long bursts apply at most one step
per four client ticks. Map approach provides a convenient way to reach objects
across interplanetary distances.

The server confines movement to a sphere of radius **4096 AU** around the current
system center. Swept movement checks stop the camera before a body even at high
speed. The protection radius is `1.03 * body radius + 10 km`; this is a navigation
barrier, not a collision with a voxel surface.

Client commands provide the main actions and precise settings:

```text
/astra-flight
/astra-flight map
/astra-flight scan
/astra-flight smoothness 0.72
/astra-flight exposure 1.0
/astra-render quality balanced
```

`smoothness` accepts `0..0.95`: zero gives direct camera response, while higher
values increase smoothing. `exposure` accepts `0.1..4`. The map's Smoothing and
Exposure buttons cycle through several values. These visual settings belong to
the client session and do not change celestial parameters.
`/astra-render environment` selects ordinary Astra environments; Rocket mode uses
a separate astronomical sky. Exposure is shared with `/astra-render exposure`;
the Exposure button and flight command change the same value.

## Map and discoveries

**Current system** shows bodies and orbits from above in the XZ plane. A cross
marks the camera position. Select a body through its marker or the list; the list
and zoom are useful for tightly grouped inner planets. **Track selected body**
adds a target marker to the flight HUD. It appears while the target is in view
and shows distance to the body's center. **Approach body** starts automatic flight
to an observation point. Large symbolic map markers do not enlarge bodies in the
flight scene.

**Discovered systems** contains only systems discovered by this player, sorted
by distance from the current system. Scanning discovers the current sector and
all adjacent sectors, including diagonals: at most 27 positions per request.
Repeated scans do not duplicate discoveries. A new record knows only `sol`,
whose nine bodies are available on the local map. Visiting a discovered system
exposes all its bodies; individual planetary discovery is not implemented.

Consumer mods can [create custom systems](CELESTIAL_API.md) with persistent
planet, star, and black-hole definitions. These appear after the consumer grants
discovery to the player; nearby-sector scanning does not reveal custom content.
The same map and flight controls work for both custom and generated systems.

**Approach body** smoothly turns the camera toward the selected body, follows a
virtual route, and decelerates near the observation point. After the stationary
aiming interval, position changes throughout the travel phase; there is no final
teleport. The last part slows down to
make the approach visible at astronomical scale. Duration depends on distance
and the safe route, rather than the selected manual speed. The HUD shows remaining
simulation seconds. The initial aiming interval lasts 40 server ticks. Routes
are bounded to 72,000 ticks (one hour at 20 TPS); longer or obstructed routes
can be refused. Camera roll follows the route continuously, including passage
through a pole, rather than resetting to a world-up orientation at arrival.

Automatic guidance owns aiming and movement during approach. **B** cancels at
the current safe position and restores manual control; **R** leaves Rocket mode.
Opening the map does not cancel approach; its **Cancel approach** button stops
the route explicitly. Mouse and Q/E control return after
completion or cancellation. The planner checks bodies along the route; if it
cannot find a safe route, the request is rejected. A new obstruction during
flight stops the route without teleporting through the body. This is not a
surface landing.

**Jump to system** is available for a discovered system other than the current
one. It retains a separate void transit lasting **80 server ticks**, or four
seconds at 20 TPS. The system and observation point change at the end. During
approach or interstellar transit, navigation requests cannot start a second route.

Discoveries are personal: at most **256 systems per player** and **4096 player
records** in the world catalog. Once the list is full, scans do not evict prior
discoveries. The map also provides access to distant discovered systems; filtering
to nearby systems only and arbitrary map panning are not implemented. Zoom ranges
from `0.25..1000000`. A button can reduce Minecraft's GUI scale when the interface
is too small to fit the controls.

## Physical scale and visibility

Radii, semi-major axes, periods, eccentricities, and inclinations for the initial
Solar System are listed in the [parameter table with NASA sources](SOLAR_REFERENCE.md).
"1:1" means there is no gameplay size or distance factor; the measurements retain
the precision of the published tables. Orbits are independent Kepler ellipses
with selected initial phases, not ephemerides for today's planetary positions.
Moons and minor bodies are absent from the initial Rocket-mode system. The
procedural Moon in the Overworld sky belongs to a separate atmospheric renderer,
not to this catalog.

The Sun has an angular diameter of approximately **0.533 degrees** at 1 AU.
A distant planet can be smaller than a pixel even without distance culling.
A close view requires approaching it; Approach body does exactly that. Optical
halos and markers help locate and perceive objects without replacing the physical
radii of their discs.

Minecraft's chunk render distance does not clip the astronomical sky. The server
stores system coordinates in `double` meters and galactic coordinates separately
in light-years. Rendering subtracts the observer position from the body position
in `double`, then uploads a unit direction and `radius / distance` ratio to the
GPU. Minecraft geometry stays near the local origin, and the shader does not
have to resolve small objects using enormous `float` coordinates.

This does not draw the entire galaxy at once: each frame extracts at most
**12 bodies from the current system**. Distant stars and nebulae are a procedural
background, not an exact projection of every galactic catalog record. Interstellar
navigation uses the map. Catalog size and visible object counts do not imply
unlimited computation or unlimited render distance for ordinary blocks.

## Procedural generation and appearance

Generator version 1 uses the galaxy seed and a canonical sector ID. Results do
not depend on visit order. Each four-light-year sector contains one system;
`sol` is fixed at the origin. Template weights are **78% single-star, 18% binary,
2% black-hole, and 2% visual supernova-remnant systems**. These are gameplay
weights, not real astronomical frequencies. A new system has 2-9 planets, with
materials and rings determined by the same seed. Identity and unit details are
in the [generator reference](SOLAR_REFERENCE.md).

The shader draws stars at several scales, a galactic light band, dark dust lanes,
and colored nebulae. Surface materials include rocky bodies, oceans with land
and clouds, gas bands and storms, and ice patterns. Atmospheres highlight disc
edges. The star with the largest apparent size as seen from a planet determines
its lit side. A binary system uses one selected star for this purpose; systems
without stars use a fixed fallback light direction. Planets occlude their rings
and cast analytic shadows onto them. Solar glow includes granulation and a corona.

Earth has real dimensions and orbital parameters, but its appearance is
procedural: **its continents do not reproduce Earth's geographic map**.
Clouds, material rotation, luminosity, color, and ring extents are artistic.
The ice material also represents Uranus and Neptune; there is no separate detailed
ice-giant atmosphere model yet.

A black hole bends the background star field and images of more distant bodies.
Its layered accretion disc has a bright, hot inner region, dark bands, and smoothly
fading outer layers. The far side produces curved images above and below the
dark silhouette; viewing angle changes their shape, while rotation determines
brightness asymmetry. Nearly edge-on views retain a small visual disc thickness.
Bloom spreads its light without changing the object's physical radius.

To inspect one, select a discovered black-hole system on the map and use
**Jump to system**. **Approach body** for its primary places the camera at
24 horizon radii. Q/E provide rolled views of the disc. Compare the image with
and without glow using `/astra-render bloom false|true`.

**Lensing is an artistic analytic approximation**, without integration of
relativistic ray trajectories. One nearest black hole is used per frame; the
current generator creates at most one per system. Its visible shadow accounts
for light capture and is wider than the physical horizon, whose catalog radius
stays unchanged. Nearby bodies occlude the lens; real Minecraft blocks render
over the sky and are not gravitationally distorted. This is not a general solver
for multiple lenses or intersecting translucent rings. The supernova remnant is
a glowing procedural shell; its animation does not trigger an explosion, destroy
buildings, or complete gameplay progression. These visual catalog templates are
separate from resource evolution in the first API slice.

The Sun in `sol` has a separate [controlled evolution cycle](SOLAR_SKY.md), shared
with the Overworld sky. `/astra sun demo` gradually depletes a diagnostic resource,
then starts collapse, supernova, and a persistent remnant. This is a fictional
scenario, not a real prediction for the Sun. The healthy Sun retains its original
1:1 scale; envelope expansion and the subsequent outburst are artistic, without
changing the canonical radius or orbits. No automatic extraction occurs until
an operator starts the cycle. Sol's planetary illumination also follows the Sun's
visual luminosity; server block light remains unchanged.

Set quality with `/astra-render quality low|balanced|high`. The astronomical
shader uses 3, 4, or 5 procedural noise octaves respectively; low omits the finest
star layer. The body limit is the same at every setting. These settings bound
workload rather than guarantee FPS on any GPU. The sky renders into RGBA16F with
multilevel bloom and one final exposure transform. Low/balanced/high use 4/5/6
bloom levels. HDR controls and limits are described in the
[rendering documentation](RENDERING.md); full global illumination and physical
ray tracing are not implemented.

## State ownership and persistence

The server owns virtual position and velocity, the selected system, discoveries,
and transitions. The client sends bounded requests and control axes; the server
validates session ownership, sequence, and message rate. Starting, completing,
or cancelling approach changes the navigation epoch, as does interstellar
arrival: delayed controls from a prior epoch cannot overwrite the new heading.
The client interpolates received positions and camera orientation without creating
an independent trajectory. An open map does not pause the server.

The catalog is saved in the main world's `data/astraengine_exploration.dat`.
Format **v3** adds up to 64 immutable custom system definitions alongside quaternion
orientation, including roll, and speed in m/s. Readable v1/v2 records migrate while
retaining position, discoveries, and exact speed; malformed data is rejected.
Navigation snapshots use protocol v4, actions v2, controls v3, and custom
definitions v1, so client and server need matching mod versions. Only a player's
discovered custom definitions are sent, before navigation refers to them; client
resource reload retains them and logout clears them. The catalog contains format
and generator versions, a seed, custom definitions, a shared orbital clock, and personal navigation
records. An active approach route belongs to the current session: leaving ends
it, and reconnecting does not resume it automatically. Discoveries, system, and
the reached virtual position survive logout and restart. After interrupted flight,
login returns the player to the original real world; re-entering Rocket mode uses
the saved navigation record. An unreadable existing catalog is not replaced by
an empty one.

The shared orbital clock advances while at least one living player is in Rocket
mode; it does not accumulate absent or offline time. This is a shared visual
navigation clock, separate from each discovered system's resource evolution.
Extraction and stage state in the [first-slice API](API.md) remain a separate
contract. The diagnostic Sun state is stored in `data/astraengine_solar.dat`:
its clock advances only with a living Overworld observer or an active Rocket
pilot in `sol`. Pilots in other systems can advance the orbital clock without
advancing the solar supernova.

A procedural descriptor does not allocate a new dimension. The persistent
`alpha` and `beta` building worlds belong to the first slice; binding an arbitrary
discovered planet to a persistent world for landing and construction is not
implemented. Celestial overlap is approximately sorted by center distance, not
handled by a general intersecting-object tracer. Compatibility with third-party
shader packs and performance on other hardware need separate verification.

Related tools: [Overworld Sun and supernova](SOLAR_SKY.md),
[lighting and environments](RENDERING.md), and
[local scene and GLSL editors](EDITOR.md).
