# Overworld Sun and controlled supernova

In the ordinary Overworld, `/astra-render environment auto` enables the
AstraEngine sky: a round Sun, an atmosphere, sunsets, stars, and a procedural Moon.
It replaces the vanilla celestial bodies without adding a second, square Sun.
Minecraft retains control of precipitation, terrain and the saved day/night clock.

The [seasonal sky](SEASONS.md) now derives the solar path from a Kepler orbit,
axial rotation and observer latitude. The default year is 365 Minecraft days,
with 20-minute mean solar days at 20 TPS and shorter winter daylight.
Procedural clouds replace vanilla clouds while this sky is active.

The physical Sun has an angular diameter of approximately **0.533 degrees** at
1 AU, using the [Sol reference parameters](SOLAR_REFERENCE.md). At the author's
request, the default Overworld display scale is **3**, about **1.6 degrees**,
with a soft atmospheric aureole. `/astra season sun-size 1` restores physical
apparent size. This explicit presentation setting leaves the Sol catalog and
Rocket Mode unchanged. The Moon retains host phases and approximate opposite-Sun
motion without a separate lunar orbit.

Nothing depletes automatically. An operator can start a separate diagnostic cycle:
gradual resource extraction, stellar expansion and instability, collapse, a
supernova, and a stable remnant. **The forced solar supernova is fictional gameplay,
not a prediction of the Sun's real evolution.** Accelerated time, the expanding
envelope, color, brightness, and shock front are artistic parameters. Canonical
radii and orbits in the Sol catalog remain unchanged.

## Starting and controlling the cycle

Use a Creative Overworld with operator permission level 2. Look at the sky;
for a daytime scene, optionally set `/time set 6000` and `/weather clear`.

```text
/astra-render environment auto
/astra sun demo 60
/astra sun status
```

| Command | Action |
| --- | --- |
| `/astra sun demo` | Start a new cycle at full resource, with extraction over 60 active seconds |
| `/astra sun demo 10` | Start a new cycle with extraction over 10 active seconds |
| `/astra sun pause` | Pause extraction and all phase timers |
| `/astra sun resume` | Resume a previously started, unfinished cycle |
| `/astra sun reset` | Restore a healthy Sun at full resource and stop the cycle |
| `/astra sun status` | Show the phase, resource, extracted amount, clocks, and state counters |

The `demo` duration accepts whole numbers from **10..3600 seconds** and controls
only the extraction period. Another 4 seconds of collapse and 16 seconds of
supernova follow. At 20 TPS, an uninterrupted `demo 60` reaches the remnant after
80 seconds. Pauses, absent observers, and reduced TPS increase the real waiting
time. Repeating `demo` starts a new cycle at full resource; `resume` neither
starts an unscheduled healthy star nor revives a completed remnant. `reset`
changes only the diagnostic stellar state, not the time of day, weather, or builds.

A small Overworld panel shows the phase and percentage of resource remaining.
Hide it with the client command `/astra-sky hud false` and restore it with
`/astra-sky hud true`. This panel does not affect the server simulation.

## Phases and persistence

Capacity is **1,000,000 arbitrary units**, not joules or Forge Energy.
Over the configured number of ticks, the model gradually transfers resource from
`remaining` to `extracted`; their sum always equals the initial capacity.

| Phase | Condition or duration |
| --- | --- |
| STABLE | More than 65% resource remaining |
| DISTENDED | More than 20%, up to and including 65% remaining |
| CRITICAL | More than zero, up to and including 20% remaining |
| COLLAPSING | Zero resource; 80 active ticks |
| SUPERNOVA | The next 320 active ticks |
| REMNANT | Permanent remnant until an explicit `reset` or new `demo` |

The server owns this state. It advances only while the cycle is running and
there is at least one living player in the Overworld, on a bound Sol surface
patch, or an active Rocket Mode pilot in `sol`. A Rocket Mode player in another system does not keep this cycle
running. Without eligible observers, while the server is offline, or after
`pause`, neither extraction nor hazardous phase timers advance. Minecraft's
day/night cycle is independent: `/time set` does not seek through extraction or
the supernova.

The main world's save file **`data/astraengine_solar.dat`** stores format version
1, resource, clocks, phase, run mode, cycle number, and revision. Logout and restart
preserve the exact phase; offline time is not caught up. Invalid versions, missing
clocks, or inconsistent numbers are rejected. An existing file that cannot be
read is not replaced with a new healthy star.

Cycle and revision counters increase even on reset. Clients receive a full
snapshot on login, every five server ticks, and after diagnostic commands.
Interpolation smooths values already received; it does not run independent
client-side evolution. Resource reload must not restart the event.

Sol state is separate from [extraction in `alpha` and `beta`](API.md). The `sun`
commands do not provide FE, items, or materials, deduct energy from other mods'
machines, or destroy the world. This scenario does not implement SolarTech's
gameplay collectors or economy.

## Shared rendering and lighting

The Overworld and the Sun in [Rocket Mode](COSMOS.md) use the same state and
shared solar GLSL code. The photosphere, granulation, spots, corona, and bright
arcs change as the star depletes; pulsations intensify before collapse. A brief
flash follows, then expanding gas layers with irregular edges, knots and fine
filaments. Warm ejecta cools toward restrained red/blue emission and a dim mature
remnant. A bounded 6/8/10-sample volume integral at low/balanced/high quality
replaces the old flat ring-like shell. This is an artistic emission model, without
hydrodynamic simulation or spectrally resolved radiative transfer.
The flash age comes from the server phase: ordinary shader animation does not
repeat the explosion in a loop. Pausing the cycle freezes the ejecta structure;
seeded remnant systems use its mature material without a periodic expansion. In Rocket Mode, apparent size follows the
observer's actual distance; the envelope's angular size is bounded for nearby
cameras.

The Overworld atmosphere scales clouds and reflected moonlight by the same
linear stellar luminosity used for world skylight. After the initial flash,
the Overworld's solar-disc brightness boost fades for the ejecta, avoiding an
overexposed remnant above an otherwise dark world. The shared server phase and
the space-view material are unchanged.

As the star weakens, it changes the atmosphere, fog color, and client-side sky
light contribution on real blocks. Lightmap correction reduces the sky component
while preserving the separate contribution of block light sources: torches should
not fade with the star. The flash adds bounded lighting based on sky light and
the Sun's height above the horizon. This is visual only; server light levels, mob
spawning, and block-light logic remain unchanged.

Rain and thunderstorms reduce celestial detail. Minecraft's ordinary terrain
geometry occludes the sky; standard visibility limits for blindness, darkness,
lava, powder snow, and dense fog remain in effect. The sky blends into fog color
near the horizon. This is a procedural atmosphere, without a full physical model
of scattering and radiative transfer.

## Glow and sound

The solar sky and Rocket Mode use a linear RGBA16F buffer, multilevel bloom, and
a single exposure transform. Disable glow with `/astra-render bloom false` for
comparison. Defaults are `bloom-strength 0.65`, `bloom-threshold 1`,
`bloom-radius 0.65`, and `exposure 1`, all `/astra-render` subcommands.
See the [rendering documentation](RENDERING.md) for settings and limits.

Sound accompanies instability, collapse, and supernova: a rising rumble, tense
compression, a brief impact, and a decaying low-frequency tail. This is cinematic
sound design for the stellar state, not a model of sound propagation in a vacuum.
The mod includes original synthesized assets with no third-party samples.

```text
/astra-audio enabled true
/astra-audio volume 0.75
/astra-audio status
```

Volume accepts `0..1` and defaults to `0.75`. Minecraft's master volume and
Ambient/Environment slider also apply. `/astra-audio enabled false` disables only
these sounds. Settings belong to the current client session. Overworld volume
corresponds to a distance of 1 AU; Sol uses the camera's virtual distance, with
maximum gain bounded near the star. The physical coordinates of the flight room
are not used. The star's position on the screen does not affect volume.

The impact plays once when the client observes the transition from collapse to
flash. Joining late, returning to Sol, or reloading resources does not replay an
impact that already happened; the phase ambience may resume at its current
intensity. Pause, mute, leaving the system, reset, and logout stop this subsystem's
own voices. A new diagnostic cycle can play the impact again. Audio reads server
snapshots and does not change the star's phase or resource.

## Switching modes and limits

- `/astra-render environment off` restores the vanilla Overworld sky and disables
  this fog/lightmap correction. The server cycle continues according to its
  player-presence rules.
- `/astra-render environment planet` enables a separate planetary-profile preview.
  Use `auto` for the main solar scenario.
- The resource-pack profile `assets/minecraft/environments/overworld.json` takes
  precedence in `auto`: it supplies its own sky and lighting, without additional
  solar lightmap or fog correction.
- `/astra-render lighting false` disables lightmap correction and the additional
  lighting layer; the sky and server evolution continue. Restore lighting with
  `/astra-render lighting true`.
- If the Overworld shader is unavailable, vanilla sky, fog, and lightmap paths
  are used. Compatibility with third-party Overworld effects replacements and
  shader packs requires separate verification.

This scenario does not create planetary surfaces, a physical shock wave, damage,
changes to the Sol catalog orbits, destruction, or a usable energy economy. Additional profiles,
screen-space lighting, and their limits are covered in the
[rendering documentation](RENDERING.md).

## Verification scenario

The verification client provides separate phases; the first run requires a fresh
directory, and the second opens its saved world:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-solar-run -PverifyPhase=solar
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-solar-run -PverifyPhase=solar-restart
```

These phases check the day/night sky, weather, event stages, resource reload, and
continuation of the saved phase after restart. The 2026-09-28 run passed in Fancy
and Fabulous: exact restoration of a paused state, the full cycle through the
remnant, reset, block preservation, and a separate check of sky/block lightmap
contributions. The build, 47 JUnit tests, and 4 server GameTests also passed.
Local logs, 25 screenshots, and video are retained in
`Workflow/verification/solar-2026-09-28/RESULTS.md` and `solar-03/evidence`.
This verifies a specific scenario on Intel UHD / Mesa; it does not establish an
FPS target or compatibility with third-party shader packs or multiple clients.
