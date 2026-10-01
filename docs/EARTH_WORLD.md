# Continental Earth world preset

The **Astra Earth** world type creates an Overworld backed by the shared spherical
continental field. It is an opt-in integration build while planetary travel and
storage-boundary presentation are being connected. Existing Overworlds, saved
legacy landing patches and continental inspection worlds keep their generators.
On a bound Earth world, **R** departs from the player's actual geographic location.
In Sol flight, aim at a visible location on Earth and press **L**. The server
retains that body-fixed geographic point, validates its current visibility against
the saved continental field and prepares the corresponding storage chart. Planetary
rotation during packet delay does not move the selected latitude/longitude. The
request includes the current navigation epoch; stale requests cannot start a route. A miss is rejected; it does not redirect to a fixed patch or pole.
Ordinary Overworlds retain the legacy Moon/Earth landing-patch behavior.

Landing polls a bounded neighborhood around the selected column, then checks
actual saved blocks and a clear standing volume with an open departure corridor.
Forest canopies and snow-layer tops are included. No building is cleared or
flattened. Ocean arrivals stop at the water surface; normal swimming still belongs
to Minecraft. A bound Earth permits departure and recovery while the standing eye
remains above water. Submerged heads and non-water fluids remain excluded.
Obstructed destinations can fail safely; flight remains recoverable. **B** cancels the guided route and **R** returns to its saved real
source. Minecraft still owns dimension transfer and chunk loading. Preparation allows up to
45 seconds of server ticks for distant tall charts; it shows a loading message
instead of a fictional route countdown. Guided ascent/descent then show their actual
remaining server ticks.

The bound Earth uses the Overworld's saved calendar for Sol orbital motion and
its complete axial/spin orientation. The default 365 game days span one canonical
Earth revolution; radii and orbital descriptors retain their physical values.
Surface sunlight, the rotating orbital height field and the landing point use
that same frame. Free inspection within six radii follows the nearest body's
orbital translation while preserving the camera's heading. Stopping daylight
also stops this orbital motion. A time or season-policy discontinuity safely
cancels guidance and preserves a nearby free observer's relative position.
Changing time during an ascent restores its real surface source. Shader animation
and stellar evolution keep their existing independent ownership and rates.

The geographic surface also continues beyond loaded chunks as a bounded distant
mesh from the same saved height/climate field. Oceans and mountain silhouettes
use the physical Earth radius. Near the player, the mesh preserves the host's flat
chart appearance; a smooth presentation transition ends at 32.768 km in the physical
tangent view. This does not reproject host/DH blocks or change collision. Current
chunks and DH LODs render over the background. The derived mesh contains procedural
terrain, not saved building summaries or voxel interaction targets.
Beach/desert materials use the same climate classification as the block generator;
water follows the source-water surface datum. Derivative-filtered material detail
reduces distant texture shimmer. This mesh does not generate distant voxel chunks.

Terrain, ocean and cloud depth use separate, explicit projections. Clouds stop at
the visible distant terrain instead of shining through it. In Fabulous mode,
opaque depth is preserved before the host transparency resolve; its fullscreen
effect cannot masquerade as a nearby obstruction and erase clouds viewed from above.
Private color/depth
buffers are rebuilt after resize/reload; background CPU work uses immutable geography
and is canceled on context retirement. Active Iris packs keep their sky/terrain
ownership; the native far background yields while a pack is active.

The preset uses the real 6,371,000-meter reference radius. Six versioned cube faces
cover the globe, including both poles. Each face has six persistent, disjoint
4064-meter altitude bands. Band zero of positive X is `minecraft:overworld`; the
remaining charts have permanent `astraengine:earth/<face>/<band>` dimension IDs.
Only visited/generated chunks consume voxel storage. Newly generated uniform rock
and water sections save directly as the standard singleton palette, avoiding a
4096-entry expansion. Edited palettes use Minecraft's normal serializer; no new
save format or global palette patch is introduced. At the FEATURES stage, Earth
heightmap priming skips section palettes which cannot satisfy the requested block
predicate. It still reads actual blocks, including earlier mod edits, and writes
the standard host heightmaps. Other generators retain Minecraft's original path.
This avoids scanning thousands of water layers for a seabed outside the current
altitude band. The whole globe is not
generated at full block detail during creation.

Host Y ranges from -2032 through 2031. Physical altitude is
`hostY + band * 4064`, covering [-10160, 14224) meters. The generator does not
compress mountains or insert bedrock, new sea surfaces or summit caps at band
boundaries. Horizontal storage uses a gnomonic projection, so chart block distances
are not a globally uniform metric on the sphere.

Within48 chart meters of a storage edge, the server prepares a bounded neighborhood
using expiring FULL-status chunk tickets. It sends at most32 real sections, including
saved block states, light and biomes, under a540-KiB packet budget. Capture never
requests synchronous generation. The immutable observations cannot edit blocks or
authorize travel. They expire on departure, player replacement or disconnect;
canonical chunks remain in their original permanent worlds.

Neighboring baked block models and fluids use a cancellable CPU mesh request and
owned GPU buffers. Geometry retains section-local floats; double-derived projective
coefficients map adjacent charts relative to the camera. Native views share terrain
depth; with an Iris pack these neutral observations compose after finalization and
are not pack shadow/reflection inputs. Resource reload rebuilds GPU data while
retaining the connection's numeric observation. Block-entity renderers and automatic
walking/collision handoff are still being integrated. Observed neighboring blocks
are not yet interaction targets in the current chart.

Terrain version, exact 64-bit seed, chart version, face and band are saved in the
generator codec. Unknown versions, mismatched biome faces, incomplete palettes and
fractional/overflowing identities fail validation. Startup rejects a partial or
changed active Earth preset instead of allocating replacement worlds. Ordinary
host chunk storage retains builds and block entities through shutdown and restart.

New Earth worlds select terrain version 2, with domain-warped gradient noise,
rotated octaves and connected mountain ridges. Version 1 remains readable without
changing its heights or biomes. Terrain and biome source versions must agree;
the server sends the saved terrain version to clients (Earth context protocol 2).
The seed and full generator definition remain world-owned, not renderer settings.

The immutable continental sampler supplies elevation, temperature and moisture.
Twelve named climate classes select registry-owned ocean, beach, snow, alpine,
desert, savanna, jungle, taiga, forest and plains biomes. Their normal vegetation
features remain active; consumers can extend them through ordinary biome modifiers
and placed features. This version does not generate structure sets or carvers.

Snow and freezing use physical latitude/elevation in Earth generation and server
weather checks. A narrow `Biome.shouldSnow`/`shouldFreeze` injection replaces the
temperature predicate because those host methods otherwise apply their own local-Y
lapse rate. All other block, light, survival and water-edge checks remain host-owned.
Legacy worlds retain the original predicate. Submerged storage ceilings cannot
become artificial ice surfaces. This is the base climate calculation; a seasonal
snow accumulation/melting simulation is not supplied by this hook.

The sky reads geographic latitude/longitude and physical altitude from these charts.
Its 1800..3200-meter cloud layer retains the same sea-level datum in every altitude
band; see [seasons and atmosphere](SEASONS.md).

In space, the server-selected Earth uses samples of this exact field. A 1025x513
global height/climate map and four 513x513 local tiles at 4/32/256/1024-meter spacing
provide progressive detail. Tile edges blend into coarser levels. Height interpolation uses shader float
arithmetic instead of limited-precision hardware filter weights, preventing
meter-high terraces in kilometer-scale relief. These RGBA32F
textures occupy about 24.1 MiB; generation runs on a cancellable host worker request,
never through synchronous chunk generation in a render callback. Resource reload
and logout release owned textures. Legacy saves retain their versioned geography.
The bounded relief march resolves height-dependent silhouette and parallax; its
step budget still limits very thin terrain features at grazing angles. Atmospheric
transport ends at the actual mountain hit, avoiding haze integrated behind terrain.

Orbital and distant-mesh materials use an immutable palette captured on the render
thread from active block-atlas textures and the default Earth biomes' foliage,
grass and water tints. Forest, jungle and taiga summaries weight their canopies;
they do not reconstruct individual trees. Exposed freezing and snow cover use the
same physical zero-Celsius weather threshold and quantized surface altitude as
the Earth weather hook. This does not change saved biome classes or terrain versions.
Ice carries its own solid material flag, so a pale blue texture cannot accidentally
select ocean reflections. Resource reload and logout invalidate the captured palette.

The orbital HDR path shades host display colors before decoding its display transform.
This avoids brightening green canopies twice and amplifying white materials at night.
Atmospheric scattering, sunlight and exposure still change their visible appearance.
These are base regional summaries: custom consumer biome-palette synchronization,
observed buildings, snow removal and other player edits are not yet represented here.
The optional DH base-column override remains undecorated until actual chunk data arrives.

The server announces the Earth binding at login. The client retains it across
resource reload and clears it on logout. F3 then displays longitude, latitude and
physical altitude in these charts; reduced-debug privacy is preserved. It never
infers an Earth binding solely from the Overworld name.

`AstraGeography.planetaryReference(ServerLevel)` exposes the immutable
`GeographicReference` projection on the owning server thread. `snapshot` and
`resolve` use it, including exact differential velocity. The older patch-only
`reference(ServerLevel)` method retains its original signature and behavior.
`/astra geography here` reads the binding; `tp` currently resolves within the
current chart and retains the operator, travel-ownership and host-veto checks.

Verification includes every chart codec, actual generated ProtoChunks, independent
column/heightmap agreement, pole/edge coordinates, altitude ownership, climate
temperature, native tree decoration, geographic F3 and an independent-process
restart retaining player pose, placed blocks and chest inventory. New version-two
creation and a saved version-one world both pass independent-process restart.
The orbital fixture checks full-globe and close relief, exact uploaded terrain
texels, resource reload, cache retirement and texture-binding restoration on
Fabulous. Native scenarios:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-earth -PverifyPhase=earth-generation-create
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-earth -PverifyPhase=earth-generation-restart
```

The `earth-boundary-observations` phase checks real band/face/corner preparation,
canonical edits delivered over the actual wire, immutable prior observations,
visible gold-to-emerald replacement and owned ticket retirement. It uses a fresh
Earth preset and preserves its evidence separately from player saves.

The separate `earth-orbit` phase verifies the orbital presentation path. The
`earth-materials` phase captures forest, taiga, jungle, ice and snow by day and
night, reads back actual GPU palette uniforms, and verifies invalidation on resource
reload. It uses `/astra-flight map` to avoid another mod's M-key binding in a test pack.
The `earth-landscape` phase covers lowland, summit, high-altitude, coast, pole, face-edge
and foothill views, plus resource reload and resize. It retains screenshots and
31 asynchronous GPU timer samples per view; these measure the complete `AFTER_SKY`
event interval, including any optional handlers, rather than total frame time.
`earth-landscape-pack` checks the same scenes while an actual Iris pack owns rendering.
The geographic
travel fixture uses a fresh `earth-travel-create` directory, then
`earth-travel-restart` in that same completed directory. It exercises actual
navigation packets, forest and mountain departures, both polar charts, delayed
body-fixed aiming, stale input epochs, collision-checked arrival, canceled ascent
and persistence of pose, blocks and a chest inventory. Verification owns disposable
worlds and never modifies a user's existing save.

Use a fresh disposable directory for creation. Restart accepts only that completed
fixture; verification code and worlds are not included in the distributed JAR.
