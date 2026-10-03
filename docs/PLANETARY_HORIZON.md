# Physical-scale horizon calibration

AstraEngine includes an opt-in ocean calibration world for its first spherical
horizon prototype. It renders a smooth sea with Earth's reference radius,
6,371,000 meters, and three fixed measuring towers. Raising the camera reveals
more of the distant surface; water hides distant targets from the bottom upward.
This world is separate from the existing Overworld, highlands and landing patches.

## Enter and compare

With operator permission, use Creative flight in the new world:

```text
/gamemode creative
/execute in astraengine:horizon_ocean run tp @s 0.5 64.08 -8.5 0 0
```

Enable flight to remain above the water. The supplied feet height places a
standing player's eye approximately 1.7 meters above the nominal sea reference
Y=64, or about 1.81 meters above the visible source-water surface. Face south (yaw 0). The orange/white visual towers are approximately 12, 30
and 60 km away, 120 meters tall and 200 meters wide. They are diagnostic targets,
without block collision or gameplay interaction. Blocks you place in this world
are ordinary persistent Minecraft blocks.

Raise the camera using these examples:

```text
/execute in astraengine:horizon_ocean run tp @s 0.5 162.38 -8.5 0 0
/execute in astraengine:horizon_ocean run tp @s 0.5 1062.38 -8.5 0 0
/execute in astraengine:horizon_ocean run tp @s 0.5 100062.38 -8.5 0 12
```

These give approximately 100 m, 1 km and 100 km eye altitude. Flying at 100 km
does not extend the world's block storage height. Return with Minecraft's
`/execute in minecraft:overworld run tp @s ...` using your chosen safe coordinates.

Local presentation controls:

```text
/astra-render horizon
/astra-render horizon towers false
/astra-render horizon flat-comparison true
/astra-render horizon flat-comparison false
/astra-render horizon enabled false
/astra-render horizon enabled true
```

The flat comparison changes presentation only. Controls reset on disconnect and
survive resource reload. Use the automatic environment profile for this scene;
a manually forced Astra environment is a separate diagnostic override.

## Geometry and ownership

The distant ocean is analytic, independent of chunk render distance. Eye altitude
is kept separate from planetary radius to preserve precision near the surface.
Fixed tower positions use the existing patch-to-body mapping and camera-relative
double subtraction before conversion to GPU floats. The pure `PlanetaryHorizon`
calculations also support other radii, but this particular scene is Earth-sized.

| Eye altitude | Smooth-sphere sight distance to horizon |
| --- | ---: |
| 1.7 m | 4.65 km |
| 100 m | 35.70 km |
| 1 km | 112.88 km |
| 100 km | 1133.23 km |

These distances exclude terrain and atmospheric refraction. The sky is a simple
calibration atmosphere, not the seasonal Overworld atmosphere. The pass writes
color at `AFTER_SKY`, before nearby blocks, and writes no
host depth. Minecraft owns shader reload/disposal. It allocates no private render
targets and does not replace third-party programs.

## Current limits

- Nearby host blocks, water and collision remain flat. This is a
  distant spherical reference, not complete terrain reprojection or globe walking.
- The nominal sea reference remains Y=64. The analytic water now follows the
  source-water height Y=63+8/9 (the host applies a further 0.001 m raster epsilon).
  The calibration ocean and host fog share a marine color. Native water materials
  and lighting still differ, so this does not promise an exact material join.
- Active Iris shader packs retain their own sky and terrain; the spherical
  calibration pass is disabled. This fallback does not add curved terrain,
  shadows, reflections or temporal-history integration to a shader pack.
- Underwater views, blindness/darkness, missing shader resources, unsupported
  dimensions, or cameras outside the bounded patch use the host sky.
- No existing body radii, saved world definitions, terrain or discoveries change.
  The scene does not supply terrain-to-orbit geography or unlimited block height.

See [terrain development](PLANETARY_TERRAIN.md), [geographic frames](SURFACE_FRAMES.md)
and [optional renderer compatibility](COMPATIBILITY.md).

## Reproduce verification

Use fresh disposable directories and the original pinned optional artifacts from
the compatibility guide. The verification source set is excluded from the JAR.

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-horizon -PverifyPhase=horizon-create
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-horizon -PverifyPhase=horizon-restart -PverifyGraphics=fabulous
```

Creation refuses an existing fixture world. Restart requires its completion marker
and compares the exact saved pose, marker and complete chest inventory. The first
phase captures eye heights 1.7 m, 100 m, 1 km and 100 km, flat/curved comparisons,
FOV30 close views, resource reload, resize, and disabled/out-of-dimension controls.
TSV records both nominal and visible-water eye altitude/radius, plus the actual
camera and projection; Creative flight modifies effective
FOV, so the option value alone is not a projection measurement.

For the stable Iris stack with an active pack, use another fresh profile and run
`horizon-iris`. It requires zero horizon draws and unchanged Iris configuration
through toggles/reload. Inspect the captures as well as completion markers and the
Gradle result; a callback count alone does not demonstrate visible composition.
