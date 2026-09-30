# AstraEngine visual lighting and environments

Client presentation for Minecraft 1.21.1 / NeoForge 21.1.252. This layer renders
shader sky bodies and lights real local geometry. It never changes vanilla
block light, mob spawning, stellar resources or the system's saved evolution.

The [in-game scene and GLSL editor](EDITOR.md) authors local analytic objects,
lights and a user-compiled final fragment effect. Its shape pass precedes opaque
lighting; its optional custom GLSL pass follows final composition. These are
client drafts, separate from authoritative stellar descriptors. The ordinary
pass order below applies without an active Iris shader pack;
[shader-pack ownership](COMPATIBILITY.md) changes sky, lighting and overlay stages.

The physical-scale [Rocket cosmos renderer](COSMOS.md) owns the flight dimension's
sky independently. It shares the quality setting, uses meter-based descriptors
and shares celestial exposure and bloom controls with the Overworld sky. The local
geometry-lighting pipeline and analytic scene shapes are bypassed there. The
explicit GLSL final-effect editor remains available. Environment overrides do
not replace the Rocket sky.

## Try it

In a creative test world, use the client commands:

```text
/astra-render environment planet
/astra-render flashlight true
/astra-render quality balanced
/astra-render status
```

Walk into an enclosed, unlit space: the planetary profile darkens the view;
the flashlight follows the camera and illuminates the visible wall/floor with a
soft cone. `environment auto` restores automatic environment selection;
`environment off` disables Astra sky/lighting/composition. In the normal Overworld,
auto now uses the dedicated [solar sky](SOLAR_SKY.md): a [seasonal atmosphere](SEASONS.md), procedural clouds and Moon, a more visible
Overworld Sun, and the shared server-authored Sol event.
`environment off` restores vanilla Overworld sky/fog/lightmap behavior; it does
not pause the server's solar cycle. Explicit named previews remain available.

With server command permission, `/time set 6000`, `/time set 11800` and
`/time set 18000` show noon, sunset and night. The same sun direction drives the
sky, directional lighting and the ringed planet's shadow. These commands change
Minecraft's world time, not the engine's occupied-system evolution counter.
Planetary preview changes presentation of the current world; it does not generate
a planet surface or move the player into a new world.

Other session-local controls:

```text
/astra-render flashlight false
/astra-render lighting false
/astra-render bloom false
/astra-render quality low
/astra-render quality high
/astra-render environment astraengine:space
```

The quality budgets include the built-in sun and flashlight:

| Quality | Selected lights per frame | Contact-shadow samples per light |
| --- | ---: | ---: |
| low | 4 | 0 |
| balanced (default) | 8 | 8 |
| high | 16 | 16 |

Up to 256 submissions are considered per frame. IDs are unique within the frame;
first submission wins duplicate IDs. Directional sources rank first; local
sources rank by intensity, range and camera distance, with IDs breaking ties.
A local source can illuminate visible geometry even when its range does not
reach the camera. Sources beyond the view radius plus their own range are culled.
Budgets bound work; they are not a promise of a particular frame rate.

## Spatial galaxies and regions

The [universe atlas](UNIVERSE.md) supplies immutable galaxy transforms and named
region geometry to the same celestial pass as the body renderer. Different
oriented spiral, elliptical and irregular galaxies coexist in one coordinate
space. Emission/absorbing nebulae, stellar clusters, remnant shells and quasar
regions use those descriptors instead of following the camera as sky decorations.
Nuclear stellar light and structured dust replace the earlier smooth core.

Shared galactic coordinates are light-years; local body projection stays in
camera-relative meters. Public atlas geometry is derived from the saved seed
and generation version, while visits/navigation remain server-owned. The
renderer does not spawn worlds, mutate the catalog or unlock travel. The
supermassive black hole remains a physical-scale local body. Unresolved stellar
emission is a bounded representation, not every individual star in the galaxy.

All nine galaxy bounds are considered. Low/balanced/high quality uses 12/20/28
samples for an intersected disk and 10/14/18 for its nuclear population. Named
regions are ranked by angular importance, faded at the selection boundary and
limited to 12/18/24; intersected volumes use 12/18/24 samples. Remnant shells use
two analytic surfaces, and quasar jets use an analytic transverse integral to
avoid separated sampling bands. Local statistical stars traverse at most 40
spatial cells. These bounds do not establish a frame-rate guarantee.

The unresolved quasar core fades out near its local system, where the physical
black hole and disk supply the close view. Jet integration excludes a launch
cavity and uses only forward ray segments; entering the nucleus does not turn
the distant core's glow into an all-sky white field.

A connection-owned cache queries at most 27 nearby system descriptors per
four-light-year cell and draws up to 24 actual catalog star points. Legacy
points use the absolute Sol-centered 64-light-year zone, fading over four
light-years at its boundary; outside it, the shared galaxy population supplies
points. Selection depends on absolute position, so rebasing a flight origin does
not replace the neighborhood. Named cluster density also applies outside the
main galactic disk. Unresolved light and statistical stars represent the rest
of the population. Volume composition is approximately ordered from far to
near; this is artistic radiative transfer, not a full physical light solver.

## Celestial HDR and bloom

Rocket cosmos and the automatic Overworld sky render into owned linear RGBA16F
attachments. A soft-knee bright extract feeds a 4/5/6-level downsample/upsample
pyramid at low/balanced/high quality. Composition applies exposure once, preserves
emission hue through a highlight shoulder, and converts the result for display.
Physical catalog radii stay unchanged. The Overworld has an explicit apparent
Sun scale (default 3); Rocket Mode retains physical angular sizes. Bloom is a
separate image-space optical effect.

```text
/astra-render bloom true
/astra-render bloom-strength 0.65
/astra-render bloom-threshold 1.0
/astra-render bloom-radius 0.65
/astra-render exposure 1.0
```

These are defaults. Strength accepts 0..4, threshold 0.1..16, radius 0..1, exposure
0.1..4. `/astra-render bloom false` disables glow while keeping the same HDR scene
and exposure, for a direct comparison. `/astra-flight exposure` and the map's
Exposure button update this same value. Controls are session-local.

Without an active Iris pack, the celestial pass finishes before opaque terrain
and the HUD, so foreground blocks normally occlude its glow and HUD text does
not bloom. With a pack active, cosmos and resource-profile skies use late
clear-depth composition in other dimensions; the pack owns the Overworld sky even
when an Astra environment preview is forced. Resource reload,
resize, quality changes and logout release/recreate owned attachments; Minecraft
owns registered programs. Missing shaders or failed HDR allocation fall back to
the direct celestial shader. Strong optical bloom may scatter accretion-disc light
into a black hole silhouette. It is not a mask preserving every black pixel.

This HDR path is specific to celestial rendering. The separate profile-based
opaque-world lighting/editor pipeline below still consumes Minecraft's LDR color;
it does not gain deferred materials, transparent relighting or GI from this change.

## Celestial lens and disk

CosmosRenderer extracts immutable camera-relative frames on the render thread.
CPU subtraction/normalization uses double meters. The shader receives direction,
physical radius/distance and center distance in units of the nearest black hole's
distance. A single analytic lens bends the procedural background and farther
celestial bodies; direct foreground sphere intersections protect nearer surfaces,
including a larger body whose center lies behind the lens. This is bounded to the
existing twelve-body frame. It is not a general multi-lens or transparency solver.

The black-hole material shares one layered emissive disk between its direct and
inclination-dependent lensed images. Orbital asymmetry follows the disk plane,
and finite visual thickness avoids the disappearing exact edge-on view. Physical
catalog radius remains the horizon radius; the larger apparent capture silhouette
is a presentation calculation. Near/inside-horizon diagnostic views have a finite
dark fallback, while server navigation still prevents normal body entry.

The lens uses Schwarzschild-inspired capture and disk scales, with a bounded
analytic deflection and finite-source image approximation. It does not integrate
null geodesics or claim scientific image accuracy. Ordinary Minecraft geometry,
hands and HUD are drawn later and are not refracted. Non-overlapping celestial
objects retain center-distance painter order outside the primary lens; intersecting
disks/rings do not have a general per-pixel transparency ordering solution.

Star footprints are computed before stochastic branching. Ring material filtering
uses an explicit angular footprint rather than derivatives inside intersection
branches. Interior sphere exits use the exit surface normal, and direction basis
fallbacks cover pole-aligned views. These controls bound numerical edge behavior;
they do not guarantee alias-free images at every distance or on every GPU.

## Profiles supplied by resources

Put a JSON resource at `assets/<namespace>/environments/<name>.json`. It becomes
`<namespace>:<name>` and appears in command suggestions. For automatic application
in a dimension `<namespace>:<path>`, give its profile the same ID. In the absence
of a matching dimension profile, Astra systems use `astraengine:space`, and other
worlds retain their vanilla environment, except for the dedicated automatic
Overworld solar sky described above. Transit retains the space fallback.
The Overworld sky hook yields to an explicit `minecraft:overworld` resource-profile
mapping in auto mode, including its solar fog/lightmap corrections. Explicit named
environment selections also preview their profile instead of the solar sky.

Example `assets/example/environments/ringed_world.json`:

```json
{
  "version": 1,
  "planetary": true,
  "ambient": 0.72,
  "sun_strength": 1.8,
  "exposure": 1.0,
  "bloom": 0.28,
  "cave_floor": 0.015,
  "rings": true,
  "ring_tilt": 0.5
}
```

`planetary` selects the day/night atmosphere and Minecraft day-time sun path;
false selects the space presentation. `ambient` scales the already shaded world
image (0..2), `sun_strength` scales additional sun lighting (0..8), `exposure`
controls final display exposure (0.1..4), and `bloom` controls bright-pass glow
(0..2). `cave_floor` (0..1) is the minimum multiplier for enclosed views.
`ring_tilt` (0.05..1) sets the ring plane's Y component before normalization;
it is a dimensionless artistic parameter, not an angle. Omitted values use the
built-in space or planet defaults. Nonfinite, wrong-type and out-of-range values
are rejected. This version has a fixed procedural ringed-planet appearance;
arbitrary body layout/material definitions are not yet a profile feature.

F3+T reloads profiles and shaders. Up to 256 profiles are supported. Profiles are
parsed off-thread and published together. One invalid profile preserves the
previous entire set and reports a specific error in the log. Removing a selected
custom profile returns that world to vanilla rendering until a valid selection
exists. Profiles contain presentation data only and do not create dimensions.

## Submit a light from another mod

Register the listener only from a physical-client entry point, on
`NeoForge.EVENT_BUS`. The event runs on the render thread once per rendered
Astra environment frame. Submit current lights each frame; do not keep the event,
collector or ClientLevel in a global cache. A consumer owns the persistent identity
and logical lifecycle of its machines/entities; this event only extracts visuals.

```java
NeoForge.EVENT_BUS.addListener((CollectSceneLightsEvent event) -> {
    if (!event.level().dimension().location().toString().equals("astraengine:alpha")) {
        return;
    }
    event.collector().add(SceneLight.point(
            "example:reactor_light",
            new LightVector(8.5, 83.0, 8.5),
            new LightVector(0.1, 0.6, 1.0),
            2.0f,
            16.0f));
});
```

The imports live in `dev.lexawhatt.astraengine.client.lighting`. These classes are
client API, not additions to the common/server `AstraSystems` API. Never register
this listener from common startup on a dedicated server.

`SceneLight` is immutable and validated. Position and range use world blocks;
RGB is a nonnegative source-color coefficient up to 4 per channel; intensity is
0..16. Source values are visual coefficients, not calibrated photometric units.
The current composite augments Minecraft's already shaded display color.
Directions are normalized automatically. A directional light points **toward**
the source. A spot direction points **outward from** its source. Spot inner/outer
half-angles are degrees, `0 <= inner < outer < 90`. Range is at most 256 blocks.
Set `contactShadows` false for sources that should skip screen-space shadowing.

`collector.add` returns whether the light is currently selected; a later stronger
candidate can displace it. Zero-intensity, duplicate, invisible-range and excessive
submissions return false. Invalid numeric input throws. The engine seals the
collector after dispatch; later mutation throws. The built-in flashlight is an
ordinary spot source at the camera. It has a 28-block range, 12/23-degree cone,
and no contact-shadow march because visible surfaces already lie on camera rays.

## Render ownership and limits

The automatic Overworld solar sky uses `DimensionSpecialEffects.renderSky`,
replacing the host celestial draw rather than adding a second Sun after it.
Minecraft retains precipitation, weather scheduling and terrain. Procedural
[volumetric clouds and shafts](SEASONS.md#volumetric-clouds-and-light-shafts) replace
the block-cloud draw while automatic sky is active. The registered Overworld effect
also adjusts visual fog and, when lighting is enabled, subtracts the diminished
sky contribution from the client lightmap while preserving the block-source
contribution. A bounded solar flash follows sky visibility and the Sun's height.
These changes do not alter server sky/block light, mob spawning or world time.
With an unavailable sky shader, the sky/fog/lightmap follow their vanilla paths.
With an active Iris pack, Astra yields Overworld sky, clouds, fog, lightmap and
aerial transport to the pack, including with a forced environment preview.
The server keeps its seasons and
stellar state, but the pack does not automatically consume them. Other mods
replacing the same Overworld effects need separate compatibility work.

The automatic Overworld first computes reduced-resolution cloud/air transport in
an owned RGBA16F target and composites it into the celestial HDR sky before bloom.
At `AFTER_LEVEL`, a second RGBA16F transport target integrates only up to copied
opaque scene depth, then composes with depth-aware reconstruction before the local
GLSL editor effect. Sky pixels are excluded from this second composition. The
renderer owns these two targets plus a full-size color/depth copy, preserves host
depth, and releases attachments on reload, resize, logout and deactivation.
No temporal history or offscreen terrain shadow map is maintained.

The following pass sequence applies to the resource-profile environment layer
and the first persistent Astra worlds when no Iris pack is active:

1. `AFTER_SKY`: a full-screen shader reconstructs view directions without camera
   translation. It draws stars, planets, sun/remnant, atmosphere and rings. A ray
   from each ring fragment toward the sun is intersected with the planet sphere,
   producing a geometric, moving shadow on the ring without entities or chunks.
2. `AFTER_BLOCK_ENTITIES`: copy color and depth to an owned target. Reconstruct
   positions/normals from depth and add directional/point/spot lighting to opaque
   visible surfaces. The pass samples copied attachments, never its output.
3. `AFTER_LEVEL`: copy scene color, run two half-resolution bloom passes, then
   compose exposure/glow into the main target. Hand and HUD render afterward.

Stages and reload registration use NeoForge's public hooks; signatures and order
were checked in the pinned local sources and the upstream
[render event](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/event/RenderLevelStageEvent.java)
and [reload event](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent.java).

The renderer owns three intermediate targets, recreates them on resize, matches
main depth/stencil format, and releases them on reload/logout or deactivation.
Minecraft owns registered shader disposal. Passes restore framebuffer, viewport,
shader/program, texture bindings and depth/cull/blend state. Vanilla's spectator
PostChain is not replaced. A shader-load failure disables its affected passes.

This is an additive visual-light layer, not a deferred material renderer or GI.
Normals come from visible depth. Contact shadows cannot see offscreen occluders
and may show silhouettes/thickness artifacts. Camera skylight approximates cave
exposure; mixed indoor/outdoor views need per-surface visibility for higher fidelity.
Transparent materials, first-person hands and gameplay block light are not relit
by the opaque pass. Existing block emission/night vision are already in the input
image; extreme darkness profiles also attenuate that input. Added illumination
is softly compressed to preserve texture detail on strongly lit surfaces. Bloom uses LDR source
color and cannot recover clipped HDR energy. The celestial ring shadow is analytic
and independent of these screen-space limitations.

The [Sodium/Iris compatibility guide](COMPATIBILITY.md) records the optional
integration and its exact version/pack evidence. Active packs own opaque-world
lighting and profile post-processing; outside the Overworld, Astra draws
resource-profile skies through a depth mask at `AFTER_LEVEL`, then consumer
ships/eligible local shapes and the explicit
GLSL editor effect. These late visuals are not pack materials or shadow casters.
Distant-terrain renderers, unlisted packs, arbitrary hardware and extreme scene
sizes require separate compatibility/performance verification. The current extension
surface is lights and environment profiles; arbitrary user-defined render-pass
graphs, shadow maps, voxel GI and planetary world generation remain future work.


## Consumer-supplied ship visuals

The [ship visual API](SHIP_RENDERING.md) supplies immutable geometry, materials and
transforms to one engine-owned renderer. It intersects cylinders, frustums and
boxes in GLSL and writes scene depth at `AFTER_BLOCK_ENTITIES`, before opaque
lighting. With an active Iris pack, collection and drawing move to `AFTER_LEVEL`
after pack composition and never run during shadow extraction. It also supports
bounded GUI preview and analytic visual picking.

Consumers submit frame snapshots through a client collection event; the renderer
has no construction catalog, engineering statistics, deployment entities or
persistent ship state. Consumer code owns authoritative movement, synchronization
and collisions. The same draw path runs in the physically bounded flight world.
Minecraft owns registered programs; AstraEngine owns preview/world-copy targets.
