# Continental inspection worlds

Three opt-in worlds show coastlines, high mountains and deep ocean floor from one
new spherical height field. They preserve the existing Overworld, highlands and
Moon/Earth landing worlds. They are development views of a procedural planet,
not a finished walkable globe or a recreation of Earth's actual continents.

## Explore

With operator permission, enter Creative and enable flight:

```text
/gamemode creative
/execute in astraengine:continental_coast run tp @s 78.7 12 63 -51.3 22
/execute in astraengine:continental_alpine run tp @s 0.5 1600 40.5 180 24
/execute in astraengine:continental_abyss run tp @s 0.5 -647 8.5 180 28
```

Use one destination command at a time. The abyss viewpoint is underwater; night
vision helps inspect it. F3 displays longitude, latitude and physical altitude
above the planet's sea reference. Return with Minecraft's dimension teleport
command using safe coordinates in your Overworld.

These are ordinary persistent voxel worlds. Blocks and chest inventories remain
on return and after restarting. They are not yet map landing destinations.

## Geography and physical scale

The immutable `ContinentalTerrain` version-one field uses Earth's reference radius
of 6,371,000 meters. Broad warped land masses produce connected continents,
continental shelves, ocean slopes and abyssal plains. Coherent mountain belts
carry finer ridges; approximate latitude, altitude and moisture select surface
materials. The same direction and seed always produce the same sample, including
across longitude wrap and at the poles.

The authored global envelope is -7,000 to +10,000 meters. This is a mathematical
bound, not a promise that every seed reaches both extremes. It is authored
procedural geography, without tectonic simulation, hydraulic erosion, rivers,
caves, vegetation, structures or default ore population. Biome weather is fixed;
the field's climate currently controls surface materials only.

## Explicit altitude windows

Minecraft still stores a finite vertical range. Each world uses 4,064 blocks,
local Y=-2032 through 2031. A pinned origin converts local host Y to physical
altitude without compressing relief:

`physical altitude in meters = host Y + altitude origin`

| World | Altitude origin | Stored physical altitude range | Horizontal width |
| --- | ---: | ---: | ---: |
| `continental_coast` | 0 m | -2,032 to +2,031 m | 32,768 m |
| `continental_alpine` | +7,168 m | +5,136 to +9,199 m | 16,384 m |
| `continental_abyss` | -5,120 m | -7,152 to -3,089 m | 32,768 m |

Borders include a safety margin. Local horizontal distances use the existing
gnomonic patch mapping with a one-meter tangent scale at the anchor. Minecraft
meshes and gravity remain flat. An alpine F3 altitude around 8.7 km is physical
height above sea level; the host's smaller Y is its storage coordinate.

These windows are separate regions, not automatically connected height bands.
Flying through their upper or lower boundary does not load the next part of the
planet. Where terrain extends past the window it is clipped; water and rock
continue to the boundary. The abyss's open upper boundary is not the real ocean
surface. There is no invented bedrock cap at the window boundary. This does not remove
the host height limit or implement global traversal.

## Persistence and extension

The generator codec pins the field version and seed, region version and identity,
anchor, width and altitude origin. Unknown or altered definitions fail rather
than silently loading different geography. Host workers generate chunks;
Minecraft owns storage, lighting and saving. No generation runs on the render
thread. Existing saved blocks remain authoritative after edits.

Normal biome feature decoration is inherited. Consumers can add placed features
to the three namespaced continental biomes through NeoForge's host mechanisms.
Feature positions must use local host Y and respect each altitude window; global
sea level may be outside that window. AstraEngine defines no SolarTech materials,
ore economy or replacement plugin framework.

These inspection dimensions retain the host sky, fog and weather; their physical
altitude does not yet drive an atmospheric density model. The shared spherical
field does not replace the orbital planet material or provide arbitrary landings. Distant Horizons remains optional; its
existing bounded [terrain experiment](COMPATIBILITY.md#distant-horizons-terrain-experiment)
does not qualify these new dimensions at every distance or with every shader pack.
The separate [ocean horizon calibration](PLANETARY_HORIZON.md) remains the current
physical-curvature reference.

## Reproduce verification

Use a new disposable profile for creation and reuse only that profile for restart:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-continents -PverifyPhase=continental-create
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-continents -PverifyPhase=continental-restart -PverifyGraphics=fabulous
```

The fixture visits all three windows, captures geographic F3 and terrain, checks
resource reload, and compares saved generator definitions, blocks, full chest
inventories and the initial player pose across processes. Its abyss captures use
night vision solely for inspection. Model and GameTest checks separately exercise
spherical continuity, altitude conversion, codecs and real generated columns.
This is functional verification, not an FPS or unlimited-height benchmark.
