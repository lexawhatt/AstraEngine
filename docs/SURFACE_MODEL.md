# Planet-fixed surface model

The first planetary surfaces are permanent, bounded patches on Sol's Moon and
Earth. They have separate world identities; Earth does not replace or reinterpret
the Overworld. `SurfaceDefinition` binds each patch to the existing immutable Sol
body. It does not create worlds, load chunks, tick time, synchronize state, or
authorize travel. Those responsibilities belong to the logical server integration.

This is a local landing foundation, not continuous traversal around a voxel globe.
Both patches cover local X/Z from -2048 to +2048 meters. The generator samples
block centers and leaves columns outside that bound empty. A world border and
arrival authorization remain server responsibilities. Surface geography is authored
game terrain, not a reconstruction of measured lunar terrain or Earth's continents.

## Identity and compatibility

| Binding | Geography version | Seed | Radius | Anchor |
| --- | --- | --- | --- | --- |
| `sol` / `moon` | 1 | `0x4D4F4F4E` | 1,737,400 m | Latitude 0, longitude 0 |
| `sol` / `earth` | 1 | `0x45415254` | 6,371,000 m | Latitude 0, longitude 0 |

The bindings, anchors, seed and geography algorithm are pinned. Their constructors
reject altered version-one definitions. A generator codec must retain these values
and reject unsupported versions instead of quietly generating different terrain.
`SurfaceDefinition.find(systemId, bodyId)` returns empty for every other binding,
including custom systems with a body named `earth`. Existing celestial descriptors,
parent orbits, player worlds and saved flight positions remain separate data.

`SurfaceDefinition.byBody` resolves the two supported Sol bodies or throws.
Definitions and samples are immutable and contain no Minecraft references, so pure
calculations can run on either side or a generation worker. The server still owns
the selected binding, simulation time, route, dimension and player transition.

## Coordinate and orientation contract

Body-fixed positions and system-local positions are double meters. Latitude is
north-positive, longitude east-positive, and angles are radians:

```text
normal(latitude, longitude) =
    (cos(latitude) * cos(longitude), sin(latitude), -cos(latitude) * sin(longitude))
bodyPoint = normal * (bodyRadius + altitudeMeters)
latitude = atan2(bodyPoint.y, hypot(bodyPoint.x, bodyPoint.z))
longitude = atan2(-bodyPoint.z, bodyPoint.x), canonicalized to [-pi, pi)
```

The center has no geographic position. Exact poles use longitude zero when
inverting a Cartesian vector. Near-pole calculations use `atan2`, preserving the
small horizontal component.

Local Minecraft +X is east, +Y is up and +Z is south. Let A be the anchor normal,
E its east vector and S its south vector. The gnomonic patch projection is:

```text
normal(x, z) = normalize(A * bodyRadius + E * x + S * z)
bodyPoint(local) = normal(local.x, local.z) * (bodyRadius + local.y - 64)
local.x = bodyRadius * dot(bodyPoint, E) / dot(bodyPoint, A)
local.z = bodyRadius * dot(bodyPoint, S) / dot(bodyPoint, A)
local.y = 64 + length(bodyPoint) - bodyRadius
```

The inverse requires the anchor's open hemisphere. The mathematical transform also
works outside the playable square; `patch.contains` determines actual patch
membership. One local block has a one-meter tangent metric at the anchor, with
small bounded projection distortion away from it. This does not change Minecraft
gravity or bend its block meshes.

`SurfacePatch.orientationAt(x,z)` maps the local east/up/south basis into body-fixed
space at that column. Its direction and orientation helpers provide both inverse
transforms without converting a quaternion through host yaw/pitch. Orientation
retains roll and remains valid across camera pole crossings.

`BodyFixedFrame.of(system, body, orbitalSeconds, spinRadians)` resolves the complete
parent chain through `system.positionAt`. Its rotation is
`Rx(axialTilt) * Ry(-spin)`, exactly the inverse of the orbital material's
tilt-then-spin sampling transform. Its point methods include translation; direction
methods do not. Relative velocity transforms contain no automatic orbital or spin
transport velocity. This is appropriate for the current free-camera handoff; a
future inertial vehicle requires an explicit velocity convention.

`definition.frame(system, orbitalSeconds, clockTicks)` combines the pinned spin and
body position. A ground camera follows:

```text
systemPosition = frame.toSystemPoint(patch.toBody(localCameraPosition))
systemOrientation = frame.toSystemOrientation(
    patch.toBodyOrientation(localCameraPosition.x, localCameraPosition.z, localOrientation))
```

Subtract system positions in double precision before narrowing for the GPU.

## Time and surface rotation

`SurfaceTime.compatibility()` keeps the existing physical ephemeris rate:
0.05 orbital seconds per occupied server tick. `rebase(tick, newRate)` can establish
an explicit alternative rate without changing any body's phase at that tick. The
model does not enable such a rate change automatically. It owns no second clock;
absence, offline time, synchronization and persistence remain server policy.

Geography version one introduces a new surface orientation profile for Earth and
Moon. The preceding orbital material used an artistic 24-hour texture turn for
both. At an existing nonzero clock, the new material orientation can therefore
differ on first upgrade. There were no prior versioned surface patches to rotate
or regenerate. Body centers, parent phases and saved flight coordinates remain
unchanged. This addition is not a claim that the old texture phase was migrated;
any future change to an existing persisted surface profile needs an explicit
phase-preserving epoch instead.

Earth spin is a 24,000-tick mean solar turn plus its physical mean orbital
increment. Moon spin uses its physical mean orbital rate. These are analytic
presentation rotations, not measured libration or attitude ephemerides. Earth's
surface year initially follows its actual catalog orbital phase. Consequently it
does **not** have the Overworld's independent 365-game-day calendar. No orbital
period, initial phase or Overworld day is silently reset to conceal this difference.

`SurfaceRotation` separately supports a pinned phase/rate and a phase-preserving
rebase, including retrograde rates. Both time helpers are pure values suitable for
an explicitly versioned future profile; they do not introduce mutable global state.

## Shared geography and GLSL mirror

`SurfaceGeography.sample(bodyFixedNormal)` normalizes the direction, casts its
components to float and evaluates a spherical field. There is no longitude seam.
`Kind.MOON` is ordinal 0; `Kind.EARTH` is ordinal 1. The renderer uploads ordinal
plus one, reserving GPU discriminator zero for no surface binding. `shaderSeed()`
folds the long seed as `(int)(seed ^ (seed >>> 32))`. Upload it as an integer/uint bit pattern.
The low 24 output hash bits convert exactly to float:

```text
uint h = uint(x)*0x8DA6B343u ^ uint(y)*0xD8163841u ^ uint(z)*0xCB1AB31Fu ^ seed
h ^= h >> 16; h *= 0x7FEB352Du
h ^= h >> 15; h *= 0x846CA68Bu
h ^= h >> 16
hash = float(h & 0xFFFFFFu) / 16777216.0
```

All arithmetic in the hash wraps to 32 bits. Three-dimensional value noise uses
floor-cell indices, `f*f*(3-2*f)` per fractional coordinate and trilinear
interpolation in X, then Y, then Z. Java's interpolation is `a+(b-a)*f`; a shader
mirror should preserve this evaluation order. The version-one field is:

| Body | Field, before final clamp |
| --- | --- |
| Moon | `18 + 40*(noise(n*8,seed)-.5) + 8*(noise(n*4096,seed^0x71E1)-.5) + craters(n*1600,seed^0xC4A7)` |
| Earth | `320*(noise(n*3.1,seed)-noise((3.1,0,0),seed)) + 24*(noise(n*8192,seed^0x71E1)-.5) + 6*(noise(n*32768,seed^0xB135)-.5)` |

The Earth broad coast passes through the fixed anchor; the higher frequencies
provide terrain variation within the patch. The lunar crater field evaluates all
27 neighboring cells in X/Y/Z order. Each feature center is
`cell+.15+.7*hash(cell,key)` with keys `seed`, `seed^0x51A3` and `seed^0xA73D` for
X/Y/Z. Its radius is `.35+.25*hash(cell,seed^0x3C17)`. With `q=distance/radius`,
each crater contributes:

```text
bowl = max(0, 1-q*q)
rim = max(0, 1-abs(q-1)/.22)
heightContribution = -30*bowl*bowl + 10*rim*rim
```

Clamp the result to [-48,112] meters. Earth columns below zero are ocean; Moon
never receives water. Material thresholds are defined in the Java sampler and
can drive the orbital material's land/water palette. The Java sampler is the
authority for saved voxels. A GLSL mirror is numerically comparable, not a promise
of bit-identical hardware float/FMA behavior. The orbital renderer can filter
unresolved high-frequency relief by pixel footprint. The exact shared field and
reference probes below correspond to a zero filtering footprint.

These exact Java reference elevations help detect changed hashing or coordinate
conventions before visual verification:

| Body-fixed direction | Moon meters | Earth meters |
| --- | --- | --- |
| `(1,0,0)` | 1.3091201782226562 | 8.991991996765137 |
| `(0,1,0)` | 26.8975772857666 | 112 |
| `(0,0,1)` | 1.459272861480713 | 32.47537612915039 |
| `normalize(1,2,-3)` | 20.83668327331543 | 112 |

The generator samples columns at `(blockX+.5, blockZ+.5)`. Solid first-air height
is `floor(64+heightMeters)`: fill solid blocks below that value. Ocean water fills
below Y=64, so its highest block is 63. The world-surface heightmap is the maximum
of solid first-air and ocean first-air; the ocean-floor heightmap retains solid
first-air. A dry arrival uses the solid first-air height, adding the actual player
eye height only for the virtual camera endpoint.

## Bounded arrival route

`SurfaceApproach` stores two body-fixed endpoints and samples a 480-tick route.
Descent first aligns along a great circle for 240 ticks, then descends radially
for 240 ticks. Alignment clearance is at least 10 km above nominal radius and at
least the higher endpoint radius. The antipodal case uses a stable perpendicular
axis. Ascent reverses the same route, climbing before alignment. Both endpoints
are preserved exactly; elapsed time outside the route is rejected.

Radial descent interpolates logarithmic remaining clearance, with distances in
meters and `t` the normalized 240-tick radial phase:

```text
remaining = 32 * expm1(log1p((clearanceRadius - finalRadius) / 32) * (1 - smoothstep(t)))
radius = finalRadius + remaining
```

Both endpoint velocities approach zero. The logarithmic scale leaves the final
seconds for human-scale altitude: from four Earth or Moon radii, the last full
tick moves less than 0.1 m and the final one-eighth tick less than 1 mm. The radial
midpoint is tens of kilometers above the destination, with the final three seconds
starting below 250 m. Ascent is the exact time reversal. Endpoints outside the
existing 4096-AU local navigation envelope are rejected, keeping `log1p`/`expm1`
arithmetic finite without changing the separate global manual-flight bounds.

The route never cuts the body as a chord. A shared terrain elevation below nominal
radius permits the final negative-altitude shell, bounded by the geography floor.
Only the owning server arrival may suspend the target body's ordinary free-flight
clearance rule. Other-body collision checks, a verified dry endpoint, dimension
readiness and failure recovery remain mandatory integration responsibilities.

`orientationAt(elapsedTicks, startBody, endBody)` guides the camera toward the body
and local horizon rather than adopting the ground view while still high above it.
Its downward pitch is 15 degrees plus the geometric horizon dip
`acos(clamp(radius/distance,0,1))`, capped at 85 degrees. The endpoint heading is
projected onto its tangent plane and parallel-transported along the route. This
avoids a 180-degree heading flip when a direct projection at an intermediate
position would vanish. A radial endpoint view uses its up vector as the stable
heading reference.

The first and last 60 ticks acquire/release guidance using fixed anchors at ticks
60 and 420. Fixed anchors prevent a moving quaternion interpolation target from
switching its shortest arc halfway through a blend. The supplied start/end
orientations remain exact, including roll; the caller supplies an upright walking
view for a ground commit. Tests sample polar and antipodal routes every one-eighth
tick, checking angular changes below six degrees per tick and visible body framing
during settled high-altitude guidance. This bound describes those tested routes,
not a universal flight-camera angular-speed setting.

Pure tests cover coordinate/orientation inverses, parent-relative positions,
shader transform agreement, pinned geography and visible patch relief, water/land
coverage, explicit time rebasing and reversible nonintersecting routes. Native
terrain, renderer, transition and restart evidence is separate from these tests.
