# Seasons and the atmospheric sky

The default Overworld sky uses orbital position, axial tilt and observer latitude
to place the Sun. A year lasts **365 Minecraft days** by default. Each mean solar
day remains **24000 ticks**, or **20 minutes at 20 TPS**. Winter has a lower Sun,
later sunrise and earlier sunset; summer has longer daylight. Transitions are
continuous. The default observation latitude is **45 degrees north**, with a
**23.44-degree axial tilt** and orbital eccentricity **0.0167**.

In the **Astra Earth** preset, observer latitude and longitude come from the
server-bound planetary chart instead. Traveling east changes local solar time;
traveling north changes daylight length. Orbital date remains global. Polar faces
rotate light and stars into their actual local tangent axes. `/astra season status`
reports this local observer; the profile's `latitude` setting remains the fallback
for an ordinary Overworld. `AstraSky.snapshotAt` exposes the same geographic sample
on the server without advancing time or loading chunks.

The atmosphere has wavelength-dependent extinction and a bounded scattering
integral. Low sunlight warms the horizon and illuminates procedural cloud edges
and undersides. Cloud coverage and haze respond to weather and orbital season;
colors follow lighting rather than four fixed color filters. Minecraft retains
terrain, precipitation and its weather scheduler. The Earth preset applies its
physical climate temperature to snow/freezing; legacy biome behavior is unchanged. Seasonal
snow, foliage changes, crop rules and terrain shadows are separate features.

Clouds use a ray-marched 3D density field with eroded billows, finite thickness
and sun-path self-shadowing. Dense undersides remain darker than thin sunlit
rims. The same density field shadows scattering in the air below the clouds,
forming light shafts through gaps. The layer can be viewed from below, inside
and above; this is procedural optical transport, not a fluid/weather simulation.
Cloud illumination and reflected moonlight follow the diagnostic star's linear
luminosity: depletion and remnants dim them, while the actual supernova flash
briefly brightens them. Local block emission remains independent of the star.

## Try it

Use an Overworld with operator permission level 2. Set the time first, then select
a season: vanilla `/time set` changes the absolute date as well as the clock.

```text
/astra-render environment auto
/gamerule doDaylightCycle false
/weather clear
/time set 10000
/astra season set winter
/astra season status
```

At the same clock time, `/astra season set summer` raises the Sun and lengthens
the remaining daylight. Use `/gamerule doDaylightCycle true` to resume orbital
motion and rotation. The `set` commands select the northern equinox or solstice;
they adjust a saved orbital offset without changing world time or builds.

| Command | Persistent setting or result |
| --- | --- |
| `/astra season status` | Year, latitude, tilt, solar longitude, geometric daylight hours, altitude, pollution and Sun scale |
| `/astra season set spring` | Northern spring equinox |
| `/astra season set summer` | Northern summer solstice |
| `/astra season set autumn` | Northern autumn equinox |
| `/astra season set winter` | Northern winter solstice |
| `/astra season year 365` | Year length in 4..1000000 game days |
| `/astra season latitude 45` | Observation latitude in -90..90 degrees; negative is south |
| `/astra season tilt 23.44` | Axial tilt in 0..90 degrees |
| `/astra season pollution 0.02` | Background light pollution in 0..1 |
| `/astra season sun-size 3` | Apparent Overworld solar disk scale in 1..8 |

In a legacy Overworld, latitude is a world profile setting rather than a conversion
of block Z. In Astra Earth, the spherical chart supplies the actual coordinates. Southern latitudes reverse the northern seasonal pattern;
polar settings support polar day and night. Daylight hours in `status` use the
geometric solar center, excluding atmospheric refraction and display enlargement.
At the default latitude the solstices give approximately 8.57 and 15.43 hours out
of the 24-hour model day, corresponding to about 7.14 and 12.86 real minutes at
20 TPS. No timezone, civil calendar or leap-year model is implied.

On the bound Astra Earth preset, this calendar also drives the Sol orbital frame
used by flight and landing. Earth has the same orientation and Sun direction in
the geographic sky and in the orbital renderer. Signed orbital epochs and the
complete rotation travel with camera snapshots, so network interpolation cannot
mix two independent dates. Default 365 game days map to one canonical Earth
revolution; the physical descriptor sizes and ellipse stay unchanged. Legacy
Overworlds retain their previous independent navigation clock. See
[Earth travel behavior](EARTH_WORLD.md) for time-command recovery during flight.

## A more visible Sun

The default Overworld disk is **three times the physical angular diameter**, about
**1.6 degrees** at 1 AU, with an atmospheric aureole and HDR bloom. The larger
presentation scale was requested for visibility. `/astra season sun-size 1`
restores the physical angular size, approximately 0.533 degrees at 1 AU.
The orbital distance still changes the apparent size over the year.

This setting does not alter the Sun's radius in the celestial catalog, its size
in Rocket Mode, navigation distances, energy or the geometric day-length model.
The existing [diagnostic solar evolution](SOLAR_SKY.md) still controls the shared
photosphere, collapse and supernova. Bloom/exposure controls remain available
through `/astra-render`; cloud and atmospheric extinction apply before bloom.

## Night and light pollution

The star field rotates with the sidereal sky. Under dark skies the Milky Way has
an extended stellar band and dust structure. Rising daylight, clouds and light
pollution reduce its contrast. Set `/astra season pollution 0` for a dark background
or `1` for a strong urban-style glow. Nearby emitted block light adds a local
contribution: at most 75 already-loaded light samples are read every ten client
ticks and blended smoothly. This never loads chunks or changes block light.

The background setting represents distant settlement glow that the local samples
cannot infer. This is a bounded visual approximation, not a regional light
transport model. The Milky Way is a procedural sky material, not a catalog of
individually resolved astronomical stars. The Moon retains the host's eight-phase
cycle and approximate opposite-Sun motion; a separate lunar orbit is not modeled.

## Clock, persistence and compatibility

Minecraft's server-owned `dayTime` supplies the date and rotation. Sleeping skips
forward; `/time set` seeks the date, and `/time add` advances it. Freezing the
vanilla daylight cycle freezes both orbital and rotational presentation. There
is no extra scheduler or offline catch-up. Host ticking and empty-server pause
behavior remain Minecraft's responsibility. The independent diagnostic stellar
extraction still follows its existing occupied-only rules.

Settings live in Overworld `data/astraengine_sky.dat`, format 1. Existing worlds
receive defaults without changing their saved time or celestial identities.
Malformed existing settings are rejected rather than overwritten. Full immutable
settings synchronize on login and changes, survive client resource reload and
clear on disconnect. Clients use the existing vanilla time synchronization.

Seasonal visual skylight replaces only the sky contribution to Minecraft's
lightmap; enclosed block emission is preserved. This does not change server
light levels, mob spawning, daylight sensors, sleep eligibility or Minecraft's
internal day/night predicates. Those gameplay rules can therefore differ from
seasonal sunrise/sunset. Weather, water/lava fog, blindness and darkness retain
host ownership. This is an atmospheric sky, not a complete terrain shader pack.

Vanilla block clouds are suppressed while the custom sky is active. Minecraft's
**Clouds: OFF** also hides the procedural clouds. `/astra-render environment off`
or an unavailable sky program restores vanilla sky/clouds/fog/lightmap. Explicit
resource-defined Overworld profiles take precedence. Third-party shader-pack
compatibility and a universal frame-rate target have not been established.

## Volumetric clouds and light shafts

The legacy Overworld owns one procedural cloud slab from **Y=360 to Y=860**,
using one block as one meter for this visual. Astra Earth uses **1800 to 3200 meters
above sea level**; camera altitude includes the current storage band offset. Clouds
therefore remain at the same physical altitude when the host Y coordinate rebases.
The same atmosphere effects operate on every server-bound Earth chart. Cloud/air integration is bounded
at **16 km**. Horizontal density repeats continuously every **64 km**; wrapping
camera coordinates before float conversion preserves detail far from spawn.
Wind uses the host game clock, independent of the seasonal `dayTime` clock.
`/tick freeze` also freezes wind for repeatable comparisons.

```text
/astra-render shafts true
/astra-render cloud-cover 0.65
/astra-render quality balanced
/astra-render cloud-cover auto
```

These are connection-local presentation controls. `shafts false` removes the
cloud-shadowed air scattering while retaining volumetric clouds. Coverage is
`0..1`, or `auto` for weather/season input; Minecraft **Clouds: OFF** takes
precedence. Cloud cover is a density threshold control, not a promised percentage
of occupied sky. Controls do not change server weather or celestial state.

| Quality | Transport resolution | Total view samples | Sun-shadow samples |
| --- | --- | --- | --- |
| Low | One-sixth width/height | Up to 32 | Up to 4 |
| Balanced | Quarter width/height | Up to 48 | Up to 5 |
| High | Half width/height | Up to 64 | Up to 6 |

Cloud body, self-shadowing and shafts share a padded 64-cubed scalar-noise field.
A 528-by-528 RG8 atlas stores adjacent depth slices, requiring 544.5 KiB of texture
storage. Smooth interpolation replaces repeated corner hashes inside the ray loops.
The field is presentation data; it changes neither saved weather nor geography.
Its periodic billow pattern is shared by all three transport calculations.

Cloud and air segments share the view budget. Empty segments and opaque rays
terminate early. Spatial reconstruction filters sampling noise; there is no
history buffer or temporal reprojection. Fine edges can still shimmer, especially
at the horizon and at low quality. These limits bound work, not frame rate.

Cloud transport is composed into celestial HDR before bloom. A separate pass
clips air/cloud transport to the copied opaque world depth and reconstructs
edges conservatively. It does not paint over the hand or HUD. The host world's
color remains its existing display-color path, not a new HDR material renderer.
Opaque depth is captured before the Fabulous transparency resolve. Continental
Earth additionally supplies its private distant-terrain depth with the matching
projection, allowing clouds to obscure ground beyond loaded voxel chunks.
Camera skylight suppresses shafts in enclosed interiors. This is **not a terrain
shadow map**: mountains and offscreen structures do not cast complete volumetric
shadows, and mixed indoor/outdoor views remain approximate. Transparent surfaces
use the host's opaque depth rather than separate volumetric layers.

Owned attachments are recreated on resize/quality changes and released on
reload, disconnect or leaving the automatic Overworld. Registered shaders remain
Minecraft-owned. If the volume program or allocation is unavailable, the previous
layered sky-cloud approximation remains available. No new save format is involved.

The transport follows the emission/absorption integration described in
[NVIDIA's volume-rendering chapter](https://developer.nvidia.com/gpugems/gpugems/part-vi-beyond-triangles/chapter-39-volume-rendering-techniques).
Procedural density and cloud-lighting references include
[Nubis](https://advances.realtimerendering.com/s2017/index.html).

## Consumer API

`PlanetarySkyProfile`, `SkyEphemeris` and `SkySample` are immutable, pure model
contracts reusable for consumer planets. The current world adapter applies the
saved profile to the default Overworld only. It does not allocate planetary worlds.

```java
// Owning logical-server thread; the consumer owns permission policy.
PlanetarySkyProfile profile = AstraSky.profile(server);
AstraSky.configure(server, profile.withLatitudeDegrees(52).withSunSizeMultiplier(2));
SkySample sky = AstraSky.snapshot(server);

// Pure sampling; inputs are a host tick count and a fractional tick in [0, 1].
SkySample customView = SkyEphemeris.sample(profile, 6000L, 0.5);
```

Imports are `dev.lexawhatt.astraengine.api.AstraSky` and
`dev.lexawhatt.astraengine.sky.{PlanetarySkyProfile,SkyEphemeris,SkySample}`.
The constructor also accepts an orbital eccentricity in 0..0.95 and a mean-orbit
offset in `[0, yearDays)`. The semimajor axis is 1 AU with a fixed Earth-like
perihelion orientation. It is a game ephemeris, not an arbitrary N-body model.
`AstraSky.configure` rejects invalid/off-thread input, returns false for unchanged
settings and otherwise increments the revision and broadcasts. Normal world-save
ownership applies; return does not promise an immediate filesystem commit.

Local directions use Minecraft axes: +X east, +Y up, +Z south. Angles explicitly
name degrees or radians; distance is in AU. Season phase is true solar longitude
in turns, with 0/.25/.5/.75 at northern equinoxes/solstices. Sidereal angle is the
local hour angle of equatorial right ascension zero. Large signed tick counts are
reduced before floating-point conversion to preserve sub-day precision.

The geometry follows the altitude/hour-angle relationships described in
[NOAA's solar equations](https://gml.noaa.gov/grad/solcalc/solareqns.PDF).
Scattering/extinction concepts are described in
[Bruneton's atmosphere reference](https://github.com/ebruneton/precomputed_atmospheric_scattering).
Astra uses its own bounded approximation, without the reference's precomputed
multiple-scattering model or scientific accuracy claim.

## Verification entry points

```sh
./gradlew build
./gradlew runVerifyServer -PverifyServerDirectory=Workflow/verification/my-seasons-server
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-seasons -PverifyPhase=seasonal
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-seasons -PverifyPhase=seasonal-restart -PverifyGraphics=fabulous
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-solar-clouds -PverifyPhase=solar-clouds
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-volumetric -PverifyPhase=volumetric
```

The first native phase refuses to overwrite an existing fixture. It captures
four seasonal dawn/noon/dusk/afterglow/night views and tests saved settings,
local light pollution, weather, clock freeze/resume, Sun size, resource reload
and vanilla fallback. The second phase opens that fixture and verifies the exact
saved settings, revision, frozen clock and real blocks. Verification code is
excluded from the production JAR.

The separate `solar-clouds` phase requires a fresh fixture. It samples the real
server evolution model at six paused stages, with frozen cloud wind, a fixed
off-Sun camera, and paired cloud-on/off captures. It records image luminance and
checks flash response and post-event dimming. It is not a performance benchmark.

The fresh `volumetric` phase captures views below/inside/above the layer, paired
shafts on/off at fixed time, opaque walls/roofs, weather, night, stellar remnants,
quality changes, resource reload, resize and disabled paths. It records actual
presented-frame intervals and hardware/settings alongside image comparisons.
Those intervals include the host renderer, CPU work and presentation; they are
not isolated GPU timings or a general frame-rate guarantee.
