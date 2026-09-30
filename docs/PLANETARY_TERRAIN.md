# Planetary terrain prototype

`astraengine:terrain_highlands` is a separate, permanent terrain development world.
It adds kilometer-scale relief and expanded vertical bounds without regenerating
the existing Overworld, Moon or Earth landing patches. It is an opt-in prototype,
not a completed replacement for Minecraft world generation or a walkable globe.

## Explore

With operator permission, switch to Creative and enter the mountain viewpoint:

```text
/gamemode creative
/execute in astraengine:terrain_highlands run tp @s -14839.5 1350 -3535.5 180 24
```

Enable Creative flight to remain above the peak. **F3** shows Longitude, Latitude
and Altitude in meters above the reference sphere. The same geographic display
is available on the existing Moon/Earth patches; ordinary worlds retain vanilla
coordinates. Reduced-debug mode does not expose geographic coordinates.

The highlands are not yet a map landing destination. Use Minecraft's dimension
teleport command to return to your chosen Overworld coordinates. Blocks placed or
removed here are ordinary saved Minecraft terrain and survive a restart.

## Scale and generation

| Property | Prototype value |
| --- | --- |
| Permanent dimension | `astraengine:terrain_highlands` |
| Generator | `astraengine:planetary_terrain` |
| Terrain algorithm | Version 1, seed `71826152767793` |
| Reference radius | 6,371,000 meters |
| Geographic anchor | 45 degrees north, 0 degrees longitude |
| Local patch | 65,536 meters square, with a border safety margin |
| Vertical block range | Y=-256 through Y=1791, inclusive |
| Reference sea level | Y=0 |
| Authored elevation envelope | -192 through +1536 meters |

One local block corresponds to one meter at the patch anchor. The gnomonic
projection retains Earth's reference radius; this does not bend Minecraft block
meshes or change its gravity. The field samples three-dimensional noise on the
sphere, so its geography is continuous across longitude wrap and at both poles.
This property of the sampler does **not** yet enable walking between world edges.

The field combines broad land variation, warped ridges and finer relief. Elevation
and latitude affect the approximate temperature used for surface snow; moisture
also influences surface material. Water fills ocean columns up to the reference
level. The distribution includes grass, exposed rock, snow, sand and ocean floor.
A sampled peak at X=-14840, Z=-3576 has first-air Y=1291. A 256-meter grid across
the patch measures about 1455.5 meters between its sampled extremes.

This is procedural authored geography, not measured Earth continents, a tectonic
simulation or hydraulic erosion. The prototype has no caves, rivers, structures,
vegetation or default ore population. Its biome weather remains a fixed host
biome; the temperature field currently selects materials only.

## Ownership and extension

`PlanetaryTerrain` is an immutable, worker-safe height/climate sampler. Minecraft
owns chunk scheduling, block storage, lighting and saving. The generator samples
each block column once and stores homogeneous deep-rock sections as single-state
palettes. Mixed surface and water sections retain ordinary block states. This
reduces redundant stone writes; it is not a measured frame-time improvement.

The saved generator codec pins the prototype seed and version. Unknown versions
or changed seeds fail loading, and startup rejects an incompatible generator or
dimension height. Existing `surface_patch` version-one definitions are unchanged.
Saved chunks are never regenerated on departure or resource reload.

Normal Minecraft/NeoForge biome feature decoration is inherited. Consumer mods
can add placed features through the host biome modification mechanisms to
`astraengine:terrain_highlands`; features must respect its height and reference
sea level. AstraEngine adds no SolarTech dependency, resource economy or alternate
plugin registry. The terrain profile remains a prototype rather than a frozen
general planet-generation API.

## Vertical limits and remaining work

Minecraft 1.21.1's ordinary dimension codec supports at most 4064 vertical blocks
within Y=-2032 through Y=2031. Packed positions, section serialization, lighting,
network packets and render sections also constrain taller worlds. The prototype
uses 2048 blocks; it does not remove those host limits. Mountains beyond this
envelope require a separate storage and coordinate design, not just a larger JSON
height value.

[Closed geographic tile identities](PLANETARY_GEOGRAPHY.md), polar neighbors and
saved topology metadata are implemented as the second terrain stage. Continuous
walking, authoritative block storage across local frames and larger vertical storage
remain subsequent development stages.
The highlands currently use the host sky and atmosphere. Shared orbital terrain,
altitude-dependent atmospheric integration and landing at arbitrary geographic
coordinates are not provided by this prototype.

Distant Horizons is an optional experiment described in
[renderer compatibility](COMPATIBILITY.md#distant-horizons-terrain-experiment).
Its LOD cache is separate from authoritative Minecraft block storage.

## Reproduce verification

Use a fresh disposable directory, never an existing player installation:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-terrain -PverifyPhase=terrain-create -PverifyGraphics=fabulous
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-terrain -PverifyPhase=terrain-restart -PverifyGraphics=fabulous
```

The first phase refuses an existing fixture world. The second requires the first
phase's completion marker, checkpoint and saved world, then verifies modified
blocks above one kilometer and below sea level. Captures include geographic F3.
Both phases must produce `verified-<phase>.txt` and a successful Gradle exit.

For DH, use another fresh directory, put the original pinned DH JAR in its `mods/`
folder, and run `terrain-dh` instead of `terrain-create`. The fixture uses a real
custom world, waits for LOD preparation, records API observations and saves same-pose
ON/OFF/ON captures. Inspect the images: a successful callback counter alone is not
proof of visible terrain. If Iris is installed, an active pack is required. Use
the complete stable stack in the compatibility guide; do not mix Sodium generations.
