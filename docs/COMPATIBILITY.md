# Renderer compatibility

AstraEngine runs without Sodium or Iris. Optional integration targets Minecraft
1.21.1 and NeoForge 21.1.252. Sodium and Iris are not bundled with the mod, and
AstraEngine never changes the player's shader-pack selection or enabled setting.

The ownership rules below are implemented and verified in the pinned Sodium and
Iris stacks listed below. The experimental Iris beta remains experimental. These
native checks cover the specified scenarios and settings, not arbitrary version
combinations or shader packs.

## Rendering ownership

| Feature | Plain NeoForge, Sodium, or Iris with no active pack | Iris with an active shader pack |
| --- | --- | --- |
| Overworld sky, clouds, fog and lightmap | Astra's automatic seasonal atmosphere, or the selected preview | The pack owns these effects, even with a forced Astra environment |
| Opaque-world lights, flashlight, profile darkness and profile post-processing | Ordinary Astra lighting path | Disabled; the pack owns terrain lighting and post-processing |
| Rocket cosmos and selected resource-profile skies | Ordinary celestial stage | Composited after the pack in non-Overworld dimensions, only into clear-depth sky pixels |
| Bound Moon/Earth surface skies | Shared orbital frame, atmosphere and visual skylight correction | Late celestial sky composition; pack retains terrain lighting and fog |
| Consumer ship visuals and eligible local editor shapes | Analytic depth before Astra opaque lighting | Depth-aware overlays after pack composition |
| Ship GUI preview and picking | Engine preview and conservative picking | Engine preview and conservative picking |
| User-compiled GLSL editor effect | Explicitly enabled final effect | Explicitly enabled final effect, after pack composition |
| Server seasons, stellar evolution, navigation and persistence | Server-owned state | The same server-owned state continues |

An active pack does not automatically read Astra's orbital seasons, apparent Sun
size, light pollution or diagnostic stellar evolution. Its Overworld Sun, day
length and lighting may therefore differ from Astra's saved astronomical state.
Turning shaders off restores the Astra sky at the current state; it does not
reset the date or stellar event. These ownership choices apply to actual active
pack state, including after toggling, reload and dimension changes.

The first [Moon/Earth patches](SURFACE_TRAVEL.md) use the same depth-preserving
late sky boundary. Their land/depart/return and persisted restart scenarios pass
with the stable Iris/add-on row and Complementary r5.9.3. This does not synchronize
the pack's terrain light or fog with the surface's orbital Sun, and it adds no
pack shadow, weather or cloud integration.

In the Overworld, forcing `environment space`, `environment planet` or a named
resource profile does not replace an active pack's sky. Selected late resource
skies apply only in other dimensions. Local editor shapes retain their ordinary
eligibility: they are disabled by `environment off` and in the flight world.
Consumer ship visuals remain independent of the environment selection.

Late skies render at NeoForge `AFTER_LEVEL`, after Iris finalizes its world
composition. Astra copies the finalized scene and depth into owned attachments,
draws its sky separately, and replaces only untouched depth pixels (`1.0`).
Opaque terrain and the pack's previously rendered foreground remain in the
scene. The sky compositor preserves host depth. Consumer ships and local shapes
then draw against that depth, followed by any explicitly enabled GLSL editor
effect. Custom passes and consumer collection are excluded from shadow passes.

These overlays do not become shader-pack materials. They do not participate in
pack shadow maps, reflections, global illumination, motion vectors or temporal
antialiasing history. Transparent geometry, temporal edge reconstruction, custom
depth conventions and non-default output color spaces can affect composition.
Default sRGB is the initial verification scope. A specific passing pack is not
a guarantee for every pack or every graphics setting.

## Diagnostics

Run this read-only client command in a world:

```text
/astra-render compatibility
```

It reports loaded Sodium, Iris, Sodium Extra, Reese's Sodium Options, Chloride and Distant Horizons
versions, Iris public API revision, actual active-pack state and shadow-pass
state at the time of the query. Installed versions come from mod metadata, not
the JAR filename. For example, the official Iris 1.8.12 artifact reports an
internal `1.8.12-snapshot+mc1.21.1-local` version string.

If the installed Iris public API cannot be linked, the adapter logs one error
and conservatively suppresses custom world passes. Diagnostics explicitly mark
API facts unavailable; they do not report an invented active pack. Restoring a
supported optional-mod installation requires a client restart. No setting is
changed automatically.

## Pinned verification matrix

All rows use Minecraft 1.21.1, NeoForge 21.1.252 and original upstream artifacts.
The active-pack target is Complementary Reimagined **r5.9.3**. Versions in each
row form a distinct stack; the stable Iris pair does not use the newer standalone
Sodium generation. Status is recorded as of 2026-09-30.

| Stack | Sodium | Iris | Sodium Extra | Reese's Options | Chloride | Current evidence |
| --- | --- | --- | --- | --- | --- | --- |
| Ordinary renderer baseline | 0.8.13 | Absent | 0.9.4 | 2.2.4 | Absent | Pre-integration native `ship-visual` passed |
| Stable Iris, shaders off baseline | 0.6.13 | 1.8.12 | Absent | Absent | Absent | Pre-integration native `ship-visual` passed |
| Standalone Sodium with add-ons | 0.8.13 | Absent | 0.9.4 | 2.2.4 | 1.8.1 | Final native `ship-visual` and `volumetric` passed; 12 and 23 captures |
| Stable Iris with add-ons | 0.6.13 | 1.8.12 | 0.6.0 | 1.8.3 | 1.7.8 | Final native `render-compat` passed; 19 captures |
| Experimental Iris beta with add-ons | 0.8.13 | 1.8.14-beta.1 | 0.9.4 | 2.2.4 | 1.8.1 | Final native `render-compat` passed; 19 captures; beta remains experimental |

The two baseline runs checked preview/picking, reload, resize, opaque depth and
free-camera flight through the consumer visual fixture. They were performed
before the new late-composition integration. The final Sodium/add-on run repeated
that scenario against the completed integration; its covered-wall comparison had
zero image difference when the hidden visual was enabled or disabled. A separate
23-capture Sodium/add-on volumetric regression passed close-wall, close-roof and
night comparisons with zero difference, exact sunset repetition, quality changes,
resource reload and resize.

Final Iris runs each retained 19 captures covering pack on/off/on, resource reload,
forced Overworld `space`/`planet` previews, consumer preview, resize, opaque depth,
real free-camera flight and return. The pack retained its Overworld sky with both
forced environments. The stable run observed 1,236 native shadow-stage events and
the beta run 1,272, with zero Astra ship or light collection events during shadows
in either run. Flight supplied 15 stable-stack and 16 beta-stack presented frames
with live late Cosmos render targets and visible bodies.

These final runs used default add-on and pack options, Fancy graphics and default
sRGB output on Intel UHD Graphics CML GT2 with Mesa 26.2.3. They establish the
bounded visual/ownership scenarios, not an FPS target or a general performance
improvement. A separate plain NeoForge client without these optional mods passed
the complete `ship-visual` scenario in Fabulous graphics (12 captures). A dedicated
server without the optional mods passed all 18 GameTests; Iris client classes are
not needed for server startup.

The local original artifact manifest, SHA-512 verification and scenario evidence
are retained under `Workflow/verification/render-compat-2026-09-30/`. That folder
is developer-local and is not included in a repository clone or shipped JAR.

The subsequent satellite/map/supernova update repeated the stable Iris/add-on
`render-compat` phase with the same unmodified pack: 19 captures, 1,196 observed
shadow stages, zero Astra ship/light collections in shadow passes, and 17
presented flight frames with live late Cosmos targets. Plain Fancy and Fabulous
also passed the new `celestial-polish` phase (43 captures each). These checks do
not add support for Astra stellar events inside a shader pack's Overworld sky.

## Reproduce the native scenario

Use a new disposable game directory for each version stack and each run. The
scenario creates a world and refuses to overwrite an existing fixture world.
Do not point it at a personal Minecraft installation: verification code toggles
Iris shaders off and on, reloads resources and changes window/game settings in
the selected test directory. Production AstraEngine does not perform those
configuration changes.

1. Create `Workflow/verification/my-render-compat/mods`, `shaderpacks` and `config`
   directories, with the latter two also inside `my-render-compat`.
2. Download the exact original artifacts for one complete Iris row in the matrix and put
   its five mod JARs in `mods/`. Do not mix the stable and beta Sodium generations.
   Gradle already supplies the local AstraEngine and verification mod; do not add
   another AstraEngine JAR.
3. Put the unchanged `ComplementaryReimagined_r5.9.3.zip` in `shaderpacks/`, without
   a pack-specific settings override, and create `config/iris.properties`:

   ```properties
   enableShaders=true
   shaderPack=ComplementaryReimagined_r5.9.3.zip
   colorSpace=SRGB
   ```

4. From the repository root, run:

   ```sh
   ./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-render-compat -PverifyPhase=render-compat -PverifyGraphics=fancy
   ```

The fixture has a 600-second deadline; allow additional time for Gradle startup
and dependency preparation. It requires an actual working shader pack at entry,
then checks active/off/on ownership, reload, forced environment selections,
shadow-pass isolation and the consumer ship/flight scenario. A successful run
writes `verified-render-compat.txt`; process startup or exit code alone is not
sufficient. Inspect `evidence/`, `render-compatibility-status.txt`,
`render-compatibility-observations.csv`, `ship-visual-observations.csv` and
`logs/latest.log` in the chosen directory.

For the standalone Sodium/add-on row, prepare another fresh directory containing
its four mod JARs, omit Iris and the shader pack, and use `-PverifyPhase=ship-visual`.
The active-pack phase deliberately requires Iris; it is not an absence test.

## Distant Horizons terrain experiment

The independent [highlands prototype](PLANETARY_TERRAIN.md) was verified with
original **Distant Horizons 3.3.3**, Minecraft 1.21.1 and NeoForge 21.1.252.
[Exact publisher release](https://modrinth.com/mod/distanthorizons/version/9w34y8ai).
DH is optional and is not bundled. Its public API is a compile-only dependency
for the cloud-ownership bridge; the verification source set also uses API 7.2
for measurements. No DH implementation, renderer or configuration is bundled.

The plain NeoForge experiment passed with native render distance **4 chunks**,
DH radius **32 chunks**, fixed midday and the real 2048-block generator. Same-pose
DH ON/OFF/ON captures show the distant mountain slope disappearing and returning;
the run also observed target-world LOD callbacks and nonclear DH depth. Early
captures during cold cache/mesh preparation lacked terrain and are retained as
readiness evidence, not presented as a finished frame. This is a bounded visible
terrain test, not an FPS qualification or a maximum-distance measurement.

The same visible ON/OFF/ON scenario also passed with the stable add-on stack:
Sodium 0.6.13, Iris 1.8.12, Sodium Extra 0.6.0, Reese's Options 1.8.3,
Chloride 1.7.8 and active Complementary Reimagined r5.9.3. The pack retains its
sky, clouds and fog. The logs retain upstream shader warnings about unavailable
version-specific uniforms and uninitialized shader values; the scoped terrain
scenario completed with no captured OpenGL error. This is not a claim that all
shader-pack features or other dimensions are compatible.

For this custom terrain, select DH's **CHUNKS_ONLY** generation plan. The
[upstream API contract](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/api/src/main/java/com/seibel/distanthorizons/api/enums/worldGeneration/EDhApiGeneratorPlan.java)
recommends it where the rough surface approximation would not match a custom
world generator. Astra does not silently alter the player's DH settings. The
fixture changes temporary API overrides in a disposable profile and restores them.
LODs use DH's own derived cache; authoritative terrain and player modifications
remain in Minecraft's saved chunks.

**This does not establish full Astra sky compatibility.** The highlands currently
use the host sky. DH has a separate depth buffer, while Astra's late custom sky
currently tests host depth alone. With Iris, a distant-terrain pixel can therefore
be mistaken for empty sky and overwritten. The corresponding
[upstream copy pass](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/a54dd3eb1df0078e63a71266d042c10d9cda4493/common/src/main/java/com/seibel/distanthorizons/common/render/openGl/postProcessing/copy/GlDhCopyShader.java)
composites color without making host depth a combined terrain buffer. Custom
planet skies, Astra terrain-light post effects, distant transparency, reload and
multiplayer need their own integration checks. Do not infer support for those
paths from the highlands experiment.

Local original artifacts, hashes, source audit and native evidence are under
`Workflow/verification/planetary-terrain-2026-09-30/`. They are not shipped in the JAR.

## Distant Horizons cloud ownership

DH 3.3.3 renders its own box clouds independently of NeoForge's ordinary
`DimensionSpecialEffects.renderClouds` hook. Astra's volumetric atmosphere and
those DH clouds can otherwise overlap, even without Sodium. The optional bridge
uses DH's public cancellable generic-object event and suppresses only the exact
`DistantHorizons:Clouds` group in the currently rendered dimension while Astra
owns that sky. Terrain LODs and unrelated generic objects retain DH ownership.
See the upstream [cloud-group construction](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/core/src/main/java/com/seibel/distanthorizons/core/render/renderer/CloudRenderHandler.java)
and [cancellable event contract](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/api/src/main/java/com/seibel/distanthorizons/api/methods/events/abstractEvents/DhApiBeforeGenericObjectRenderEvent.java).

The engine does not rewrite DH, Minecraft cloud, or shader-pack settings. With
Astra's Overworld atmosphere active, Minecraft Clouds ON shows Astra's clouds;
Clouds OFF hides them and does not reveal a second DH cloud layer. Disabling the
Astra environment restores DH ownership. An actual active Iris pack retains
cloud ownership and receives no Astra cloud cancellation. Missing Astra shaders
restore the host sky. An unavailable optional event API leaves DH clouds untouched
and logs one failure; it does not change graphics preferences.
The listener binds once per connection, survives reload without duplication and
unbinds on logout. It retains no world, player or GPU resource.

DH's own `overrideVanillaGraphicsSettings` option can change Minecraft Clouds to
OFF during its initial renderer setup. Astra honors that resulting choice; it
does not silently turn clouds back on. Select Clouds ON to display Astra's
volumetric clouds. This startup behavior is in the upstream
[LOD renderer](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/core/src/main/java/com/seibel/distanthorizons/core/render/renderer/LodRenderer.java).

The native `dh-clouds` fixture uses original DH 3.3.3 and Zume 1.2.2 without
Sodium. The 2026-10-01 native run passed Fancy/OFF/Fast, disabled-Astra fallback
and reload comparisons, with one event listener before and after reload. The
`dh-clouds-iris` run also passed with the stable Iris/add-on stack and Complementary
r5.9.3: 4,317 observed cloud groups and zero Astra cancellations while the pack
remained active. Both runs retain six captures and unchanged pack/API settings.
For reproduction, add the original Zume 1.2.2 artifact to a fresh profile and run
`runVerifyClient -PverifyPhase=dh-clouds` or `dh-clouds-iris` with the corresponding
mod stack and `-PverifyDirectory=...`.
These are explicit verification scenarios, not a guarantee for every DH release.

## Spherical ocean calibration

The separate [horizon calibration world](PLANETARY_HORIZON.md) draws its analytic
sea/sky at `AFTER_SKY`, before plain DH terrain. It borrows no DH depth and installs
no DH program override. Native checks cover the pinned DH 3.3.3 at 32 chunks / 512 m:
real LOD depth and visible ON/OFF/ON composition with the spherical background.
Its meshes stay flat. The ocean now follows the source-water height and shares
a marine fog color with the host. At the same daylight camera pose, the central
near-water join's mean largest-channel jump fell from 150.49 to 11.00 on an 8-bit
image, a 92.69% reduction. This is one fixed visual comparison: a smaller material
seam remains, and seamless joins or larger-distance spherical DH geometry are
not qualified.

With the stable Iris/Sodium/Chloride stack and active Complementary Reimagined,
the horizon pass yields completely. Native toggle/reload checks retain the actual
pack settings and zero Astra horizon draws. This is a host/pack fallback, not
curved pack geometry or shared shadows/reflections. The older custom celestial
sky/DH depth limitation above remains open outside this opt-in path.

## Original artifact references

The links below identify the exact upstream files used for the matrix, rather
than promising that the newest download is interchangeable with them. Install
optional mods from their publishers; AstraEngine redistributes none of these
mods or shader packs.

| Component | Original upstream artifacts |
| --- | --- |
| Sodium | [0.6.13](https://cdn.modrinth.com/data/AANobbMI/versions/Pb3OXVqC/sodium-neoforge-0.6.13%2Bmc1.21.1.jar), [0.8.13](https://cdn.modrinth.com/data/AANobbMI/versions/uMOpc5uV/sodium-neoforge-0.8.13%2Bmc1.21.1.jar) |
| Iris | [Stable 1.8.12](https://cdn.modrinth.com/data/YL57xq9U/versions/t3ruzodq/iris-neoforge-1.8.12%2Bmc1.21.1.jar), [experimental 1.8.14-beta.1](https://cdn.modrinth.com/data/YL57xq9U/versions/KduFYu4t/iris-neoforge-1.8.14-beta.1%2Bmc1.21.1.jar) |
| Sodium Extra | [0.6.0](https://cdn.modrinth.com/data/PtjYWJkn/versions/pFmw1eci/sodium-extra-neoforge-0.6.0%2Bmc1.21.1.jar), [0.9.4](https://cdn.modrinth.com/data/PtjYWJkn/versions/ufpcXU9c/sodium-extra-neoforge-0.9.4%2Bmc1.21.1.jar) |
| Reese's Sodium Options | [1.8.3](https://cdn.modrinth.com/data/Bh37bMuy/versions/xAiCe6w8/reeses-sodium-options-neoforge-1.8.3%2Bmc1.21.4.jar), [2.2.4](https://cdn.modrinth.com/data/Bh37bMuy/versions/XjF2IkL8/reeses-sodium-options-neoforge-2.2.4%2Bmc1.21.1.jar) |
| Chloride | [1.7.8](https://cdn.modrinth.com/data/yD9qW65f/versions/wHVcHnEW/chloride-NEOFORGE-mc1.21.1-v1.7.8.jar), [1.8.1](https://cdn.modrinth.com/data/yD9qW65f/versions/IgWRdE2d/chloride-NEOFORGE-mc1.21.1-v1.8.1.jar) |
| Shader pack | [Complementary Reimagined r5.9.3](https://cdn.modrinth.com/data/HVnmMxH1/versions/Bqen1mJX/ComplementaryReimagined_r5.9.3.zip) |

Reese's Options 1.8.3 retains `mc1.21.4` in its filename; its
[publisher metadata](https://api.modrinth.com/v2/version/xAiCe6w8) also lists
Minecraft 1.21.1. The stable Iris
[publisher metadata](https://api.modrinth.com/v2/version/t3ruzodq) pins Sodium
0.6.13. The newer Iris row is explicitly a
[beta release](https://api.modrinth.com/v2/version/KduFYu4t).

## Integration boundary for developers

The optional bridge calls the
[Iris public API](https://github.com/IrisShaders/Iris/blob/eb7afb99f747cc8ed5ee4072119539035d33cefd/common/src/api/java/net/irisshaders/iris/api/v0/IrisApi.java)
only after NeoForge reports Iris loaded. It uses actual pack/shadow queries,
without reflection into private pipeline state or a replacement Iris renderer.
Client diagnostics and the optional bridge are outside common/server startup.
Gradle uses the pinned Iris artifact as `compileOnly`; building the integration
requires that API dependency, while ordinary runtime and dedicated servers do
not require Iris. Verification-only code and third-party dependencies are not
bundled into the production JAR.

Pass order was checked against the pinned local Minecraft/NeoForge sources,
[NeoForge's renderer patch](https://github.com/neoforged/NeoForge/blob/1.21.1/patches/net/minecraft/client/renderer/GameRenderer.java.patch)
and the corresponding
[Iris world finalization hooks](https://github.com/IrisShaders/Iris/blob/eb7afb99f747cc8ed5ee4072119539035d33cefd/common/src/main/java/net/irisshaders/iris/mixin/MixinLevelRenderer.java).
This is a version-specific integration boundary. See
[render ownership](RENDERING.md#render-ownership-and-limits) and the
[consumer ship API](SHIP_RENDERING.md) for ordinary-mode contracts and budgets.
